package com.juandegates.multipantalla.priv;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.audiopolicy.AudioPolicy;
import android.media.session.MediaSession;
import android.os.IBinder;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.juandegates.multipantalla.IMultiService;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corre en un proceso aparte con permisos de shell (lo arranca Shizuku).
 * Abre apps como ventanas libres, les pasa el control y maneja su audio.
 */
public class MultiService extends IMultiService.Stub {

    private static final String TAG = "MP-Service";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int USER_CURRENT = -2;
    private static final int PLAYER_STATE_STARTED = 2;

    private final Context appContext;
    private volatile String lastError = "";

    // Volumen deseado por uid de app (solo los que no están al 100 %).
    private final Map<Integer, Float> volumes = new ConcurrentHashMap<>();
    private final Map<String, Integer> uidCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

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
        // Las apps crean reproductores nuevos (cada video, cada anuncio):
        // cada segundo se les vuelve a poner el volumen elegido.
        ticker.scheduleWithFixedDelay(this::reapplyVolumes, 1, 1, TimeUnit.SECONDS);
    }

    private Context ctx() {
        try {
            return FakeContext.get();
        } catch (Throwable t) {
            if (appContext != null) {
                return FakeContext.wrap(appContext);
            }
            throw t;
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
            if (buffer.size() < 128 * 1024) {
                buffer.write(chunk, 0, n);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8.name()).trim();
    }

    // ---------------------------------------------------------------- ventanas

    @Override
    public String launchFreeform(String component, int left, int top, int right, int bottom) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            return "error:componente inválido " + component;
        }
        String pkg = cn.getPackageName();
        StringBuilder notes = new StringBuilder();

        String first = launchOnce(cn, left, top, right, bottom, notes);
        if (!first.startsWith("ok:")) {
            return first;
        }
        // Algunas apps (YouTube) solo acomodan su imagen al tamaño con el que nacen.
        // Android recuerda el último tamaño de cada app, así que al cerrarla y
        // abrirla otra vez ya nace del tamaño correcto (sin quedar cortada).
        SystemClock.sleep(700);
        exec("am force-stop " + pkg);
        SystemClock.sleep(400);
        String second = launchOnce(cn, left, top, right, bottom, notes);
        String result = second.startsWith("ok:") ? second : first;
        // Algunas apps (YouTube) abren primero una pantalla "puente" que se cierra sola y
        // luego la real en OTRA ventana. Por eso cada vuelta se busca otra vez la ventana
        // viva de la app y se revisa que quedó en su lugar.
        int task = ensureBounds(pkg, Integer.parseInt(result.substring(3)), left, top, right, bottom, 8000);
        return "ok:" + task;
    }

    private int ensureBounds(String pkg, int task, int left, int top, int right, int bottom, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        int good = 0;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(500);
            int current = findTask(pkg);
            if (current >= 0 && current != task) {
                task = current;
                good = 0;
            }
            int[] b = taskBounds(task);
            if (b.length == 4 && b[0] == left && b[1] == top && b[2] == right && b[3] == bottom) {
                if (++good >= 3) {
                    return task;
                }
            } else {
                good = 0;
                resizeTask(task, left, top, right, bottom);
            }
        }
        return task;
    }

    @Override
    public int[] taskBounds(int taskId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    args[i] = types[i] == int.class ? (i == 0 ? 100 : -1) : types[i] == boolean.class ? false : null;
                }
                List<?> tasks = (List<?>) m.invoke(atm, args);
                for (Object info : tasks) {
                    if (info.getClass().getField("taskId").getInt(info) != taskId) {
                        continue;
                    }
                    Object config = info.getClass().getField("configuration").get(info);
                    Object wc = config.getClass().getField("windowConfiguration").get(config);
                    Rect r = (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
                    return new int[]{r.left, r.top, r.right, r.bottom};
                }
                break;
            }
        } catch (Throwable t) {
            remember("taskBounds", t);
        }
        // Plan B: leer el "bounds=" de dumpsys.
        String dump = exec("dumpsys activity activities | grep -A3 '#" + taskId + " ' | grep -m1 -oE 'bounds=Rect\\([^)]*\\)'");
        Matcher m = Pattern.compile("(-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)").matcher(dump);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
        }
        return new int[0];
    }

    private String launchOnce(ComponentName cn, int left, int top, int right, int bottom, StringBuilder notes) {
        String pkg = cn.getPackageName();
        int task = -1;
        // 1) Con opciones de arranque: la app nace ya con su tamaño final.
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(new Rect(left, top, right, bottom));
            Method setMode = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            setMode.invoke(options, WINDOWING_MODE_FREEFORM);
            int result = startActivityAsShell(intent, options.toBundle());
            if (result >= 0) {
                task = waitForTask(pkg, 4000);
                if (task < 0) {
                    notes.append("arrancó pero no encontré su ventana; ");
                }
            } else {
                notes.append("startActivity=").append(result).append("; ");
            }
        } catch (Throwable t) {
            notes.append("opciones: ").append(shortError(t)).append("; ");
        }

        // 2) Plan B: el comando "am".
        if (task < 0) {
            String out = exec("am start --windowingMode " + WINDOWING_MODE_FREEFORM
                    + " -n " + cn.flattenToShortString());
            if (out.contains("Error") || out.contains("Exception")) {
                lastError = notes + out;
                return "error:" + lastError;
            }
            task = waitForTask(pkg, 4000);
            if (task < 0) {
                lastError = notes + "no apareció la ventana de " + pkg;
                return "error:" + lastError;
            }
        }
        // Siempre se acomoda después: la TV ignora el tamaño pedido al abrir.
        SystemClock.sleep(300);
        resizeTask(task, left, top, right, bottom);
        if (notes.length() > 0) {
            lastError = notes.toString();
        }
        return "ok:" + task;
    }

    @Override
    public String launchFullscreen(String component, boolean behind) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            return "error:componente inválido";
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (behind) {
            try {
                ActivityOptions options = (ActivityOptions) ActivityOptions.class
                        .getMethod("makeTaskLaunchBehind").invoke(null);
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, 1);
                int r = startActivityAsShell(intent, options.toBundle());
                if (r >= 0) {
                    return "detras";
                }
            } catch (Throwable t) {
                remember("launchBehind", t);
            }
        }
        String out = exec("am start --windowingMode 1 -n " + cn.flattenToShortString());
        return out.contains("Error") || out.contains("Exception") ? "error:" + out : "visible";
    }

    private int startActivityAsShell(Intent intent, Bundle options) throws Exception {
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("startActivityAsUserWithFeature") && m.getParameterTypes().length == 12) {
                Object r = m.invoke(am, null, FakeContext.PACKAGE_NAME, null, intent, null, null, null,
                        0, 0, null, options, USER_CURRENT);
                return (Integer) r;
            }
        }
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("startActivityAsUser") && m.getParameterTypes().length == 11) {
                Object r = m.invoke(am, null, FakeContext.PACKAGE_NAME, intent, null, null, null,
                        0, 0, null, options, USER_CURRENT);
                return (Integer) r;
            }
        }
        throw new NoSuchMethodException("startActivityAsUser");
    }

    private int waitForTask(String pkg, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            int id = findTask(pkg);
            if (id >= 0) {
                return id;
            }
            SystemClock.sleep(250);
        }
        return -1;
    }

    @Override
    public int findTask(String pkg) {
        // 1) Lista de tareas del sistema.
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == int.class) {
                        args[i] = i == 0 ? 100 : -1; // maxNum, displayId = todas
                    } else if (types[i] == boolean.class) {
                        args[i] = false;
                    } else {
                        args[i] = null;
                    }
                }
                List<?> tasks = (List<?>) m.invoke(atm, args);
                if (tasks == null) {
                    break;
                }
                for (Object info : tasks) {
                    if (taskBelongsTo(info, pkg)) {
                        return info.getClass().getField("taskId").getInt(info);
                    }
                }
                break;
            }
        } catch (Throwable t) {
            remember("getTasks", t);
        }
        // 2) Plan B: leer el resumen de dumpsys.
        String dump = exec("dumpsys activity activities | grep -E '\\* Task\\{'");
        Matcher m = Pattern.compile("#(\\d+) type=standard A=\\d+:" + Pattern.quote(pkg)).matcher(dump);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static boolean taskBelongsTo(Object info, String pkg) {
        for (String field : new String[]{"baseActivity", "topActivity", "realActivity", "origActivity"}) {
            try {
                Object value = info.getClass().getField(field).get(info);
                if (value instanceof ComponentName && pkg.equals(((ComponentName) value).getPackageName())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent base = (Intent) info.getClass().getField("baseIntent").get(info);
            if (base != null && base.getComponent() != null
                    && pkg.equals(base.getComponent().getPackageName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public boolean focusTask(int taskId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            atm.getClass().getMethod("setFocusedTask", int.class).invoke(atm, taskId);
            return true;
        } catch (Throwable t) {
            remember("setFocusedTask", t);
        }
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            for (Method m : atm.getClass().getMethods()) {
                if (m.getName().equals("moveTaskToFront")) {
                    // Firma: (caller, callingPackage, taskId, flags, options).
                    Class<?>[] types = m.getParameterTypes();
                    Object[] args = new Object[types.length];
                    boolean taskIdSet = false;
                    for (int i = 0; i < types.length; i++) {
                        if (types[i] == int.class) {
                            args[i] = taskIdSet ? 0 : taskId;
                            taskIdSet = true;
                        } else if (types[i] == String.class) {
                            args[i] = FakeContext.PACKAGE_NAME;
                        } else {
                            args[i] = null;
                        }
                    }
                    m.invoke(atm, args);
                    return true;
                }
            }
        } catch (Throwable t) {
            remember("moveTaskToFront", t);
        }
        return false;
    }

    @Override
    public boolean resizeTask(int taskId, int left, int top, int right, int bottom) {
        String out = exec("am task resize " + taskId + " " + left + " " + top + " " + right + " " + bottom);
        if (out.isEmpty()) {
            return true;
        }
        lastError = "resize: " + out;
        return !(out.contains("Error") || out.contains("Exception"));
    }

    // ---------------------------------------------------------------- audio

    private int uidOf(String pkg) {
        Integer cached = uidCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        int uid = -1;
        try {
            ApplicationInfo info = ctx().getPackageManager().getApplicationInfo(pkg, 0);
            uid = info.uid;
        } catch (Throwable t) {
            Matcher m = Pattern.compile("uid:(\\d+)").matcher(exec("cmd package list packages -U " + pkg));
            if (m.find()) {
                uid = Integer.parseInt(m.group(1));
            }
        }
        if (uid >= 0) {
            uidCache.put(pkg, uid);
        }
        return uid;
    }

    private List<AudioPlaybackConfiguration> players() {
        AudioManager audio = (AudioManager) ctx().getSystemService(Context.AUDIO_SERVICE);
        List<AudioPlaybackConfiguration> list = audio.getActivePlaybackConfigurations();
        return list == null ? new ArrayList<>() : list;
    }

    private static int clientUid(AudioPlaybackConfiguration cfg) {
        try {
            return (Integer) AudioPlaybackConfiguration.class.getMethod("getClientUid").invoke(cfg);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int playerState(AudioPlaybackConfiguration cfg) {
        try {
            return (Integer) AudioPlaybackConfiguration.class.getMethod("getPlayerState").invoke(cfg);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object proxy(AudioPlaybackConfiguration cfg) {
        try {
            return AudioPlaybackConfiguration.class.getMethod("getPlayerProxy").invoke(cfg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean callProxy(Object proxy, String method, Object... args) {
        if (proxy == null) {
            return false;
        }
        try {
            if (args.length == 0) {
                proxy.getClass().getMethod(method).invoke(proxy);
            } else {
                proxy.getClass().getMethod(method, float.class).invoke(proxy, args);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String setAppVolume(String pkg, float volume) {
        int uid = uidOf(pkg);
        if (uid < 0) {
            return "error:sin uid para " + pkg;
        }
        float v = Math.max(0f, Math.min(1f, volume));
        if (v >= 0.999f) {
            volumes.remove(uid);
        } else {
            volumes.put(uid, v);
        }
        int applied = 0;
        int seen = 0;
        try {
            for (AudioPlaybackConfiguration cfg : players()) {
                if (clientUid(cfg) != uid) {
                    continue;
                }
                seen++;
                if (callProxy(proxy(cfg), "setVolume", v)) {
                    applied++;
                }
            }
        } catch (Throwable t) {
            remember("setAppVolume", t);
            return "error:" + shortError(t);
        }
        return "ok:" + applied + "/" + seen;
    }

    private void reapplyVolumes() {
        if (volumes.isEmpty()) {
            return;
        }
        try {
            for (AudioPlaybackConfiguration cfg : players()) {
                Float v = volumes.get(clientUid(cfg));
                if (v != null) {
                    callProxy(proxy(cfg), "setVolume", v);
                }
            }
        } catch (Throwable t) {
            remember("reapplyVolumes", t);
        }
    }

    @Override
    public String media(String pkg, String action) {
        // 1) Sesión multimedia de la app: pausa de verdad el video.
        try {
            for (MediaController c : sessions()) {
                if (!pkg.equals(c.getPackageName())) {
                    continue;
                }
                PlaybackState state = c.getPlaybackState();
                boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                boolean play = action.equals("play") || (action.equals("toggle") && !playing);
                if (play) {
                    c.getTransportControls().play();
                } else {
                    c.getTransportControls().pause();
                }
                return "sesion:" + (play ? "play" : "pause");
            }
        } catch (Throwable t) {
            remember("media/sesion", t);
        }
        // 2) Plan B: pausar sus reproductores de audio directamente.
        try {
            int uid = uidOf(pkg);
            List<Object> proxies = new ArrayList<>();
            boolean anyStarted = false;
            for (AudioPlaybackConfiguration cfg : players()) {
                if (clientUid(cfg) != uid) {
                    continue;
                }
                Object p = proxy(cfg);
                if (p != null) {
                    proxies.add(p);
                    if (playerState(cfg) == PLAYER_STATE_STARTED) {
                        anyStarted = true;
                    }
                }
            }
            if (!proxies.isEmpty()) {
                boolean play = action.equals("play") || (action.equals("toggle") && !anyStarted);
                for (Object p : proxies) {
                    callProxy(p, play ? "start" : "pause");
                }
                return "audio:" + (play ? "play" : "pause");
            }
        } catch (Throwable t) {
            remember("media/audio", t);
        }
        return "nada";
    }

    @Override
    public String audioInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AudioPlaybackConfiguration> list = players();
            sb.append("Reproductores activos: ").append(list.size()).append('\n');
            for (AudioPlaybackConfiguration cfg : list) {
                int uid = clientUid(cfg);
                String[] pkgs = null;
                try {
                    pkgs = ctx().getPackageManager().getPackagesForUid(uid);
                } catch (Throwable ignored) {
                }
                sb.append("· uid ").append(uid)
                        .append(pkgs != null && pkgs.length > 0 ? " " + pkgs[0] : "")
                        .append(" estado=").append(playerState(cfg))
                        .append(proxy(cfg) != null ? " control=sí" : " control=no")
                        .append('\n');
            }
            sb.append("Volúmenes guardados: ").append(volumes).append('\n');
        } catch (Throwable t) {
            sb.append("Error: ").append(shortError(t));
        }
        try {
            sb.append("Sesiones multimedia: ");
            for (MediaController c : sessions()) {
                PlaybackState st = c.getPlaybackState();
                sb.append(c.getPackageName()).append('(').append(st == null ? "?" : st.getState()).append(") ");
            }
        } catch (Throwable t) {
            sb.append("Sesiones multimedia: no disponibles (").append(shortError(t)).append(')');
        }
        return sb.toString();
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
        if (action == KeyEvent.ACTION_UP) {
            exec("input keyevent " + keyCode);
        }
        return false;
    }

    // ---------------------------------------------------------------- otros

    @Override
    public String lastError() {
        return lastError;
    }

    /**
     * Sesiones multimedia activas. Se piden directo al servicio del sistema
     * (MediaSessionManager falla con nuestro contexto falso).
     */
    private List<MediaController> sessions() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "media_session");
        Object msm = Class.forName("android.media.session.ISessionManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object result = null;
        for (Method m : msm.getClass().getMethods()) {
            if (m.getName().equals("getSessions") && m.getParameterTypes().length == 2) {
                result = m.invoke(msm, null, 0);
                break;
            }
        }
        List<?> tokens;
        if (result instanceof List) {
            tokens = (List<?>) result;
        } else if (result != null) {
            tokens = (List<?>) result.getClass().getMethod("getList").invoke(result);
        } else {
            tokens = new ArrayList<>();
        }
        List<MediaController> list = new ArrayList<>();
        for (Object t : tokens) {
            if (t instanceof MediaSession.Token) {
                list.add(new MediaController(ctx(), (MediaSession.Token) t));
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- audio compartido

    private AudioPolicy sharePolicy;

    // ---------------------------------------------------------------- eventos para el reporte

    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    private void event(String text) {
        Log.i(TAG, text);
        synchronized (events) {
            events.addLast(clock.format(new java.util.Date()) + "  " + text);
            while (events.size() > 300) {
                events.removeFirst();
            }
        }
    }

    @Override
    public String serviceEvents() {
        StringBuilder sb = new StringBuilder();
        synchronized (events) {
            for (String e : events) {
                sb.append(e).append('\n');
            }
        }
        return sb.toString();
    }

    private Method focusResultMethod;

    /**
     * Contesta a una app que pidió el audio. En Android la función está en AudioManager
     * (setFocusRequestResult(info, resultado, política)); en algunas versiones en AudioPolicy.
     */
    private String answerFocus(AudioFocusInfo afi, int result) {
        AudioPolicy p = sharePolicy;
        if (p == null) {
            return "sin política";
        }
        AudioManager audio = (AudioManager) ctx().getSystemService(Context.AUDIO_SERVICE);
        try {
            if (focusResultMethod == null) {
                for (Method m : AudioManager.class.getMethods()) {
                    if (m.getName().equals("setFocusRequestResult") && m.getParameterTypes().length == 3) {
                        focusResultMethod = m;
                        break;
                    }
                }
            }
            if (focusResultMethod != null) {
                focusResultMethod.invoke(audio, afi, result, p);
                return "ok (AudioManager)";
            }
        } catch (Throwable t) {
            remember("AudioManager.setFocusRequestResult", t);
        }
        try {
            for (Method m : AudioPolicy.class.getMethods()) {
                if (m.getName().equals("setFocusRequestResult") && m.getParameterTypes().length == 2) {
                    m.invoke(p, afi, result);
                    return "ok (AudioPolicy)";
                }
            }
        } catch (Throwable t) {
            remember("AudioPolicy.setFocusRequestResult", t);
        }
        return "error: " + lastError;
    }

    private static String describe(AudioFocusInfo afi) {
        try {
            return afi.getPackageName() + " (uid " + afi.getClientUid() + ", pide " + afi.getGainRequest() + ")";
        } catch (Throwable t) {
            return String.valueOf(afi);
        }
    }

    /** Da el audio a todas las apps sin avisarle a ninguna que lo perdió. */
    private final class ShareListener extends AudioPolicy.AudioPolicyFocusListener {
        @Override
        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {
            String r = answerFocus(afi, AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            event("Audio pedido por " + describe(afi) + " → concedido: " + r);
        }

        @Override
        public void onAudioFocusAbandon(AudioFocusInfo afi) {
            event("Audio soltado por " + describe(afi));
        }
    }

    @Override
    public synchronized String setShareAudio(boolean on) {
        AudioManager audio = (AudioManager) ctx().getSystemService(Context.AUDIO_SERVICE);
        if (!on) {
            if (sharePolicy == null) {
                return "ok";
            }
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", AudioPolicy.class).invoke(audio, sharePolicy);
            } catch (Throwable t) {
                try {
                    AudioManager.class.getMethod("unregisterAudioPolicyAsync", AudioPolicy.class).invoke(audio, sharePolicy);
                } catch (Throwable t2) {
                    remember("unregisterAudioPolicy", t2);
                }
            }
            sharePolicy = null;
            event("Audio compartido: apagado");
            return "ok";
        }
        if (sharePolicy != null) {
            return "ok";
        }
        try {
            AudioPolicy.Builder builder = new AudioPolicy.Builder(ctx());
            builder.setAudioPolicyFocusListener(new ShareListener());
            builder.setIsAudioFocusPolicy(true);
            AudioPolicy policy = builder.build();
            sharePolicy = policy;
            int r = (Integer) AudioManager.class.getMethod("registerAudioPolicy", AudioPolicy.class)
                    .invoke(audio, policy);
            if (r != 0) {
                sharePolicy = null;
                lastError = "registerAudioPolicy=" + r;
                event("Audio compartido: no se pudo registrar (" + r + ")");
                return "error:" + lastError;
            }
            event("Audio compartido: encendido");
            return "ok";
        } catch (Throwable t) {
            sharePolicy = null;
            remember("setShareAudio", t);
            return "error:" + shortError(t);
        }
    }

    @Override
    public void destroy() {
        try {
            setShareAudio(false);
        } catch (Throwable ignored) {
        }
        // Antes de salir, todas las apps vuelven a su volumen normal.
        try {
            volumes.replaceAll((uid, v) -> 1f);
            reapplyVolumes();
        } catch (Throwable ignored) {
        }
        ticker.shutdownNow();
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
