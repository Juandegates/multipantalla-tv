package com.juandegates.multipantalla;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.juandegates.multipantalla.priv.MultiService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST = 7;
    private static final int CELLS = 4;

    // Tamaño de cada pantalla virtual: 1280x720 a 213 dpi = 960x540 dp,
    // el mismo espacio que ven las apps en una TV normal.
    private static final int VD_WIDTH = 1280;
    private static final int VD_HEIGHT = 720;
    private static final int VD_DPI = 213;

    private static final int COLOR_IDLE = 0xFF2A2D36;
    private static final int COLOR_SELECTED = 0xFF2F7DF6;
    private static final int COLOR_CONTROL = 0xFFF5A524;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService keys = Executors.newSingleThreadExecutor();

    private final Cell[] cells = new Cell[CELLS];
    private int selected = 0;
    private Cell controlling = null;

    private TextView status;
    private volatile IMultiService service;
    private boolean bound;

    // Para saber si "Atrás" se mantuvo presionado en modo control.
    private long backDownAt;
    private boolean backLongHandled;

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, MultiService.class.getName()))
            .daemon(false)
            .processNameSuffix("svc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    // ------------------------------------------------------------------ celdas

    private final class Cell implements SurfaceHolder.Callback {
        final int index;
        final FrameLayout frame;
        final SurfaceView surfaceView;
        final TextView label;
        final TextView hint;
        Surface surface;
        int displayId = -1;
        String pkg;
        String component;
        String appName;
        boolean muted;
        boolean sharedAudio;

        Cell(int index) {
            this.index = index;
            frame = new FrameLayout(MainActivity.this);
            frame.setPadding(dp(3), dp(3), dp(3), dp(3));

            surfaceView = new SurfaceView(MainActivity.this);
            surfaceView.getHolder().setFixedSize(VD_WIDTH, VD_HEIGHT);
            surfaceView.getHolder().addCallback(this);
            surfaceView.setFocusable(false);
            frame.addView(surfaceView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            hint = new TextView(MainActivity.this);
            hint.setTextColor(0xFFB8BCC8);
            hint.setTextSize(18);
            hint.setGravity(Gravity.CENTER);
            frame.addView(hint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xB0000000);
            pill.setCornerRadius(dp(10));
            label.setBackground(pill);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(dp(8), dp(8), 0, 0);
            frame.addView(label, lp);
            refresh();
        }

        void refresh() {
            String text = (index + 1) + " · " + (appName == null ? "vacía" : appName);
            if (muted) {
                text += "  · silencio";
            }
            label.setText(text);
            if (appName == null) {
                hint.setVisibility(View.VISIBLE);
                hint.setText(displayId < 0 ? "Preparando pantalla…" : "OK para elegir una app");
            } else {
                hint.setVisibility(View.GONE);
            }
            int color = this == controlling ? COLOR_CONTROL
                    : (index == selected ? COLOR_SELECTED : COLOR_IDLE);
            frame.setBackgroundColor(color);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surface = holder.getSurface();
            ensureDisplay(this);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            surface = null;
            final int id = displayId;
            final IMultiService s = service;
            if (id >= 0 && s != null) {
                work.execute(() -> {
                    try {
                        s.setDisplaySurface(id, null);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        main.postDelayed(() -> {
            if (!Shizuku.pingBinder()) {
                showStatus("Shizuku no está activo. Ábrelo y actívalo con el comando de ADB, luego vuelve a abrir MultiPantalla.", false);
            }
        }, 1500);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        final IMultiService s = service;
        if (s != null) {
            final List<Cell> snapshot = new ArrayList<>();
            Collections.addAll(snapshot, cells);
            work.execute(() -> {
                for (Cell c : snapshot) {
                    if (c.pkg != null) {
                        restoreAudio(s, c.pkg);
                    }
                    if (c.displayId >= 0) {
                        try {
                            s.releaseDisplay(c.displayId);
                        } catch (Exception ignored) {
                        }
                    }
                }
                try {
                    if (bound) {
                        Shizuku.unbindUserService(serviceArgs, connection, true);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        work.shutdown();
        keys.shutdown();
        super.onDestroy();
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1015);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                int i = row * 2 + col;
                cells[i] = new Cell(i);
                line.addView(cells[i].frame, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0101218);
        bg.setCornerRadius(dp(12));
        status.setBackground(bg);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = dp(24);
        root.addView(status, slp);
        status.setVisibility(View.GONE);

        setContentView(root);
        showStatus("Flechas: elegir cuadro · OK: abrir app o menú · Menú/OK largo: opciones", true);
    }

    // ------------------------------------------------------------------ Shizuku

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(this::connectShizuku);

    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(() -> {
        service = null;
        bound = false;
        showStatus("Shizuku se detuvo. Vuelve a iniciarlo con el comando de ADB.", false);
    });

    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) ->
            main.post(() -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectShizuku();
                } else {
                    showStatus("Sin el permiso de Shizuku no se pueden crear las pantallas.", false);
                }
            });

    private void connectShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                showStatus("Tu versión de Shizuku es muy vieja. Instala la más reciente.", false);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                showStatus("Acepta el permiso de Shizuku en la ventana que aparece.", false);
                Shizuku.requestPermission(SHIZUKU_REQUEST);
                return;
            }
            if (!bound) {
                showStatus("Conectando con Shizuku…", false);
                Shizuku.bindUserService(serviceArgs, connection);
                bound = true;
            }
        } catch (Throwable t) {
            showStatus("Error con Shizuku: " + t.getMessage(), false);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                showStatus("El servicio de Shizuku no respondió.", false);
                return;
            }
            service = IMultiService.Stub.asInterface(binder);
            showStatus("Listo. Elige un cuadro y presiona OK.", true);
            for (Cell c : cells) {
                ensureDisplay(c);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    // ------------------------------------------------------------------ pantallas

    private void ensureDisplay(final Cell cell) {
        final IMultiService s = service;
        final Surface surface = cell.surface;
        if (s == null || surface == null || !surface.isValid()) {
            return;
        }
        work.execute(() -> {
            try {
                if (cell.displayId >= 0) {
                    s.setDisplaySurface(cell.displayId, surface);
                    return;
                }
                int id = s.createDisplay("MultiPantalla-" + (cell.index + 1), VD_WIDTH, VD_HEIGHT, VD_DPI, surface);
                if (id < 0) {
                    String err = s.lastError();
                    main.post(() -> showLongText("No se pudo crear la pantalla " + (cell.index + 1), err));
                    return;
                }
                cell.displayId = id;
                main.post(cell::refresh);
            } catch (Exception e) {
                main.post(() -> showStatus("Error al crear pantalla: " + e.getMessage(), false));
            }
        });
    }

    // ------------------------------------------------------------------ teclas

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        if (controlling != null) {
            return handleControlKey(event);
        }
        return handleNavigationKey(event);
    }

    private boolean handleControlKey(KeyEvent event) {
        final Cell cell = controlling;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            // Atrás corto = Atrás dentro de la app. Atrás largo = salir del control.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    backDownAt = event.getEventTime();
                    backLongHandled = false;
                } else if (!backLongHandled && event.getEventTime() - backDownAt > 600) {
                    backLongHandled = true;
                    stopControl();
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP && !backLongHandled) {
                sendKey(cell, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0);
                sendKey(cell, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            sendKey(cell, event.getAction(), code, event.getRepeatCount(), event.getMetaState());
        }
        return true;
    }

    private void sendKey(Cell cell, int action, int code, int repeat, int meta) {
        final IMultiService s = service;
        final int id = cell.displayId;
        if (s == null || id < 0) {
            return;
        }
        keys.execute(() -> {
            try {
                s.injectKey(id, action, code, repeat, meta);
            } catch (Exception ignored) {
            }
        });
    }

    private long okDownAt;
    private boolean okLongHandled;

    private boolean handleNavigationKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveSelection(selected % 2 == 1 ? selected - 1 : selected);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveSelection(selected % 2 == 0 ? selected + 1 : selected);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveSelection(selected >= 2 ? selected - 2 : selected);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveSelection(selected < 2 ? selected + 2 : selected);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // OK corto: abrir/controlar. OK largo: menú del cuadro.
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        okDownAt = event.getEventTime();
                        okLongHandled = false;
                    } else if (!okLongHandled && event.getEventTime() - okDownAt > 600) {
                        okLongHandled = true;
                        showCellMenu(cells[selected]);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP && !okLongHandled) {
                    onCellPressed(cells[selected]);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                if (down && event.getRepeatCount() == 0) showCellMenu(cells[selected]);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP) confirmExit();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void moveSelection(int to) {
        selected = to;
        for (Cell c : cells) {
            c.refresh();
        }
    }

    private void startControl(Cell cell) {
        controlling = cell;
        for (Cell c : cells) {
            c.refresh();
        }
        showStatus("Controlando cuadro " + (cell.index + 1) + " · mantén ATRÁS para salir", true);
    }

    private void stopControl() {
        controlling = null;
        for (Cell c : cells) {
            c.refresh();
        }
        showStatus("Flechas: elegir cuadro · OK: controlar · OK largo: opciones", true);
    }

    // ------------------------------------------------------------------ acciones

    private void onCellPressed(Cell cell) {
        if (service == null) {
            showStatus("Todavía no hay conexión con Shizuku.", false);
            return;
        }
        if (cell.displayId < 0) {
            showStatus("El cuadro " + (cell.index + 1) + " aún no tiene pantalla.", false);
            ensureDisplay(cell);
            return;
        }
        if (cell.pkg == null) {
            pickApp(cell);
        } else {
            startControl(cell);
        }
    }

    private void showCellMenu(final Cell cell) {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (cell.pkg != null) {
            items.add("Controlar " + cell.appName);
            actions.add(() -> startControl(cell));
            items.add(cell.muted ? "Activar sonido" : "Silenciar");
            actions.add(() -> setMuted(cell, !cell.muted));
            items.add(cell.sharedAudio ? "Dejar que pause a las demás (normal)"
                    : "Que no pause a las demás (experimental)");
            actions.add(() -> setSharedAudio(cell, !cell.sharedAudio));
            items.add("Cambiar app");
            actions.add(() -> pickApp(cell));
            items.add("Cerrar app");
            actions.add(() -> closeApp(cell));
        } else {
            items.add("Elegir app");
            actions.add(() -> pickApp(cell));
        }
        items.add("Diagnóstico");
        actions.add(this::showDiagnostics);
        items.add("Salir de MultiPantalla");
        actions.add(this::finish);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cuadro " + (cell.index + 1))
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void pickApp(final Cell cell) {
        final Map<String, ComponentName> apps = listApps();
        final List<String> names = new ArrayList<>(apps.keySet());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("App para el cuadro " + (cell.index + 1))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    String name = names.get(which);
                    launchInCell(cell, name, apps.get(name));
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
            if (pkg.equals(getPackageName()) || byPackage.containsKey(pkg)) {
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

    private void launchInCell(final Cell cell, final String name, final ComponentName component) {
        if (component == null) {
            return;
        }
        for (Cell other : cells) {
            if (other != cell && component.getPackageName().equals(other.pkg)) {
                Toast.makeText(this, name + " ya está en el cuadro " + (other.index + 1)
                        + ". Android solo deja abrir una copia de cada app.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        final IMultiService s = service;
        final int id = cell.displayId;
        if (s == null || id < 0) {
            return;
        }
        final String previous = cell.pkg;
        showStatus("Abriendo " + name + " en el cuadro " + (cell.index + 1) + "…", false);
        work.execute(() -> {
            try {
                if (previous != null && !previous.equals(component.getPackageName())) {
                    restoreAudio(s, previous);
                    s.exec("am force-stop " + previous);
                }
                // Cerrarla primero obliga a Android a abrirla en esta pantalla
                // y no en la principal.
                s.exec("am force-stop " + component.getPackageName());
                String out = s.exec("am start --display " + id + " -n "
                        + component.flattenToShortString() + " -f 0x10000000");
                boolean failed = out.contains("Error") || out.contains("Exception");
                main.post(() -> {
                    if (failed) {
                        showLongText("No se pudo abrir " + name, out);
                        return;
                    }
                    cell.pkg = component.getPackageName();
                    cell.component = component.flattenToShortString();
                    cell.appName = name;
                    cell.muted = false;
                    cell.sharedAudio = false;
                    cell.refresh();
                    showStatus(name + " abierta en el cuadro " + (cell.index + 1)
                            + ". OK para controlarla.", true);
                });
            } catch (Exception e) {
                main.post(() -> showStatus("Error al abrir: " + e.getMessage(), false));
            }
        });
    }

    private void setMuted(final Cell cell, final boolean mute) {
        final IMultiService s = service;
        if (s == null || cell.pkg == null) {
            return;
        }
        final String pkg = cell.pkg;
        work.execute(() -> {
            try {
                s.exec("cmd appops set " + pkg + " PLAY_AUDIO " + (mute ? "deny" : "allow"));
                main.post(() -> {
                    cell.muted = mute;
                    cell.refresh();
                    showStatus(cell.appName + (mute ? " silenciada" : " con sonido"), true);
                });
            } catch (Exception e) {
                main.post(() -> showStatus("No se pudo cambiar el sonido: " + e.getMessage(), false));
            }
        });
    }

    private void setSharedAudio(final Cell cell, final boolean shared) {
        final IMultiService s = service;
        if (s == null || cell.pkg == null) {
            return;
        }
        final String pkg = cell.pkg;
        work.execute(() -> {
            try {
                s.exec("cmd appops set " + pkg + " TAKE_AUDIO_FOCUS " + (shared ? "ignore" : "default"));
                main.post(() -> {
                    cell.sharedAudio = shared;
                    showStatus(shared
                            ? cell.appName + " ya no pedirá el audio solo para ella. Si deja de reproducir, regrésalo a normal."
                            : cell.appName + " vuelve al audio normal.", true);
                });
            } catch (Exception e) {
                main.post(() -> showStatus("No se pudo cambiar: " + e.getMessage(), false));
            }
        });
    }

    private void closeApp(final Cell cell) {
        final IMultiService s = service;
        if (s == null || cell.pkg == null) {
            return;
        }
        final String pkg = cell.pkg;
        work.execute(() -> {
            try {
                restoreAudio(s, pkg);
                s.exec("am force-stop " + pkg);
            } catch (Exception ignored) {
            }
            main.post(() -> {
                cell.pkg = null;
                cell.component = null;
                cell.appName = null;
                cell.muted = false;
                cell.sharedAudio = false;
                if (controlling == cell) {
                    controlling = null;
                }
                cell.refresh();
            });
        });
    }

    private static void restoreAudio(IMultiService s, String pkg) {
        try {
            s.exec("cmd appops set " + pkg + " PLAY_AUDIO default; cmd appops set " + pkg + " TAKE_AUDIO_FOCUS default");
        } catch (Exception ignored) {
        }
    }

    private void showDiagnostics() {
        final IMultiService s = service;
        if (s == null) {
            showLongText("Diagnóstico", "Sin conexión con Shizuku.\nShizuku activo: " + Shizuku.pingBinder());
            return;
        }
        work.execute(() -> {
            String text;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("Versión: ").append(BuildConfig.VERSION_NAME).append('\n');
                sb.append("uid del servicio: ").append(s.exec("id -u")).append('\n');
                for (Cell c : cells) {
                    sb.append("Cuadro ").append(c.index + 1).append(": pantalla ").append(c.displayId)
                            .append(c.pkg != null ? " · " + c.pkg : "").append('\n');
                }
                sb.append("Último error: ").append(s.lastError()).append("\n\n");
                sb.append(s.exec("dumpsys activity activities | grep -E 'Display #|ResumedActivity:'"));
                text = sb.toString();
            } catch (Exception e) {
                text = "Error: " + e.getMessage();
            }
            final String result = text;
            main.post(() -> showLongText("Diagnóstico", result));
        });
    }

    private void confirmExit() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("¿Salir de MultiPantalla?")
                .setMessage("Se cerrarán las apps de los cuadros.")
                .setPositiveButton("Salir", (d, w) -> finish())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ utilidades

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private void showStatus(String text, boolean autoHide) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
        if (autoHide) {
            main.postDelayed(hideStatus, 6000);
        }
    }

    private void showLongText(String title, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextIsSelectable(false);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("OK", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
