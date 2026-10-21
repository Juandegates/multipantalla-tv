package com.juandegates.multipantalla;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;

import com.juandegates.multipantalla.priv.MultiService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Coordina todo: la conexión con Shizuku, las ventanas abiertas, el audio
 * de cada una y la ventana que tiene el control. Lo usan la pantalla
 * principal y el servicio de teclas.
 */
public final class Director {

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int SHIZUKU_REQUEST = 7;

    public static final int DEFAULT_KEY_NEXT = KeyEvent.KEYCODE_CHANNEL_UP;
    public static final int DEFAULT_KEY_PREV = KeyEvent.KEYCODE_CHANNEL_DOWN;
    public static final int DEFAULT_KEY_MENU = KeyEvent.KEYCODE_INFO;

    public enum Freeform { UNKNOWN, OFF, NEEDS_REBOOT, ON }

    public interface Listener {
        void onDirectorChanged();
    }

    /** Un cuadro del diseño y la app que tiene. */
    public static final class Slot {
        public String component;
        public String pkg;
        public String name;
        public int taskId = -1;
        public int volume = 100;
        public boolean muted;
        public Rect bounds = new Rect();

        public boolean hasApp() {
            return component != null;
        }

        public boolean running() {
            return taskId >= 0;
        }
    }

    private static Director instance;

