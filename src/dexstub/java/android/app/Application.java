// Compile-time stub of android.app.Application, never shipped: the activity
// callbacks the local host registers.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Application.java#ActivityLifecycleCallbacks
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Application.java#registerActivityLifecycleCallbacks
//
// not ported: onActivityPreCreated, onActivityPostCreated, onActivityPreStarted, onActivityPostStarted, onActivityPreResumed, onActivityPostResumed (default methods since API 29, the stub needs only the abstract ones)
// not ported: onActivityPrePaused, onActivityPostPaused, onActivityPreStopped, onActivityPostStopped, onActivityPreSaveInstanceState, onActivityPostSaveInstanceState (default methods since API 29)
// not ported: onActivityPreDestroyed, onActivityPostDestroyed, onActivityConfigurationChanged (default methods, never called by the local host)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Bundle;

import java.io.File;

public class Application extends Context {
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle savedInstanceState);

        void onActivityStarted(Activity activity);

        void onActivityResumed(Activity activity);

        void onActivityPaused(Activity activity);

        void onActivityStopped(Activity activity);

        void onActivitySaveInstanceState(Activity activity, Bundle outState);

        void onActivityDestroyed(Activity activity);
    }

    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        throw new RuntimeException("stub");
    }

    @Override
    public AssetManager getAssets() {
        throw new RuntimeException("stub");
    }

    @Override
    public File getFilesDir() {
        throw new RuntimeException("stub");
    }

    @Override
    public Context getApplicationContext() {
        throw new RuntimeException("stub");
    }

    @Override
    public String getPackageName() {
        throw new RuntimeException("stub");
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new RuntimeException("stub");
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        throw new RuntimeException("stub");
    }

    @Override
    public void startActivity(Intent intent) {
        throw new RuntimeException("stub");
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#getResources
    @Override
    public android.content.res.Resources getResources() {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Application.java#unregisterActivityLifecycleCallbacks
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        throw new RuntimeException("stub");
    }
}
