package com.juandegates.multipantalla;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pantalla principal: revisión de requisitos, elección de diseño y apps,
 * teclas, y el fondo negro que queda detrás de las ventanas.
 */
public class MainActivity extends Activity implements Director.Listener {

    private static final int COLOR_BG = 0xFF0E1015;
    private static final int COLOR_CARD = 0xFF171A21;
    private static final int COLOR_BUTTON = 0xFF2A2D36;
    private static final int COLOR_FOCUS = 0xFF2F7DF6;
    private static final int COLOR_OK = 0xFF30A46C;
    private static final int COLOR_BAD = 0xFFE5484D;
    private static final int COLOR_WARN = 0xFFF5A524;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Director director;

    private FrameLayout root;
    private View panel;
    private View fondo;
    private TextView fondoHint;
    private LinearLayout checklist;
    private LinearLayout config;
    private TextView status;
    private boolean fondoMode;
    private String lastSignature = "";

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        director = Director.get(this);
        buildViews();
        director.addListener(this);
        director.tryBind();
        showPanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        director.tryBind();
        director.refreshFreeform();
        render(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Se abrió desde el menú rápido: mostrar el panel.
        showPanel();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            // Salir de MultiPantalla: todo vuelve a la normalidad.
            director.stopSession(false);
        }
        director.removeListener(this);
        TeclasService.setSpy(null);
        super.onDestroy();
    }

    @Override
    public void onDirectorChanged() {
        if (!director.sessionActive && !director.starting && fondoMode) {
            showPanel();
        }
        render(false);
    }

    // ------------------------------------------------------------------ vistas

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        // Panel con dos columnas: revisión a la izquierda, configuración a la derecha.
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(40), dp(28), dp(40), dp(28));

        checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        config = new LinearLayout(this);
        config.setOrientation(LinearLayout.VERTICAL);

        columns.addView(card(checklist), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(this);
        columns.addView(gap, new LinearLayout.LayoutParams(dp(24), 1));
        columns.addView(card(config), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
        panel = columns;
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Fondo: lo que queda detrás de las ventanas durante la sesión.
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        f.setFocusable(true);
        fondoHint = new TextView(this);
        fondoHint.setTextColor(0xFF5C6170);
        fondoHint.setTextSize(16);
        fondoHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        hp.bottomMargin = dp(18);
        f.addView(fondoHint, hp);
        fondo = f;
        fondo.setVisibility(View.GONE);
        root.addView(fondo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xE0101218);
        sb.setCornerRadius(dp(12));
        sb.setStroke(dp(1), 0xFF2F3340);
        status.setBackground(sb);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = dp(18);
        root.addView(status, slp);
        status.setVisibility(View.GONE);

        setContentView(root);
    }

    private View card(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(18));
        scroll.setBackground(bg);
        content.setPadding(dp(28), dp(22), dp(28), dp(22));
        scroll.addView(content);
        return scroll;
    }

    private void showPanel() {
        fondoMode = false;
        panel.setVisibility(View.VISIBLE);
        fondo.setVisibility(View.GONE);
        render(true);
    }

    private void showFondo() {
        fondoMode = true;
        panel.setVisibility(View.GONE);
        fondo.setVisibility(View.VISIBLE);
        fondoHint.setText("MultiPantalla · " + Director.keyName(director.keyNext) + " / "
                + Director.keyName(director.keyPrev) + " cambia de ventana · "
                + Director.keyName(director.keyMenu) + " abre el menú · OK aquí: volver al panel");
        fondo.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (fondoMode && event.getAction() == KeyEvent.ACTION_UP) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK) {
                showPanel();
                return true;
            }
        }
        if (fondoMode && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        if (!fondoMode && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                confirmExit();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void confirmExit() {
        if (!director.sessionActive && !director.starting) {
            finish();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("¿Cerrar todo y salir?")
                .setMessage("Se cerrarán las ventanas y la TV queda como siempre.")
                .setPositiveButton("Salir", (d, w) -> finish())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ dibujar

    private String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(director.shizukuInstalled()).append(director.shizukuRunning())
                .append(director.shizukuPermission()).append(director.connected())
                .append(director.freeform).append(director.keyServiceEnabled())
                .append(TeclasService.isConnected()).append(director.layout)
                .append(director.sessionActive).append(director.starting).append(director.bindingSlow())
                .append(director.keyNext).append(director.keyPrev)
                .append(director.keyMenu);
        for (Director.Slot s : director.slots) {
            sb.append('|').append(s.component).append(s.taskId);
        }
        return sb.toString();
    }

    private void render(boolean force) {
        if (director.message != null && !director.message.isEmpty()) {
            showStatus(director.message);
            director.message = "";
        }
        String sig = signature();
        if (!force && sig.equals(lastSignature)) {
            return;
        }
        lastSignature = sig;
        Object focusedTag = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        renderChecklist();
        renderConfig();
        if (!fondoMode) {
            View again = focusedTag == null ? null : root.findViewWithTag(focusedTag);
            if (again != null && again.isEnabled()) {
                again.requestFocus();
            } else {
                View start = root.findViewWithTag("iniciar");
                if (start != null && start.isEnabled()) {
                    start.requestFocus();
                } else {
                    View first = root.findViewWithTag("primera_accion");
                    if (first != null) {
                        first.requestFocus();
                    }
                }
            }
        }
    }

    private void renderChecklist() {
        checklist.removeAllViews();
        checklist.addView(title("MultiPantalla"));
        checklist.addView(small("Versión " + BuildConfig.VERSION_NAME));
        checklist.addView(header("Revisión"));

        boolean firstAction = true;

        boolean installed = director.shizukuInstalled();
        checklist.addView(row(installed ? 1 : 0, "Shizuku instalado",
                installed ? null : "Instálalo desde Termux (ver README del repositorio).", null, null));

        boolean running = director.shizukuRunning();
        if (installed) {
            View r = row(running ? 1 : 0, "Shizuku activo",
                    running ? null : "Inícialo desde Termux con el comando de arranque. Se apaga cada vez que reinicias la TV.",
                    running ? null : "Abrir Shizuku", running ? null : this::openShizuku);
            tagFirst(r, firstAction && !running);
            firstAction &= running;
            checklist.addView(r);
        }

        if (running) {
            boolean perm = director.shizukuPermission();
            View r = row(perm ? 1 : 0, "Permiso de Shizuku", null,
                    perm ? null : "Dar permiso", perm ? null : director::requestShizukuPermission);
            tagFirst(r, firstAction && !perm);
            firstAction &= perm;
            checklist.addView(r);

            if (perm) {
                boolean conn = director.connected();
                boolean slow = director.bindingSlow();
                View c = row(conn ? 1 : 2, conn ? "Conectado con permisos de sistema" : "Conectando…",
                        conn ? null : slow ? "Tarda mucho: Reintentar abre Shizuku un momento y vuelve." : null,
                        conn ? null : "Reintentar", conn ? null : () -> director.retryBind(slow));
                tagFirst(c, firstAction && !conn);
                checklist.addView(c);
            }
        }

        if (director.connected()) {
            switch (director.freeform) {
                case ON:
                    checklist.addView(row(1, "Ventanas libres activas", null, null, null));
                    break;
                case NEEDS_REBOOT: {
                    View r = row(2, "Ventanas libres: falta reiniciar la TV",
                            "Después de reiniciar, vuelve a iniciar Shizuku desde Termux.",
                            "Reiniciar TV", this::confirmReboot);
                    tagFirst(r, firstAction);
                    firstAction = false;
                    checklist.addView(r);
                    break;
                }
                case OFF: {
                    View r = row(0, "Ventanas libres apagadas", "Es el ajuste oculto de Android que permite varias apps a la vez.",
                            "Activar", director::enableFreeform);
                    tagFirst(r, firstAction);
                    firstAction = false;
                    checklist.addView(r);
                    break;
                }
                default:
                    checklist.addView(row(2, "Revisando ventanas libres…", null, null, null));
            }

            boolean enabled = director.keyServiceEnabled();
            boolean keys = enabled && TeclasService.isConnected();
            View k = row(keys ? 1 : 0, "Servicio de teclas",
                    keys ? "Escucha " + Director.keyName(director.keyNext) + ", " + Director.keyName(director.keyPrev)
                            + " y " + Director.keyName(director.keyMenu) + " en cualquier app."
                            : enabled ? "Está encendido pero desconectado (pasa al instalar una versión nueva)."
                            : "Permite cambiar de ventana y abrir el menú con el control.",
                    keys ? null : enabled ? "Reconectar" : "Activar", keys ? null : director::enableKeyService);
            tagFirst(k, firstAction && !keys);
            checklist.addView(k);
        }

        checklist.addView(header("Ayuda"));
        Button diag = button("Diagnóstico", this::showDiagnostics);
        diag.setTag("diag");
        checklist.addView(diag, buttonParams());
        Button report = button("Generar reporte", this::makeReport);
        report.setTag("reporte");
        checklist.addView(report, buttonParams());
        if (director.connected()) {
            Button full = button("Restablecer apps a pantalla completa", director::resetAllToFullscreen);
            full.setTag("full");
            checklist.addView(full, buttonParams());
            checklist.addView(small("Úsalo si alguna app se abre pequeña o con un marco raro fuera de MultiPantalla."));
        }
    }

    private void tagFirst(View row, boolean isFirst) {
        if (!isFirst) {
            return;
        }
        View b = row.findViewWithTag("accion");
        if (b != null) {
            b.setTag("primera_accion");
        }
    }

    private void renderConfig() {
        config.removeAllViews();
        config.addView(header("Diseño"));
        Button layoutBtn = button("◀  " + Layouts.NAMES[director.layout] + "  ▶   (OK cambia)",
                () -> director.setLayout((director.layout + 1) % Layouts.count()));
        layoutBtn.setTag("layout");
        config.addView(layoutBtn, buttonParams());

        config.addView(header("Apps"));
        int count = Layouts.slots(director.layout);
        for (int i = 0; i < count; i++) {
            final int index = i;
            Director.Slot s = director.slots[i];
            String text = "Cuadro " + (i + 1) + ":  " + (s.hasApp() ? s.name : "vacío");
            if (director.sessionActive) {
                text += s.running() ? "   · abierta" : "   · cerrada";
            }
            Button b = button(text, () -> pickApp(index));
            b.setTag("slot" + i);
            config.addView(b, buttonParams());
        }

        config.addView(header("Teclas"));
        config.addView(small("Siguiente ventana: " + Director.keyName(director.keyNext)
                + "   ·   Anterior: " + Director.keyName(director.keyPrev)
                + "   ·   Menú: " + Director.keyName(director.keyMenu)));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        Button test = button("Probar teclas", this::keyTester);
        test.setTag("probar");
        Button assign = button("Asignar teclas", this::assignKeys);
        assign.setTag("asignar");
        keyRow.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View sp = new View(this);
        keyRow.addView(sp, new LinearLayout.LayoutParams(dp(12), 1));
        keyRow.addView(assign, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams krp = buttonParams();
        config.addView(keyRow, krp);

        View space = new View(this);
        config.addView(space, new LinearLayout.LayoutParams(1, dp(18)));

        boolean anyApp = false;
        for (int i = 0; i < count; i++) {
            anyApp |= director.slots[i].hasApp();
        }
        Button start = button(director.sessionActive ? "▶  Volver a abrir todo" : "▶  Iniciar", this::startSession);
        start.setTag("iniciar");
        start.setTextSize(20);
        start.setEnabled(director.ready() && anyApp);
        start.setAlpha(start.isEnabled() ? 1f : 0.45f);
        config.addView(start, buttonParams());
        if (!director.ready()) {
            config.addView(small("Completa la revisión de la izquierda para iniciar."));
        } else if (!anyApp) {
            config.addView(small("Elige al menos una app."));
        }

        if (director.sessionActive) {
            Button back = button("Volver a las ventanas", () -> {
                showFondo();
                director.raiseAll();
            });
            back.setTag("volver");
            config.addView(back, buttonParams());
            Button stop = button("Cerrar todas las ventanas", director::stopSession);
            stop.setTag("cerrar");
            config.addView(stop, buttonParams());
        }
    }

    // ------------------------------------------------------------------ acciones

    private void startSession() {
        showFondo();
        director.startSession();
    }

    private void openShizuku() {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(Director.SHIZUKU_PACKAGE);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(Director.SHIZUKU_PACKAGE);
        }
        if (intent != null) {
            startActivity(intent);
        }
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("¿Reiniciar la TV?")
                .setMessage("Al volver a encender, inicia Shizuku desde Termux y abre MultiPantalla.")
                .setPositiveButton("Reiniciar", (d, w) -> director.rebootTv())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void pickApp(final int slot) {
        final Map<String, ComponentName> apps = listApps();
        final List<String> names = new ArrayList<>();
        names.add("(Dejar vacío)");
        names.addAll(apps.keySet());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("App para el cuadro " + (slot + 1))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        director.setSlotApp(slot, null, null);
                        return;
                    }
                    String name = names.get(which);
                    ComponentName cn = apps.get(name);
                    for (int i = 0; i < director.slots.length; i++) {
                        Director.Slot other = director.slots[i];
                        if (i != slot && cn != null && cn.getPackageName().equals(other.pkg)
                                && i < Layouts.slots(director.layout)) {
                            showStatus(name + " ya está en el cuadro " + (i + 1)
                                    + ". Android solo deja abrir una ventana por app.");
                            return;
                        }
                    }
                    director.setSlotApp(slot, cn, name);
                })
                .show();
    }

    private Map<String, ComponentName> listApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Map<String, ComponentName> byPackage = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals(Director.SHIZUKU_PACKAGE) || byPackage.containsKey(pkg)) {
                continue;
            }
            byPackage.put(pkg, new ComponentName(pkg, ri.activityInfo.name));
            labels.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        List<String> pkgs = new ArrayList<>(byPackage.keySet());
        pkgs.sort((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        Map<String, ComponentName> result = new LinkedHashMap<>();
        for (String pkg : pkgs) {
            String label = labels.get(pkg);
            if (result.containsKey(label)) {
                label = label + " (" + pkg + ")";
            }
            result.put(label, byPackage.get(pkg));
        }
        return result;
    }

    // ------------------------------------------------------------------ teclas

    private void keyTester() {
        final TextView tv = new TextView(this);
        tv.setTextSize(18);
        tv.setPadding(dp(24), dp(16), dp(24), dp(16));
        tv.setText("Presiona cualquier botón del control.\n\n"
                + (TeclasService.isConnected() ? "" : "Aviso: el servicio de teclas no está activo; "
                + "solo verás las teclas que llegan a esta pantalla.\n"));
        final String[] last = {"", ""};
        final Runnable refresh = () -> tv.setText("Presiona cualquier botón del control.\n\n"
                + "Servicio de teclas (funciona en cualquier app):\n   " + (last[0].isEmpty() ? "—" : last[0])
                + "\n\nEsta pantalla:\n   " + (last[1].isEmpty() ? "—" : last[1])
                + "\n\nSi una tecla no aparece en el servicio, la TV se la queda y no sirve para cambiar de ventana.\n"
                + "Mantén Atrás para salir.");
        refresh.run();
        TeclasService.setSpy(event -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                last[0] = Director.keyName(event.getKeyCode()) + "  (" + event.getKeyCode() + ")";
                main.post(refresh);
            }
            return false;
        });
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Probador de teclas")
                .setView(tv)
                .create();
        final long[] backDown = {0};
        dialog.setOnKeyListener((d, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                last[1] = Director.keyName(code) + "  (" + code + ")";
                refresh.run();
                if (code == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
                    backDown[0] = event.getEventTime();
                }
                if (code == KeyEvent.KEYCODE_BACK && event.getEventTime() - backDown[0] > 600) {
                    d.dismiss();
                }
            }
            return code != KeyEvent.KEYCODE_BACK || event.getAction() == KeyEvent.ACTION_UP;
        });
        dialog.setOnDismissListener(d -> TeclasService.setSpy(null));
        dialog.show();
    }

    private void assignKeys() {
        final String[] steps = {"SIGUIENTE ventana", "ventana ANTERIOR", "abrir el MENÚ rápido"};
        final int[] chosen = new int[3];
        final int[] step = {0};
        final TextView tv = new TextView(this);
        tv.setTextSize(20);
        tv.setPadding(dp(24), dp(20), dp(24), dp(20));
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Asignar teclas")
                .setView(tv)
                .setNegativeButton("Cancelar", null)
                .create();
        final Runnable refresh = () -> tv.setText("Presiona el botón para:\n\n   " + steps[step[0]]
                + "\n\nNo uses flechas, OK, Atrás, Inicio ni volumen.");
        refresh.run();

        final java.util.function.IntPredicate accept = code -> {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME
                    || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER) {
                return false;
            }
            for (int k = 0; k < step[0]; k++) {
                if (chosen[k] == code) {
                    return false;
                }
            }
            chosen[step[0]] = code;
            step[0]++;
            if (step[0] >= steps.length) {
                main.post(() -> {
                    director.setKeys(chosen[0], chosen[1], chosen[2]);
                    showStatus("Teclas guardadas.");
                    dialog.dismiss();
                });
            } else {
                main.post(refresh);
            }
            return true;
        };
        // Primero escucha el servicio de teclas (así se asignan teclas que sí llegan en cualquier app).
        TeclasService.setSpy(event -> event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0 && accept.test(event.getKeyCode()));
        dialog.setOnKeyListener((d, code, event) -> !TeclasService.isConnected()
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && accept.test(code));
        dialog.setOnDismissListener(d -> TeclasService.setSpy(null));
        dialog.show();
    }

    // ------------------------------------------------------------------ diagnóstico

    private void makeReport() {
        showStatus("Generando reporte…");
        director.makeReport(result -> {
            if ("ok".equals(result)) {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Reporte listo")
                        .setMessage("Se guardó en Descargas de la TV como " + Director.REPORT_NAME
                                + ".\n\nPara pasarlo a tu celular, en Termux:\n\n"
                                + "adb pull /sdcard/Download/" + Director.REPORT_NAME + " ~/storage/downloads/")
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                showStatus("No se pudo guardar el reporte: " + result);
            }
        });
    }

    private void showDiagnostics() {
        director.diagnostics(text -> {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(13);
            tv.setPadding(dp(20), dp(12), dp(20), dp(12));
            tv.setFocusable(true);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(tv);
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Diagnóstico")
                    .setView(scroll)
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    // ------------------------------------------------------------------ piezas de interfaz

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(30);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(0xFF8B90A0);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(0, dp(20), 0, dp(8));
        return tv;
    }

    private TextView small(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF9EA3B0);
        tv.setTextSize(14);
        tv.setPadding(0, dp(2), 0, dp(4));
        return tv;
    }

    /** Fila de la revisión: estado (1 = bien, 0 = falta, 2 = pendiente), texto y botón opcional. */
    private View row(int state, String text, String detail, String action, Runnable onAction) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));

        TextView mark = new TextView(this);
        mark.setText(state == 1 ? "✓" : state == 0 ? "✕" : "…");
        mark.setTextSize(20);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(state == 1 ? COLOR_OK : state == 0 ? COLOR_BAD : COLOR_WARN);
        line.addView(mark, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        texts.addView(label);
        if (detail != null) {
            TextView d = small(detail);
            texts.addView(d);
        }
        line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (action != null && onAction != null) {
            Button b = button(action, onAction);
            b.setTag("accion");
            b.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            line.addView(b, lp);
        }
        return line;
    }

    private Button button(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setFocusable(true);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(COLOR_FOCUS));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(COLOR_FOCUS));
        states.addState(new int[]{}, rounded(COLOR_BUTTON));
        b.setBackground(states);
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, 7000);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