    public static synchronized Director get(Context context) {
        if (instance == null) {
            instance = new Director(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    private volatile IMultiService service;
    private boolean binding;

    public final Slot[] slots = new Slot[Layouts.MAX_SLOTS];
    public int layout;
    public volatile boolean sessionActive;
    public int active = -1;
    public int fullscreenSlot = -1;
    public boolean noPause;
    public int keyNext;
    public int keyPrev;
    public int keyMenu;
    public String message = "";
    public Freeform freeform = Freeform.UNKNOWN;

    private int screenW = 1920;
    private int screenH = 1080;

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, MultiService.class.getName()))
            .daemon(false)
            .processNameSuffix("svc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private Director(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("multipantalla", Context.MODE_PRIVATE);
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
            slots[i].component = prefs.getString("slot" + i, null);
            slots[i].name = prefs.getString("slotName" + i, null);
            if (slots[i].component != null) {
                ComponentName cn = ComponentName.unflattenFromString(slots[i].component);
                slots[i].pkg = cn == null ? null : cn.getPackageName();
                if (cn == null) {
                    slots[i].component = null;
                }
            }
        }
        layout = Layouts.clamp(prefs.getInt("layout", 4));
        keyNext = prefs.getInt("key_next", DEFAULT_KEY_NEXT);
        keyPrev = prefs.getInt("key_prev", DEFAULT_KEY_PREV);
        keyMenu = prefs.getInt("key_menu", DEFAULT_KEY_MENU);
        readScreenSize();

        Shizuku.addBinderReceivedListenerSticky(() -> main.post(this::tryBind));
        Shizuku.addBinderDeadListener(() -> main.post(() -> {
            service = null;
            binding = false;
            notifyChanged();
        }));
        Shizuku.addRequestPermissionResultListener((code, result) -> main.post(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) {
                tryBind();
            }
            notifyChanged();
        }));
    }

    private void readScreenSize() {
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Point p = new Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) {
                screenW = Math.max(p.x, p.y);
                screenH = Math.min(p.x, p.y);
            }
        } catch (Throwable ignored) {
        }
    }

    public int screenWidth() {
        return screenW;
    }

    public int screenHeight() {
        return screenH;
    }

    // ------------------------------------------------------------ oyentes

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::notifyChanged);
            return;
        }
        for (Listener l : new ArrayList<>(listeners)) {
            l.onDirectorChanged();
        }
    }

    private void say(String text) {
        main.post(() -> {
            message = text;
            notifyChanged();
        });
    }

    // ------------------------------------------------------------ Shizuku

    public boolean shizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean shizukuPermission() {
        try {
            return shizukuRunning() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST);
        } catch (Throwable t) {
            say("No se pudo pedir el permiso: " + t.getMessage());
        }
    }

    public boolean connected() {
        return service != null;
    }

    public void tryBind() {
        if (service != null || binding || !shizukuPermission()) {
            notifyChanged();
            return;
        }
        try {
            binding = true;
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable t) {
            binding = false;
            say("Error al conectar con Shizuku: " + t.getMessage());
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            if (binder == null || !binder.pingBinder()) {
                say("El servicio de Shizuku no respondió.");
                return;
            }
            service = IMultiService.Stub.asInterface(binder);
            refreshFreeform();
            notifyChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            notifyChanged();
        }
    };

    private String exec(String cmd) {
        IMultiService s = service;
        if (s == null) {
            return "";
        }
        try {
            return s.exec(cmd);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ revisión

    private int bootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void refreshFreeform() {
        if (service == null) {
            freeform = Freeform.UNKNOWN;
            notifyChanged();
            return;
        }
        work.execute(() -> {
            String out = exec("settings get global enable_freeform_support; settings get global force_resizable_activities");
            String[] lines = out.split("\\s+");
            boolean on = lines.length >= 2 && lines[0].trim().equals("1") && lines[1].trim().equals("1");
            Freeform result;
            if (!on) {
                result = Freeform.OFF;
            } else {
                int saved = prefs.getInt("freeform_boot", -1);
                result = (saved >= 0 && bootCount() <= saved) ? Freeform.NEEDS_REBOOT : Freeform.ON;
            }
            main.post(() -> {
                freeform = result;
                notifyChanged();
            });
        });
    }

    public void enableFreeform() {
        work.execute(() -> {
            exec("settings put global enable_freeform_support 1; settings put global force_resizable_activities 1");
            prefs.edit().putInt("freeform_boot", bootCount()).apply();
            say("Ventanas libres activadas. Falta reiniciar la TV.");
            refreshFreeform();
        });
    }

    public void rebootTv() {
        work.execute(() -> exec("svc power reboot || reboot"));
    }

    public static String keyServiceComponent() {
        return BuildConfig.APPLICATION_ID + "/" + TeclasService.class.getName();
    }

    public boolean keyServiceEnabled() {
        try {
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return list != null && list.contains(BuildConfig.APPLICATION_ID + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    public void enableKeyService() {
        final String comp = keyServiceComponent();
        work.execute(() -> {
            exec("v=$(settings get secure enabled_accessibility_services); "
                    + "case \"$v\" in *" + BuildConfig.APPLICATION_ID + "*) ;; "
                    + "null|'') settings put secure enabled_accessibility_services " + comp + " ;; "
                    + "*) settings put secure enabled_accessibility_services \"$v:" + comp + "\" ;; esac; "
                    + "settings put secure accessibility_enabled 1");
            SystemClock.sleep(800);
            say(keyServiceEnabled() ? "Servicio de teclas activado." : "No se pudo activar el servicio de teclas.");
        });
    }

    public boolean ready() {
        return connected() && freeform == Freeform.ON;
    }

    // ------------------------------------------------------------ configuración

    public void setLayout(int newLayout) {
        layout = Layouts.clamp(newLayout);
        prefs.edit().putInt("layout", layout).apply();
        if (sessionActive) {
            applyLayoutLive();
        }
        notifyChanged();
    }

    public void setSlotApp(int i, ComponentName component, String name) {
        Slot s = slots[i];
        s.component = component == null ? null : component.flattenToString();
        s.pkg = component == null ? null : component.getPackageName();
        s.name = name;
        prefs.edit().putString("slot" + i, s.component).putString("slotName" + i, name).apply();
        notifyChanged();
    }

    public void setKeys(int next, int prev, int menu) {
        keyNext = next;
        keyPrev = prev;
        keyMenu = menu;
        prefs.edit().putInt("key_next", next).putInt("key_prev", prev).putInt("key_menu", menu).apply();
        notifyChanged();
    }

    public Rect boundsOf(int slot) {
        return Layouts.bounds(layout, slot, screenW, screenH);
    }

    // ------------------------------------------------------------ sesión

    /** Abre todas las apps del diseño, cada una en su cuadro. */
    public void startSession() {
        if (!ready()) {
            say("Primero completa la revisión.");
            return;
        }
        final int count = Layouts.slots(layout);
        work.execute(() -> {
            // Cerrar primero: así cada app nace como ventana libre y no en pantalla completa.
            for (int i = 0; i < slots.length; i++) {
                Slot s = slots[i];
                s.taskId = -1;
                s.volume = 100;
                s.muted = false;
                if (s.pkg != null) {
                    exec("am force-stop " + s.pkg + "; cmd appops set " + s.pkg + " PLAY_AUDIO default; "
                            + "cmd appops set " + s.pkg + " TAKE_AUDIO_FOCUS default");
                }
            }
            SystemClock.sleep(300);
            StringBuilder problems = new StringBuilder();
            int first = -1;
            for (int i = 0; i < count; i++) {
                Slot s = slots[i];
                s.bounds = boundsOf(i);
                if (!s.hasApp()) {
                    continue;
                }
                say("Abriendo " + s.name + " en el cuadro " + (i + 1) + "…");
                String r;
                try {
                    r = service.launchFreeform(s.component, s.bounds.left, s.bounds.top,
                            s.bounds.right, s.bounds.bottom);
                } catch (Exception e) {
                    r = "error:" + e.getMessage();
                }
                if (r != null && r.startsWith("ok:")) {
                    s.taskId = Integer.parseInt(r.substring(3));
                    if (first < 0) {
                        first = i;
                    }
                } else {
                    problems.append(s.name).append(": ").append(r).append('\n');
                }
                SystemClock.sleep(500);
            }
            final int firstSlot = first;
            final String errors = problems.toString();
            main.post(() -> {
                sessionActive = firstSlot >= 0;
                fullscreenSlot = -1;
                noPause = false;
                message = errors.isEmpty() ? "Listo. " + keyName(keyNext) + "/" + keyName(keyPrev)
                        + " cambia de ventana · " + keyName(keyMenu) + " abre el menú."
                        : "Algunas apps no abrieron:\n" + errors;
                notifyChanged();
                if (firstSlot >= 0) {
                    focusSlot(firstSlot);
                }
            });
        });
    }

    /** Cierra las apps de la sesión y deja su audio como estaba. */
    public void stopSession() {
        final List<String> pkgs = new ArrayList<>();
        for (Slot s : slots) {
            if (s.pkg != null && s.running()) {
                pkgs.add(s.pkg);
            }
            s.taskId = -1;
        }
        sessionActive = false;
        active = -1;
        fullscreenSlot = -1;
        notifyChanged();
        work.execute(() -> {
            for (String pkg : pkgs) {
                restoreAudio(pkg);
                exec("am force-stop " + pkg);
            }
        });
    }

    private void restoreAudio(String pkg) {
        IMultiService s = service;
        if (s == null) {
            return;
        }
        try {
            s.setAppVolume(pkg, 1f);
        } catch (Exception ignored) {
        }
        exec("cmd appops set " + pkg + " PLAY_AUDIO default; cmd appops set " + pkg + " TAKE_AUDIO_FOCUS default");
    }

    /** Revisa qué ventanas siguen abiertas (el usuario pudo cerrar alguna con Atrás). */
    public void refreshTasks() {
        if (!sessionActive || service == null) {
            return;
        }
        work.execute(() -> {
            boolean changed = false;
            boolean any = false;
            for (Slot s : slots) {
                if (!s.running()) {
                    continue;
                }
                int id;
                try {
                    id = service.findTask(s.pkg);
                } catch (Exception e) {
                    id = s.taskId;
                }
                if (id != s.taskId) {
                    s.taskId = id;
                    changed = true;
                }
                any |= id >= 0;
            }
            final boolean stillActive = any;
            if (changed || !stillActive) {
                main.post(() -> {
                    if (!stillActive) {
                        sessionActive = false;
                        active = -1;
                    } else if (active >= 0 && !slots[active].running()) {
                        active = nextRunning(active, 1);
                    }
                    notifyChanged();
                });
            }
        });
    }

    public int runningCount() {
        int n = 0;
        for (Slot s : slots) {
            if (s.running()) {
                n++;
            }
        }
        return n;
    }

    private int nextRunning(int from, int step) {
        for (int k = 1; k <= slots.length; k++) {
            int i = ((from + step * k) % slots.length + slots.length) % slots.length;
            if (slots[i].running()) {
                return i;
            }
        }
        return -1;
    }

    public void focusNext() {
        int i = nextRunning(active < 0 ? -1 : active, 1);
        if (i >= 0) {
            focusSlot(i);
        }
    }

    public void focusPrev() {
        int i = nextRunning(active < 0 ? 0 : active, -1);
        if (i >= 0) {
            focusSlot(i);
        }
    }

    public void focusSlot(final int i) {
        final Slot s = slots[i];
        if (!s.running()) {
            return;
        }
        active = i;
        notifyChanged();
        work.execute(() -> {
            boolean ok = false;
            try {
                ok = service.focusTask(s.taskId);
            } catch (Exception ignored) {
            }
            if (!ok) {
                // Plan B: volver a "abrir" la app trae su ventana al frente sin cambiar su tamaño.
                exec("am start -n " + ComponentName.unflattenFromString(s.component).flattenToShortString());
            }
        });
    }

    /** Vuelve a poner todas las ventanas encima (por ejemplo, al cerrar el panel). */
    public void raiseAll() {
        if (!sessionActive) {
            return;
        }
        int keep = active;
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i].running() && i != keep) {
                focusSlot(i);
            }
        }
        if (keep >= 0) {
            focusSlot(keep);
        }
    }

    // ------------------------------------------------------------ audio y video

    public void changeVolume(int i, int delta) {
        final Slot s = slots[i];
        if (!s.running()) {
            return;
        }
        s.volume = Math.max(0, Math.min(100, s.volume + delta));
        notifyChanged();
        final int v = s.volume;
        work.execute(() -> {
            String r;
            try {
                r = service.setAppVolume(s.pkg, v / 100f);
            } catch (Exception e) {
                r = "error:" + e.getMessage();
            }
            if (r.startsWith("ok:0/") && !r.equals("ok:0/0")) {
                say("La TV no dejó cambiar el volumen de " + s.name + ". Revisa Diagnóstico.");
            } else if (r.startsWith("error")) {
                say("Volumen: " + r);
            }
        });
    }

    public void toggleMute(int i) {
        final Slot s = slots[i];
        if (!s.running()) {
            return;
        }
        s.muted = !s.muted;
        notifyChanged();
        final boolean mute = s.muted;
        work.execute(() -> exec("cmd appops set " + s.pkg + " PLAY_AUDIO " + (mute ? "deny" : "allow")));
    }

    /** Que solo suene esta ventana: silencia las demás. */
    public void soloAudio(int i) {
        for (int k = 0; k < slots.length; k++) {
            final Slot s = slots[k];
            if (!s.running()) {
                continue;
            }
            final boolean mute = k != i;
            s.muted = mute;
            work.execute(() -> exec("cmd appops set " + s.pkg + " PLAY_AUDIO " + (mute ? "deny" : "allow")));
        }
        notifyChanged();
    }

    public void togglePlay(final int i) {
        final Slot s = slots[i];
        if (!s.running()) {
            return;
        }
        work.execute(() -> {
            String r;
            try {
                r = service.media(s.pkg, "toggle");
            } catch (Exception e) {
                r = "nada";
            }
            if ("nada".equals(r)) {
                // Plan B: darle el control y mandarle la tecla de pausa.
                try {
                    service.focusTask(s.taskId);
                    SystemClock.sleep(150);
                    service.injectKey(0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, 0);
                    service.injectKey(0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, 0);
                    main.post(() -> active = i);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Evita (experimental) que una app pause a las demás al empezar a sonar. */
    public void toggleNoPause() {
        noPause = !noPause;
        final boolean on = noPause;
        for (final Slot s : slots) {
            if (s.running()) {
                work.execute(() -> exec("cmd appops set " + s.pkg + " TAKE_AUDIO_FOCUS " + (on ? "ignore" : "default")));
            }
        }
        say(on ? "Las apps ya no pedirán el audio solo para ellas. Si alguna deja de reproducir, desactívalo."
                : "Audio normal: una app puede pausar a otra al empezar.");
    }

    public void toggleFullscreen(final int i) {
        final Slot s = slots[i];
        if (!s.running()) {
            return;
        }
        final boolean goFull = fullscreenSlot != i;
        final Rect target = goFull ? new Rect(0, 0, screenW, screenH) : boundsOf(i);
        final int previous = fullscreenSlot;
        fullscreenSlot = goFull ? i : -1;
        notifyChanged();
        work.execute(() -> {
            try {
                if (previous >= 0 && previous != i && slots[previous].running()) {
                    Rect back = boundsOf(previous);
                    service.resizeTask(slots[previous].taskId, back.left, back.top, back.right, back.bottom);
                }
                service.resizeTask(s.taskId, target.left, target.top, target.right, target.bottom);
            } catch (Exception ignored) {
            }
            main.post(() -> focusSlot(i));
        });
    }

    public void cycleLayout() {
        int running = runningCount();
        for (int k = 1; k <= Layouts.count(); k++) {
            int candidate = (layout + k) % Layouts.count();
            if (Layouts.slots(candidate) >= running) {
                setLayout(candidate);
                say("Diseño: " + Layouts.NAMES[candidate]);
                return;
            }
        }
    }

    /** Reacomoda las ventanas abiertas al diseño actual. */
    private void applyLayoutLive() {
        fullscreenSlot = -1;
        final int count = Layouts.slots(layout);
        work.execute(() -> {
            // Las ventanas abiertas se acomodan en orden en los cuadros del diseño nuevo.
            int target = 0;
            for (Slot s : slots) {
                if (!s.running()) {
                    continue;
                }
                if (target >= count) {
                    restoreAudio(s.pkg);
                    exec("am force-stop " + s.pkg);
                    s.taskId = -1;
                    continue;
                }
                Rect r = Layouts.bounds(layout, target, screenW, screenH);
                s.bounds = r;
                try {
                    service.resizeTask(s.taskId, r.left, r.top, r.right, r.bottom);
                } catch (Exception ignored) {
                }
                target++;
            }
            notifyChanged();
        });
    }

    /** Abre la pantalla principal de MultiPantalla encima de todo. */
    public void openPanel() {
        work.execute(() -> exec("am start -n " + BuildConfig.APPLICATION_ID + "/.MainActivity"));
    }

    // ------------------------------------------------------------ diagnóstico

    public void diagnostics(final java.util.function.Consumer<String> done) {
        final IMultiService s = service;
        if (s == null) {
            done.accept("Sin conexión con Shizuku.\nShizuku activo: " + shizukuRunning()
                    + "\nPermiso: " + shizukuPermission());
            return;
        }
        work.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                sb.append("Versión: ").append(BuildConfig.VERSION_NAME).append('\n');
                sb.append("uid del servicio: ").append(s.exec("id -u")).append('\n');
                sb.append("Pantalla: ").append(screenW).append('x').append(screenH).append('\n');
                sb.append("Ventanas libres: ").append(freeform)
                        .append(" (arranques: ").append(bootCount())
                        .append(", guardado: ").append(prefs.getInt("freeform_boot", -1)).append(")\n");
                sb.append("Servicio de teclas: ").append(keyServiceEnabled())
                        .append(TeclasService.isConnected() ? " (conectado)" : " (no conectado)").append('\n');
                for (int i = 0; i < slots.length; i++) {
                    Slot sl = slots[i];
                    sb.append("Cuadro ").append(i + 1).append(": ")
                            .append(sl.name == null ? "vacío" : sl.name)
                            .append(" · ventana ").append(sl.taskId)
                            .append(" · vol ").append(sl.volume)
                            .append(sl.muted ? " · silencio" : "").append('\n');
                }
                sb.append("Último error: ").append(s.lastError()).append("\n\n");
                sb.append(s.audioInfo()).append("\n\n");
                sb.append(s.exec("dumpsys activity activities | grep -E '\\* Task\\{' | head -12"));
            } catch (Exception e) {
                sb.append("Error: ").append(e.getMessage());
            }
            final String text = sb.toString();
            main.post(() -> done.accept(text));
        });
    }

    // ------------------------------------------------------------ utilidades

    public static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return "CH ▲";
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return "CH ▼";
            case KeyEvent.KEYCODE_INFO:
                return "ⓘ Info";
            case KeyEvent.KEYCODE_MENU:
                return "≡ Menú";
            case KeyEvent.KEYCODE_SETTINGS:
                return "⚙ Ajustes";
            case KeyEvent.KEYCODE_TV:
                return "TV";
            case KeyEvent.KEYCODE_TV_INPUT:
                return "Entrada";
            case KeyEvent.KEYCODE_PROG_RED:
                return "Rojo";
            case KeyEvent.KEYCODE_PROG_GREEN:
                return "Verde";
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return "Amarillo";
            case KeyEvent.KEYCODE_PROG_BLUE:
                return "Azul";
            default:
                String raw = KeyEvent.keyCodeToString(code);
                return raw.startsWith("KEYCODE_") ? raw.substring(8) : raw;
        }
    }
}
