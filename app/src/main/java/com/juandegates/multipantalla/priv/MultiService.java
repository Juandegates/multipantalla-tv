package com.juandegates.multipantalla.priv;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Surface;

import com.juandegates.multipantalla.IMultiService;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Corre en un proceso aparte con permisos de shell (lo arranca Shizuku).
 * Crea las pantallas virtuales, abre apps en ellas y les manda teclas.
 */
public class MultiService extends IMultiService.Stub {

    private static final String TAG = "MP-Service";

    // Banderas de pantalla virtual (las mismas que usa scrcpy para --new-display).
    private static final int FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int FLAG_OWN_FOCUS = 1 << 14;
    private static final int FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;

    private final Context appContext;
    private final ConcurrentHashMap<Integer, VirtualDisplay> displays = new ConcurrentHashMap<>();
    private volatile String lastError = "";

    private DisplayManager displayManager;
    private Object inputManager;
    private Method injectMethod;
    private Method setDisplayIdMethod;

    /** Shizuku usa este constructor (versión 13 o más nueva). */
    public MultiService(Context context) {
        this.appContext = context;
        init();
    }

    /** Constructor de respaldo para versiones viejas de Shizuku. */
    public MultiService() {
        this.appContext = null;
        init();
    }

    private void init() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.addHiddenApiExemptions("");
            }
        } catch (Throwable t) {
            remember("HiddenApiBypass", t);
        }
        try {
            Workarounds.apply();
        } catch (Throwable t) {
            remember("Workarounds", t);
        }
    }

    // ---------------------------------------------------------------- pantallas

    private DisplayManager displayManager() throws Exception {
        if (displayManager != null) {
            return displayManager;
        }
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        Exception firstError = null;
        try {
            displayManager = ctor.newInstance(FakeContext.get());
            return displayManager;
        } catch (Exception e) {
            firstError = e;
        }
        if (appContext != null) {
            displayManager = ctor.newInstance(FakeContext.wrap(appContext));
            return displayManager;
        }
        throw firstError;
    }

    @Override
    public int createDisplay(String name, int width, int height, int dpi, Surface surface) {
        int base = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY
                | FLAG_SUPPORTS_TOUCH | FLAG_DESTROY_CONTENT_ON_REMOVAL;
        int trusted = FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED
                | FLAG_TOUCH_FEEDBACK_DISABLED;
        if (Build.VERSION.SDK_INT >= 34) {
            trusted |= FLAG_OWN_FOCUS | FLAG_DEVICE_DISPLAY_GROUP;
        }

        // Primero con todas las banderas; si el sistema las rechaza, con menos.
        int[] attempts = {base | trusted, base | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP, base};
        StringBuilder errors = new StringBuilder();
        for (int flags : attempts) {
            try {
                VirtualDisplay vd = displayManager().createVirtualDisplay(name, width, height, dpi, surface, flags);
                if (vd == null || vd.getDisplay() == null) {
                    errors.append("flags 0x").append(Integer.toHexString(flags)).append(": null\n");
                    continue;
                }
                int id = vd.getDisplay().getDisplayId();
                displays.put(id, vd);
                Log.i(TAG, "Pantalla " + id + " creada con flags 0x" + Integer.toHexString(flags));
                return id;
            } catch (Throwable t) {
                errors.append("flags 0x").append(Integer.toHexString(flags)).append(": ")
                        .append(shortError(t)).append('\n');
            }
        }
        lastError = "No se pudo crear la pantalla:\n" + errors;
        Log.e(TAG, lastError);
        return -1;
    }

    @Override
    public void setDisplaySurface(int displayId, Surface surface) {
        VirtualDisplay vd = displays.get(displayId);
        if (vd == null) {
            return;
        }
        try {
            vd.setSurface(surface);
        } catch (Throwable t) {
            remember("setDisplaySurface", t);
        }
    }

    @Override
    public void releaseDisplay(int displayId) {
        VirtualDisplay vd = displays.remove(displayId);
        if (vd == null) {
            return;
        }
        try {
            vd.setSurface(null);
        } catch (Throwable ignored) {
        }
        try {
            vd.release();
        } catch (Throwable t) {
            remember("releaseDisplay", t);
        }
    }

    // ---------------------------------------------------------------- comandos

    @Override
    public String exec(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String out = readAll(process.getInputStream());
            process.waitFor();
            return out;
        } catch (Throwable t) {
            remember("exec", t);
            return "ERROR: " + shortError(t);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buffer.size() < 64 * 1024) {
                buffer.write(chunk, 0, n);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8.name()).trim();
    }

    // ---------------------------------------------------------------- teclas

    private boolean prepareInput() {
        if (injectMethod != null) {
            return true;
        }
        try {
            Class<?> cls;
            Object instance;
            try {
                cls = Class.forName("android.hardware.input.InputManagerGlobal");
                instance = cls.getMethod("getInstance").invoke(null);
            } catch (ClassNotFoundException e) {
                cls = Class.forName("android.hardware.input.InputManager");
                instance = cls.getMethod("getInstance").invoke(null);
            }
            inputManager = instance;
            injectMethod = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
            return true;
        } catch (Throwable t) {
            remember("prepareInput", t);
            return false;
        }
    }

    @Override
    public boolean injectKey(int displayId, int action, int keyCode, int repeatCount, int metaState) {
        if (prepareInput()) {
            try {
                long now = SystemClock.uptimeMillis();
                KeyEvent event = new KeyEvent(now, now, action, keyCode, repeatCount, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
                setDisplayIdMethod.invoke(event, displayId);
                Object ok = injectMethod.invoke(inputManager, event, 0);
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
            } catch (Throwable t) {
                remember("injectKey", t);
            }
        }
        // Plan B: el comando "input", más lento pero casi siempre funciona.
        if (action == KeyEvent.ACTION_UP) {
            exec("input -d " + displayId + " keyevent " + keyCode);
        }
        return false;
    }

    // ---------------------------------------------------------------- otros

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void destroy() {
        for (Integer id : displays.keySet()) {
            releaseDisplay(id);
        }
        System.exit(0);
    }

    private void remember(String where, Throwable t) {
        lastError = where + ": " + shortError(t);
        Log.e(TAG, lastError, t);
    }

    private static String shortError(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getClass().getSimpleName() + ": " + root.getMessage();
        if (msg.length() > 300) {
            msg = msg.substring(0, 300);
        }
        return msg;
    }

}
