/*
 * Adaptado de scrcpy (https://github.com/Genymobile/scrcpy), Copyright (C) Genymobile.
 * Licencia Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0
 *
 * Prepara un ActivityThread falso para poder pedir servicios del sistema
 * desde un proceso que corre como shell (uid 2000) y no es una app normal.
 */
package com.juandegates.multipantalla.priv;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
public final class Workarounds {

    private static final String TAG = "MP-Workarounds";

    private static Class<?> activityThreadClass;
    private static Object activityThread;
    private static boolean applied;

    private Workarounds() {
    }

    private static synchronized void ensureActivityThread() throws Exception {
        if (activityThread != null) {
            return;
        }
        activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();

        Field current = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);

        Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(activityThread, true);
    }

    public static synchronized void apply() throws Exception {
        if (applied) {
            return;
        }
        ensureActivityThread();
        if (Build.VERSION.SDK_INT >= 31) {
            fillConfigurationController();
        }
        fillAppInfo();
        fillAppContext();
        applied = true;
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor = appBindDataClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object appBindData = ctor.newInstance();

            ApplicationInfo info = new ApplicationInfo();
            info.packageName = FakeContext.PACKAGE_NAME;

            Field appInfo = appBindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(appBindData, info);

            Field bound = activityThreadClass.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, appBindData);
        } catch (Throwable t) {
            Log.w(TAG, "fillAppInfo: " + t);
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, FakeContext.get());
            Field initial = activityThreadClass.getDeclaredField("mInitialApplication");
            initial.setAccessible(true);
            initial.set(activityThread, app);
        } catch (Throwable t) {
            Log.w(TAG, "fillAppContext: " + t);
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> ccClass = Class.forName("android.app.ConfigurationController");
            Class<?> atiClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = ccClass.getDeclaredConstructor(atiClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(activityThread);

            Field field = activityThreadClass.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(activityThread, controller);
        } catch (Throwable t) {
            Log.w(TAG, "fillConfigurationController: " + t);
        }
    }

    static Context getSystemContext() {
        try {
            ensureActivityThread();
            Method method = activityThreadClass.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(activityThread);
        } catch (Throwable t) {
            Log.w(TAG, "getSystemContext: " + t);
            return null;
        }
    }
}
