package com.juandegates.multipantalla;

import android.graphics.Rect;

/** Diseños de pantalla. Cada cuadro se da en fracciones de la pantalla. */
public final class Layouts {

    public static final String[] NAMES = {
            "2 lado a lado",
            "2 centradas 16:9",
            "3 cuadros",
            "Principal + 3",
            "4 cuadros",
    };

    // {izquierda, arriba, derecha, abajo} en fracciones de 0 a 1.
    private static final float[][][] SLOTS = {
            // 2 lado a lado: cada una ocupa media pantalla, de arriba abajo.
            {{0f, 0f, .5f, 1f}, {.5f, 0f, 1f, 1f}},
            // 2 centradas 16:9: del tamaño exacto del video, centradas a lo alto.
            {{0f, .25f, .5f, .75f}, {.5f, .25f, 1f, .75f}},
            // 3 cuadros: dos arriba y uno abajo al centro.
            {{0f, 0f, .5f, .5f}, {.5f, 0f, 1f, .5f}, {.25f, .5f, .75f, 1f}},
            // Principal + 3: una grande y tres chicas a la derecha.
            {{0f, 0f, .75f, .75f}, {.75f, 0f, 1f, .25f}, {.75f, .25f, 1f, .5f}, {.75f, .5f, 1f, .75f}},
            // 4 cuadros.
            {{0f, 0f, .5f, .5f}, {.5f, 0f, 1f, .5f}, {0f, .5f, .5f, 1f}, {.5f, .5f, 1f, 1f}},
    };

    public static final int MAX_SLOTS = 4;

    private Layouts() {
    }

    public static int count() {
        return NAMES.length;
    }

    public static int slots(int layout) {
        return SLOTS[clamp(layout)].length;
    }

    public static Rect bounds(int layout, int slot, int width, int height) {
        float[] f = SLOTS[clamp(layout)][slot];
        return new Rect(Math.round(f[0] * width), Math.round(f[1] * height),
                Math.round(f[2] * width), Math.round(f[3] * height));
    }

    public static int clamp(int layout) {
        if (layout < 0) {
            return 0;
        }
        return Math.min(layout, NAMES.length - 1);
    }
}
