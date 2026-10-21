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
    /** Mientras se abren las apps (para no volver al panel antes de tiempo). */
    public volatile boolean starting;
    public int active = -1;
    public int fullscreenSlot = -1;
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
        // Clave nueva: los diseños cambiaron de orden en la 0.5.
        layout = Layouts.clamp(prefs.getInt("layout_v2", 0));
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

    // ------------------------------------------------------------ registro para el reporte

    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    /** Anota un evento para el reporte (se guardan los últimos 600). */
    public void log(String text) {
        String line = clock.format(new java.util.Date()) + "  " + text;
        android.util.Log.i("MP-Director", text);
        synchronized (events) {
            events.addLast(line);
            while (events.size() > 600) {
                events.removeFirst();
            }
        }
    }

    public static final String REPORT_NAME = "MultiPantalla-reporte.txt";

    /**
     * Junta todo en un archivo de texto en la carpeta Descargas de la TV:
     * eventos de la app, estado, ventanas, audio, teclas y registro del sistema.
     */
    public void makeReport(final java.util.function.Consumer<String> done) {
        log("Generando reporte");
        diagnostics(diag -> work.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("===== MultiPantalla ").append(BuildConfig.VERSION_NAME).append(" · ")
                    .append(new java.util.Date()).append(" =====\n\n");
            sb.append("===== ESTADO =====\n").append(diag).append("\n\n");
            sb.append("Sesión activa: ").append(sessionActive).append(" · abriendo: ").append(starting)
                    .append(" · activa: ").append(active).append(" · completa: ").append(fullscreenSlot)
                    .append(" · diseño: ").append(Layouts.NAMES[layout]).append('\n');
            sb.append("Teclas: sig ").append(keyName(keyNext)).append(" · ant ").append(keyName(keyPrev))
                    .append(" · menú ").append(keyName(keyMenu)).append("\n\n");
            sb.append("===== EVENTOS DE LA APP =====\n");
            synchronized (events) {
                for (String e : events) {
                    sb.append(e).append('\n');
                }
            }
            if (service != null) {
                try {
                    sb.append("\n===== EVENTOS DEL SERVICIO (audio) =====\n").append(service.serviceEvents());
                } catch (Exception e) {
                    sb.append("(no disponibles: ").append(e.getMessage()).append(")\n");
                }
                sb.append("\n===== ACCESIBILIDAD =====\n")
                        .append(exec("settings get secure enabled_accessibility_services; "
                                + "dumpsys accessibility | grep -iE 'Bound services|Enabled services|multipantalla' | head -10"));
                sb.append("\n\n===== VENTANAS =====\n")
                        .append(exec("dumpsys activity activities | grep -E '\\* Task\\{|bounds=' | head -40"));
                sb.append("\n\n===== AUDIO (foco y reproductores) =====\n")
                        .append(exec("dumpsys audio | grep -A8 'focus stack entries'; "
                                + "dumpsys audio | grep -iE 'requestAudioFocus|abandonAudioFocus|loss|policy' | tail -25; "
                                + "dumpsys audio | grep 'AudioPlaybackConfiguration' | grep -v 'state:idle' | cut -c1-220"));
                sb.append("\n\n===== PERMISOS DE AUDIO =====\n");
                for (Slot s : slots) {
                    if (s.pkg != null) {
                        sb.append(s.pkg).append(": ").append(exec("cmd appops get " + s.pkg
                                + " PLAY_AUDIO | sed 's/;.*//'; cmd appops get " + s.pkg
                                + " TAKE_AUDIO_FOCUS | sed 's/;.*//'").replace('\n', ' ')).append('\n');
                    }
                }
                sb.append("\n===== REGISTRO DEL SISTEMA (últimas líneas relevantes) =====\n")
                        .append(exec("logcat -d -v time -t 8000 | grep -iE 'MP-|multipantalla|shizuku|"
                                + "ActivityTaskManager|WindowManager.*(freeform|resize)|AudioService|MediaFocus|"
                                + "youtube|twitch|AndroidRuntime|FATAL|has died|ANR|Force finishing|codec' "
                                + "| grep -vE 'BasicMediaStatusFactory|AudioMediaPlayerWrapper|CoreBackPreview' | tail -400"));
            } else {
                sb.append("\n(Sin conexión con Shizuku: no se pudo leer el sistema)\n");
            }
            String result;
            try {
                java.io.File dir = context.getExternalFilesDir(null);
                java.io.File file = new java.io.File(dir, REPORT_NAME);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                // Copia en Descargas para sacarlo fácil con adb.
                exec("cp '" + file.getAbsolutePath() + "' /sdcard/Download/" + REPORT_NAME);
                result = "ok";
            } catch (Exception e) {
                result = "error: " + e.getMessage();
            }
            final String r = result;
            main.post(() -> done.accept(r));
        }));
    }

    private void say(String text) {
        log("Aviso: " + text.replace('\n', ' '));
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
            bindStartedAt = SystemClock.uptimeMillis();
            wakeShizukuApp();
            Shizuku.bindUserService(serviceArgs, connection);
            main.removeCallbacks(bindWatchdog);
            main.postDelayed(bindWatchdog, 9000);
        } catch (Throwable t) {
            binding = false;
            say("Error al conectar con Shizuku: " + t.getMessage());
        }
        notifyChanged();
    }

    private long bindStartedAt;
    private android.content.ContentProviderClient shizukuProvider;

    /**
     * Shizuku necesita que su propia app esté viva para entregarnos la conexión
     * ("provider is null" en el registro). Pedir su proveedor la despierta sin abrir nada.
     */
    private void wakeShizukuApp() {
        try {
            if (shizukuProvider == null) {
                shizukuProvider = context.getContentResolver()
                        .acquireUnstableContentProviderClient(SHIZUKU_PACKAGE + ".shizuku");
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean bindingSlow() {
        return binding && SystemClock.uptimeMillis() - bindStartedAt > 8000;
    }

    /** Si no conectó, se abre Shizuku un momento (eso siempre lo despierta) y se vuelve a intentar. */
    private final Runnable bindWatchdog = () -> {
        if (service == null && binding) {
            notifyChanged();
        }
    };

    /** Botón Reintentar: cancela el intento colgado y conecta de nuevo. */
    public void retryBind(boolean openShizuku) {
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (Throwable ignored) {
        }
        binding = false;
        service = null;
        if (shizukuProvider != null) {
            try {
                shizukuProvider.close();
            } catch (Throwable ignored) {
            }
            shizukuProvider = null;
        }
        if (openShizuku) {
            try {
                PackageManager pm = context.getPackageManager();
                android.content.Intent i = pm.getLeanbackLaunchIntentForPackage(SHIZUKU_PACKAGE);
                if (i == null) {
                    i = pm.getLaunchIntentForPackage(SHIZUKU_PACKAGE);
                }
                if (i != null) {
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                    // Volver a MultiPantalla y conectar.
                    main.postDelayed(() -> {
                        android.content.Intent back = new android.content.Intent(context, MainActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        context.startActivity(back);
                        tryBind();
                    }, 2500);
                    notifyChanged();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        tryBind();
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
            log("Conectado con Shizuku (servicio de sistema)");
            main.removeCallbacks(bindWatchdog);
            // Si el servicio de teclas quedó "encendido" pero desconectado, reconectarlo solo.
            main.postDelayed(() -> {
                if (keyServiceEnabled() && !TeclasService.isConnected()) {
                    log("Servicio de teclas encendido pero desconectado: reconectando");
                    enableKeyService();
                }
            }, 1500);
            refreshFreeform();
            repairAudio();
            notifyChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            log("Se perdió la conexión con Shizuku");
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

    /**
     * Activa (o reconecta) el servicio de teclas. Al instalar una versión nueva encima,
     * la TV lo deja "encendido" pero desconectado; por eso primero se quita de la lista
     * y luego se vuelve a poner: así la TV lo conecta de nuevo.
     */
    public void enableKeyService() {
        final String comp = keyServiceComponent();
        log("Servicio de teclas: activar/reconectar");
        work.execute(() -> {
            String out = exec("v=$(settings get secure enabled_accessibility_services); "
                    + "n=$(echo \"$v\" | tr ':' '\\n' | grep -v '^null$' | grep -v " + BuildConfig.APPLICATION_ID + " | paste -sd: -); "
                    + "if [ -n \"$n\" ]; then settings put secure enabled_accessibility_services \"$n\"; "
                    + "else settings put secure enabled_accessibility_services null; fi; sleep 1; "
                    + "if [ -n \"$n\" ]; then settings put secure enabled_accessibility_services \"$n:" + comp + "\"; "
                    + "else settings put secure enabled_accessibility_services " + comp + "; fi; "
                    + "settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services");
            log("Servicios de accesibilidad: " + out);
            for (int i = 0; i < 10 && !TeclasService.isConnected(); i++) {
                SystemClock.sleep(300);
            }
            log("Servicio de teclas conectado: " + TeclasService.isConnected());
            say(TeclasService.isConnected() ? "Servicio de teclas activado."
                    : "La TV no conectó el servicio de teclas. Revisa el reporte.");
        });
    }

    public boolean ready() {
        return connected() && freeform == Freeform.ON;
    }

    // ------------------------------------------------------------ configuración

    public void setLayout(int newLayout) {
        layout = Layouts.clamp(newLayout);
        prefs.edit().putInt("layout_v2", layout).apply();
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
        log("Iniciar sesión · diseño " + Layouts.NAMES[layout] + " · pantalla " + screenW + "x" + screenH);
        starting = true;
        notifyChanged();
        work.execute(() -> {
            // Cerrar primero: así cada app nace como ventana libre y no en pantalla completa.
            for (int i = 0; i < slots.length; i++) {
                Slot s = slots[i];
                s.taskId = -1;
                s.volume = 100;
                s.muted = false;
                if (s.pkg != null) {
                    exec("am force-stop " + s.pkg + "; " + audioAllow(s.pkg));
                }
            }
            SystemClock.sleep(300);
            StringBuilder problems = new StringBuilder();
            // Reparto de audio: así ninguna app pausa a las otras al empezar a sonar.
            String share;
            try {
                share = service.setShareAudio(true);
            } catch (Exception e) {
                share = "error:" + e.getMessage();
            }
            log("Audio compartido: " + share);
            if (!"ok".equals(share)) {
                problems.append("Audio compartido no disponible (").append(share)
                        .append("); las apps podrían pausarse entre sí.\n");
            }
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
                log("Abrir " + s.name + " en cuadro " + (i + 1) + " " + s.bounds.toShortString() + " → " + r);
                if (r != null && r.startsWith("ok:")) {
                    s.taskId = Integer.parseInt(r.substring(3));
                    try {
                        int[] b = service.taskBounds(s.taskId);
                        log("  quedó en " + java.util.Arrays.toString(b));
                    } catch (Exception ignored) {
                    }
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
                starting = false;
                sessionActive = firstSlot >= 0;
                fullscreenSlot = -1;
                message = errors.isEmpty() ? "Listo. " + keyName(keyNext) + "/" + keyName(keyPrev)
                        + " cambia de ventana · " + keyName(keyMenu) + " abre el menú."
                        : "Avisos:\n" + errors;
                notifyChanged();
                if (firstSlot >= 0) {
                    focusSlot(firstSlot);
                }
            });
        });
    }

    /** Cierra las apps de la sesión y deja su audio como estaba. */
    public void stopSession() {
        stopSession(true);
    }

    /**
     * @param backToPanel true si el usuario sigue en MultiPantalla; false si salió
     *                    (Inicio o salir): entonces se cierra todo sin abrir nada encima.
     */
    public void stopSession(final boolean backToPanel) {
        if (!sessionActive && !starting) {
            return;
        }
        log("Cerrar sesión · " + (backToPanel ? "volver al panel" : "salida (Inicio/salir)"));
        final List<String> pkgs = new ArrayList<>();
        final List<String> components = new ArrayList<>();
        for (Slot s : slots) {
            if (s.pkg != null && s.running()) {
                pkgs.add(s.pkg);
                components.add(s.component);
            }
            s.taskId = -1;
        }
        sessionActive = false;
        starting = false;
        active = -1;
        fullscreenSlot = -1;
        notifyChanged();
        work.execute(() -> {
            try {
                IMultiService sv = service;
                if (sv != null) {
                    sv.setShareAudio(false);
                }
            } catch (Exception ignored) {
            }
            for (String pkg : pkgs) {
                restoreAudio(pkg);
            }
            for (String pkg : pkgs) {
                exec("am force-stop " + pkg);
            }
            resetToFullscreen(components, backToPanel);
        });
    }

    /**
     * Android recuerda el tamaño de ventana de cada app (por eso después abrían
     * pequeñas o con un marco raro). Abrirlas un instante en pantalla completa
     * hace que recuerde "pantalla completa".
     */
    private void resetToFullscreen(List<String> components, boolean backToPanel) {
        IMultiService s = service;
        if (s == null) {
            return;
        }
        boolean anyVisible = false;
        for (String component : components) {
            String r;
            try {
                // Se abre por detrás (no se ve) en pantalla completa y se cierra.
                r = s.launchFullscreen(component, true);
            } catch (Exception e) {
                r = "error";
            }
            anyVisible |= "visible".equals(r);
            log("Restablecer pantalla completa " + component + " → " + r);
            SystemClock.sleep(1500);
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null) {
                exec("am force-stop " + cn.getPackageName());
            }
        }
        if (backToPanel) {
            exec("am start -n " + BuildConfig.APPLICATION_ID + "/.MainActivity");
        } else if (anyVisible) {
            // Si alguna se vio, regresar al inicio de la TV.
            exec("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        }
    }

    /** Botón de Ayuda: deja todas las apps elegidas abriendo en pantalla completa. */
    public void resetAllToFullscreen() {
        if (service == null) {
            return;
        }
        final List<String> pkgs = new ArrayList<>();
        final List<String> components = new ArrayList<>();
        for (Slot s : slots) {
            if (s.pkg != null && !pkgs.contains(s.pkg)) {
                pkgs.add(s.pkg);
                components.add(s.component);
            }
        }
        say("Restableciendo pantalla completa…");
        work.execute(() -> {
            for (String pkg : pkgs) {
                exec(audioAllow(pkg));
                exec("am force-stop " + pkg);
            }
            resetToFullscreen(components, true);
            say("Listo: esas apps vuelven a abrir en pantalla completa.");
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
        exec(audioAllow(pkg));
    }

    /**
     * Ojo: en PLAY_AUDIO y TAKE_AUDIO_FOCUS el modo "default" NO es "normal":
     * Android lo trata como negado (la app queda muda y no puede pedir el audio).
     * Por eso siempre se deja en "allow".
     */
    private static String audioAllow(String pkg) {
        return "cmd appops set " + pkg + " PLAY_AUDIO allow; cmd appops set " + pkg + " TAKE_AUDIO_FOCUS allow";
    }

    /** Arregla apps que quedaron con el audio en "default", "ignore" o "deny" (versiones 0.1 y 0.2). */
    public void repairAudio() {
        final List<String> pkgs = new ArrayList<>();
        for (Slot s : slots) {
            if (s.pkg != null && !pkgs.contains(s.pkg) && !(s.running() && s.muted)) {
                pkgs.add(s.pkg);
            }
        }
        if (pkgs.isEmpty()) {
            return;
        }
        work.execute(() -> {
            List<String> fixed = new ArrayList<>();
            for (String pkg : pkgs) {
                String out = exec("cmd appops get " + pkg + " PLAY_AUDIO; cmd appops get " + pkg + " TAKE_AUDIO_FOCUS");
                if (out.contains(": default") || out.contains(": ignore") || out.contains(": deny")) {
                    exec(audioAllow(pkg));
                    fixed.add(pkg);
                }
            }
            if (!fixed.isEmpty()) {
                say("Reparé el audio de: " + String.join(", ", fixed));
            }
        });
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
                    log("Ventana de " + s.name + ": " + s.taskId + " → " + id);
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

    /** Siguiente cuadro del diseño, con app o vacío (para poder elegir app en los vacíos). */
    private int nextSlot(int from, int step) {
        int n = Layouts.slots(layout);
        if (n <= 0) {
            return -1;
        }
        int start = from < 0 ? (step > 0 ? -1 : 0) : from;
        return ((start + step) % n + n) % n;
    }

    public void focusNext() {
        int i = nextSlot(active, 1);
        if (i >= 0) {
            focusSlot(i);
        }
    }

    public void focusPrev() {
        int i = nextSlot(active, -1);
        if (i >= 0) {
            focusSlot(i);
        }
    }

    public void focusSlot(final int i) {
        final Slot s = slots[i];
        if (!s.running()) {
            // Cuadro vacío: solo se marca (con ⓘ se elige qué abrir ahí).
            log("Control a cuadro " + (i + 1) + " (vacío)");
            active = i;
            notifyChanged();
            return;
        }
        log("Control a cuadro " + (i + 1) + " (" + s.name + ", ventana " + s.taskId + ")");
        active = i;
        notifyChanged();
        work.execute(() -> {
            boolean ok = false;
            try {
                ok = service.focusTask(s.taskId);
            } catch (Exception ignored) {
            }
            if (!ok) {
                log("  setFocusedTask falló; plan B am start");
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

    /** Vuelve a poner cada ventana en su cuadro (por si alguna se movió). */
    public void rearrange() {
        if (!sessionActive || service == null) {
            return;
        }
        work.execute(() -> {
            for (int i = 0; i < slots.length; i++) {
                Slot s = slots[i];
                if (!s.running()) {
                    continue;
                }
                Rect r = fullscreenSlot == i ? new Rect(0, 0, screenW, screenH) : boundsOf(i);
                try {
                    service.resizeTask(s.taskId, r.left, r.top, r.right, r.bottom);
                } catch (Exception ignored) {
                }
            }
            say("Ventanas reacomodadas.");
        });
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
        if (active >= count) {
            active = -1;
        }
        work.execute(() -> {
            // Cada ventana se queda en su número de cuadro; las que no caben se cierran.
            for (int i = 0; i < slots.length; i++) {
                Slot s = slots[i];
                if (!s.running()) {
                    continue;
                }
                if (i >= count) {
                    log("Diseño sin lugar para " + s.name + ": se cierra");
                    restoreAudio(s.pkg);
                    exec("am force-stop " + s.pkg);
                    s.taskId = -1;
                    continue;
                }
                Rect r = Layouts.bounds(layout, i, screenW, screenH);
                s.bounds = r;
                try {
                    service.resizeTask(s.taskId, r.left, r.top, r.right, r.bottom);
                } catch (Exception ignored) {
                }
            }
            notifyChanged();
        });
    }

    /** Lista de apps que se pueden abrir: nombre visible → componente. */
    public java.util.Map<String, ComponentName> listApps() {
        PackageManager pm = context.getPackageManager();
        List<android.content.pm.ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0));
        java.util.Map<String, ComponentName> byPackage = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || pkg.equals(SHIZUKU_PACKAGE) || byPackage.containsKey(pkg)) {
                continue;
            }
            byPackage.put(pkg, new ComponentName(pkg, ri.activityInfo.name));
            labels.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        List<String> pkgs = new ArrayList<>(byPackage.keySet());
        pkgs.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        java.util.Map<String, ComponentName> result = new java.util.LinkedHashMap<>();
        for (String pkg : pkgs) {
            String label = labels.get(pkg);
            if (result.containsKey(label)) {
                label = label + " (" + pkg + ")";
            }
            result.put(label, byPackage.get(pkg));
        }
        return result;
    }

    /**
     * Abre (o cambia) la app de un cuadro con la sesión en marcha.
     * @return mensaje de error, o null si se aceptó.
     */
    public String openInSlot(final int i, final ComponentName component, final String name) {
        if (!sessionActive || service == null) {
            return "No hay sesión activa.";
        }
        for (int k = 0; k < slots.length; k++) {
            if (k != i && slots[k].running() && component.getPackageName().equals(slots[k].pkg)) {
                return name + " ya está en el cuadro " + (k + 1) + ". Android solo deja una ventana por app.";
            }
        }
        final Slot s = slots[i];
        final String oldPkg = s.running() ? s.pkg : null;
        setSlotApp(i, component, name);
        s.taskId = -1;
        s.volume = 100;
        s.muted = false;
        final Rect b = fullscreenSlot == i ? new Rect(0, 0, screenW, screenH) : boundsOf(i);
        s.bounds = b;
        log("Abrir " + name + " en cuadro " + (i + 1) + " con la sesión en marcha"
                + (oldPkg != null ? " (cambia " + oldPkg + ")" : ""));
        say("Abriendo " + name + " en el cuadro " + (i + 1) + "…");
        work.execute(() -> {
            if (oldPkg != null) {
                restoreAudio(oldPkg);
                exec("am force-stop " + oldPkg);
            }
            exec("am force-stop " + component.getPackageName() + "; " + audioAllow(component.getPackageName()));
            String r;
            try {
                r = service.launchFreeform(component.flattenToString(), b.left, b.top, b.right, b.bottom);
            } catch (Exception e) {
                r = "error:" + e.getMessage();
            }
            log("  → " + r);
            final String result = r;
            main.post(() -> {
                if (result != null && result.startsWith("ok:")) {
                    s.taskId = Integer.parseInt(result.substring(3));
                    focusSlot(i);
                } else {
                    message = "No se pudo abrir " + name + ": " + result;
                }
                notifyChanged();
            });
        });
        return null;
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
                sb.append(s.exec("dumpsys activity activities | grep -E '\\* Task\\{' | head -12")).append("\n\n");
                for (Slot sl : slots) {
                    if (sl.pkg != null && sl.running()) {
                        sb.append(sl.name).append(" permisos: ")
                                .append(s.exec("cmd appops get " + sl.pkg + " PLAY_AUDIO | sed 's/;.*//'; "
                                        + "cmd appops get " + sl.pkg + " TAKE_AUDIO_FOCUS | sed 's/;.*//'").replace('\n', ' '))
                                .append('\n');
                        sb.append(s.exec("dumpsys window windows | grep -A40 'Window{.*" + sl.pkg
                                + "' | grep -m3 -E 'mFrame|frame=|mRequestedWidth|surface'")).append('\n');
                    }
                }
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
