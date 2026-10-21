/*
 * Adaptado de scrcpy (https://github.com/Genymobile/scrcpy), Copyright (C) Genymobile.
 * Licencia Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0
 *
 * Contexto que se presenta como el paquete "com.android.shell", para que el
 * sistema acepte las peticiones que hace este proceso (uid 2000).
 */
package com.juandegates.multipantalla.priv;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

public final class FakeContext extends ContextWrapper {

    public static final String PACKAGE_NAME = "com.android.shell";
    private static final int SHELL_UID = 2000;

    private static FakeContext instance;

    public static synchronized FakeContext get() {
        if (instance == null) {
            instance = new FakeContext(Workarounds.getSystemContext());
        }
        return instance;
    }

    /** Variante que envuelve un contexto ya existente (plan B). */
    public static FakeContext wrap(Context base) {
        return new FakeContext(base);
    }

    private FakeContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT >= 31) {
            AttributionSource.Builder builder = new AttributionSource.Builder(SHELL_UID);
            builder.setPackageName(PACKAGE_NAME);
            return builder.build();
        }
        return super.getAttributionSource();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }
}
