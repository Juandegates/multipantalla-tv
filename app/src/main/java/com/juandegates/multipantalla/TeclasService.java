package com.juandegates.multipantalla;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de accesibilidad: recibe las teclas del control aunque otra app
 * tenga el mando, dibuja el borde de la ventana activa y el menú rápido.
 */
public class TeclasService extends AccessibilityService implements Director.Listener {

    /** Para el probador de teclas y para asignarlas desde la pantalla principal. */
    public interface KeySpy {
        /** Devuelve true para que la tecla no llegue a ninguna app. */
        boolean onKey(KeyEvent event);
    }

    private static TeclasService instance;
    private static KeySpy spy;

    public static boolean isConnected() {
        return instance != null;
    }

    public static void setSpy(KeySpy keySpy) {
        spy = keySpy;
        TeclasService s = instance;
        if (s != null) {
            s.main.post(s::updateKeyFiltering);
        }
    }

    private boolean filtering = true;

    /**
     * Fuera de la sesión el servicio no revisa ninguna tecla: la TV funciona
     * exactamente igual que sin MultiPantalla.
     */
    private void updateKeyFiltering() {
        boolean want = director != null && (director.sessionActive || director.starting || menuOpen)
                || spy != null;
        if (want == filtering) {
            return;
        }
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            if (want) {
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            } else {
                info.flags &= ~android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            }
            setServiceInfo(info);
            filtering = want;
        } catch (Throwable ignored) {
        }
    }

    private String homePackage;

    private String homePackage() {
        if (homePackage == null) {
            try {
                android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME);
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
                homePackage = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            } catch (Throwable t) {
                homePackage = "";
            }
        }
        return homePackage;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Director director;
    private WindowManager windows;

    private BorderView border;
    private boolean borderShown;
    private int lastActive = -2;

    private FrameLayout menuRoot;
    private LinearLayout menuPanel;
    private boolean menuOpen;
    private int menuIndex;
    private final List<MenuItem> menuItems = new ArrayList<>();

    private static final class MenuItem {
        final String label;
        final Runnable action;
        final boolean closes;
        final boolean isVolume;

        MenuItem(String label, Runnable action, boolean closes, boolean isVolume) {
            this.label = label;
            this.action = action;
            this.closes = closes;
            this.isVolume = isVolume;
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        director = Director.get(this);
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        director.addListener(this);
        main.postDelayed(refresher, 4000);
        updateKeyFiltering();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        main.removeCallbacksAndMessages(null);
        hideBorder();
        closeMenu();
        if (director != null) {
            director.removeListener(this);
        }
        instance = null;
    }

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (director != null && director.sessionActive) {
                director.refreshTasks();
            }
            main.postDelayed(this, 4000);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Si se presiona Inicio durante la sesión, MultiPantalla se cierra sola
        // y deja todo como estaba.
        if (director == null || !director.sessionActive || director.starting || event == null
                || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        String home = homePackage();
        if (!home.isEmpty() && home.equals(pkg)) {
            closeMenu();
            hideBorder();
            director.stopSession(false);
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------------------------------------------------------- teclas

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        KeySpy s = spy;
        if (s != null && s.onKey(event)) {
            return true;
        }
        if (director == null) {
            return false;
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER
                || code == KeyEvent.KEYCODE_HOME) {
            return false;
        }
        if (menuOpen) {
            handleMenuKey(event);
            return true;
        }
        if (!director.sessionActive) {
            return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (code == director.keyNext) {
            if (down) {
                director.focusNext();
                flashBorder();
            }
            return true;
        }
        if (code == director.keyPrev) {
            if (down) {
                director.focusPrev();
                flashBorder();
            }
            return true;
        }
        if (code == director.keyMenu) {
            if (down) {
                openMenu();
            }
            return true;
        }
        return false;
    }

    private void handleMenuKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        int code = event.getKeyCode();
        MenuItem item = menuItems.isEmpty() ? null : menuItems.get(menuIndex);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                menuIndex = (menuIndex - 1 + menuItems.size()) % menuItems.size();
                renderMenu();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                menuIndex = (menuIndex + 1) % menuItems.size();
                renderMenu();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (item != null && item.isVolume && director.active >= 0) {
                    director.changeVolume(director.active, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10);
                    buildMenu();
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    director.focusNext();
                    buildMenu();
                } else {
                    director.focusPrev();
                    buildMenu();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (item != null && event.getRepeatCount() == 0) {
                    if (item.closes) {
                        closeMenu();
                    }
                    item.action.run();
                    if (!item.closes) {
                        main.postDelayed(this::buildMenu, 150);
                    }
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                closeMenu();
                break;
            default:
                if (code == director.keyMenu) {
                    closeMenu();
                } else if (code == director.keyNext) {
                    director.focusNext();
                    buildMenu();
                } else if (code == director.keyPrev) {
                    director.focusPrev();
                    buildMenu();
                }
                break;
        }
    }

    // ---------------------------------------------------------------- Director

    @Override
    public void onDirectorChanged() {
        updateKeyFiltering();
        if (director.active != lastActive) {
            lastActive = director.active;
            if (director.sessionActive && director.active >= 0) {
                flashBorder();
            }
        }
        if (!director.sessionActive) {
            hideBorder();
            closeMenu();
            return;
        }
        if (borderShown) {
            updateBorder();
        }
        if (menuOpen) {
            buildMenu();
        }
    }

    private Rect activeBounds() {
        int a = director.active;
        if (a < 0) {
            return null;
        }
        if (director.fullscreenSlot == a) {
            return new Rect(0, 0, director.screenWidth(), director.screenHeight());
        }
        return director.boundsOf(a);
    }

    // ---------------------------------------------------------------- borde

    private WindowManager.LayoutParams overlayParams(Rect r) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (r != null) {
            lp.x = r.left;
            lp.y = r.top;
            lp.width = r.width();
            lp.height = r.height();
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        return lp;
    }

    private final Runnable hideBorderLater = this::hideBorder;

    private void flashBorder() {
        main.post(() -> {
            updateBorder();
            main.removeCallbacks(hideBorderLater);
            if (!menuOpen) {
                main.postDelayed(hideBorderLater, 3000);
            }
        });
    }

    private void updateBorder() {
        Rect r = activeBounds();
        if (r == null || windows == null) {
            hideBorder();
            return;
        }
        Director.Slot slot = director.slots[director.active];
        String label = (director.active + 1) + " · " + (slot.name == null ? "" : slot.name)
                + " · " + (slot.muted ? "silencio" : slot.volume + "%");
        try {
            if (border == null) {
                border = new BorderView(this);
            }
            border.label = label;
            if (borderShown) {
                windows.updateViewLayout(border, overlayParams(r));
                border.invalidate();
            } else {
                windows.addView(border, overlayParams(r));
                borderShown = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideBorder() {
        if (border != null && borderShown && windows != null) {
            try {
                windows.removeView(border);
            } catch (Throwable ignored) {
            }
        }
        borderShown = false;
    }

    private static final class BorderView extends View {
        String label = "";
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        BorderView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(5 * density);
            stroke.setColor(0xFFF5A524);
            pill.setColor(0xD0000000);
            text.setColor(Color.WHITE);
            text.setTextSize(16 * density);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = stroke.getStrokeWidth() / 2f;
            canvas.drawRect(half, half, getWidth() - half, getHeight() - half, stroke);
            float pad = 10 * density;
            float w = text.measureText(label) + pad * 2;
            float h = 30 * density;
            RectF r = new RectF(14 * density, 14 * density, 14 * density + w, 14 * density + h);
            canvas.drawRoundRect(r, h / 2, h / 2, pill);
            canvas.drawText(label, r.left + pad, r.centerY() + text.getTextSize() / 3f, text);
        }
    }

    // ---------------------------------------------------------------- menú rápido

    private void openMenu() {
        if (director.active < 0) {
            director.focusNext();
        }
        if (director.active < 0) {
            return;
        }
        menuIndex = 0;
        menuOpen = true;
        main.removeCallbacks(hideBorderLater);
        updateBorder();
        buildMenu();
    }

    private void closeMenu() {
        if (menuRoot != null && windows != null) {
            try {
                windows.removeView(menuRoot);
            } catch (Throwable ignored) {
            }
        }
        menuRoot = null;
        menuPanel = null;
        boolean wasOpen = menuOpen;
        menuOpen = false;
        if (wasOpen) {
            main.postDelayed(hideBorderLater, 1500);
        }
    }

    private void buildMenu() {
        if (!menuOpen) {
            return;
        }
        final int a = director.active;
        if (a < 0) {
            closeMenu();
            return;
        }
        final Director.Slot slot = director.slots[a];
        menuItems.clear();
        menuItems.add(new MenuItem("Reproducir / Pausa", () -> director.togglePlay(a), false, false));
        menuItems.add(new MenuItem("Volumen: " + slot.volume + "%   ◀ ▶", () -> {
        }, false, true));
        menuItems.add(new MenuItem(slot.muted ? "Quitar silencio" : "Silenciar",
                () -> director.toggleMute(a), false, false));
        menuItems.add(new MenuItem("Que solo suene esta", () -> director.soloAudio(a), false, false));
        menuItems.add(new MenuItem(director.fullscreenSlot == a ? "Volver a la cuadrícula" : "Pantalla completa",
                () -> director.toggleFullscreen(a), true, false));
        menuItems.add(new MenuItem("Diseño: " + Layouts.NAMES[director.layout] + "  (OK cambia)",
                director::cycleLayout, false, false));
        menuItems.add(new MenuItem("Cambiar apps (abrir MultiPantalla)", director::openPanel, true, false));
        menuItems.add(new MenuItem("Cerrar todo", () -> {
            director.stopSession();
            director.openPanel();
        }, true, false));
        if (menuIndex >= menuItems.size()) {
            menuIndex = 0;
        }
        renderMenu();
    }

    private void renderMenu() {
        if (!menuOpen || windows == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        boolean add = menuRoot == null;
        if (add) {
            menuRoot = new FrameLayout(this);
            menuPanel = new LinearLayout(this);
            menuPanel.setOrientation(LinearLayout.VERTICAL);
            menuPanel.setPadding(dp(d, 18), dp(d, 14), dp(d, 18), dp(d, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0141820);
            bg.setCornerRadius(dp(d, 16));
            bg.setStroke(dp(d, 2), 0xFFF5A524);
            menuPanel.setBackground(bg);
            menuRoot.addView(menuPanel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        menuPanel.removeAllViews();

        Director.Slot slot = director.slots[director.active];
        TextView title = new TextView(this);
        title.setText("Ventana " + (director.active + 1) + " · " + (slot.name == null ? "" : slot.name));
        title.setTextColor(0xFFF5A524);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(d, 6), 0, dp(d, 6), dp(d, 8));
        menuPanel.addView(title);

        for (int i = 0; i < menuItems.size(); i++) {
            TextView tv = new TextView(this);
            tv.setText(menuItems.get(i).label);
            tv.setTextSize(16);
            tv.setPadding(dp(d, 12), dp(d, 7), dp(d, 12), dp(d, 7));
            if (i == menuIndex) {
                GradientDrawable sel = new GradientDrawable();
                sel.setColor(0xFF2F7DF6);
                sel.setCornerRadius(dp(d, 8));
                tv.setBackground(sel);
                tv.setTextColor(Color.WHITE);
            } else {
                tv.setTextColor(0xFFD6D9E0);
            }
            menuPanel.addView(tv);
        }
        TextView hint = new TextView(this);
        hint.setText("▲▼ elegir · OK aplicar · ◀▶ otra ventana · Atrás cerrar");
        hint.setTextColor(0xFF8B90A0);
        hint.setTextSize(12);
        hint.setPadding(dp(d, 6), dp(d, 8), dp(d, 6), 0);
        menuPanel.addView(hint);

        // El menú se centra sobre la ventana activa.
        Rect r = activeBounds();
        menuPanel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = menuPanel.getMeasuredWidth();
        int mh = menuPanel.getMeasuredHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
        int sw = director.screenWidth();
        int sh = director.screenHeight();
        int cx = r == null ? sw / 2 : r.centerX();
        int cy = r == null ? sh / 2 : r.centerY();
        lp.leftMargin = Math.max(0, Math.min(sw - mw, cx - mw / 2));
        lp.topMargin = Math.max(0, Math.min(sh - mh, cy - mh / 2));
        menuPanel.setLayoutParams(lp);

        try {
            if (add) {
                windows.addView(menuRoot, overlayParams(null));
            } else {
                menuRoot.requestLayout();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(float density, int v) {
        return Math.round(v * density);
    }
}
