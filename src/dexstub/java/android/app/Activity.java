// Compile-time stub of android.app.Activity, never shipped: the window the
// local host adds its surface to, and the calls of the Local screen.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#getWindow
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#onCreate
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#setContentView
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#getIntent
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#finish
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import java.io.File;

public class Activity extends Context {
    protected void onCreate(Bundle savedInstanceState) {
        throw new RuntimeException("stub");
    }

    public Window getWindow() {
        throw new RuntimeException("stub");
    }

    public void setContentView(View view) {
        throw new RuntimeException("stub");
    }

    public Intent getIntent() {
        throw new RuntimeException("stub");
    }

    public void finish() {
        throw new RuntimeException("stub");
    }

    public final Application getApplication() {
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

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#onResume
    protected void onResume() {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java#runOnUiThread
    public final void runOnUiThread(Runnable action) {
        throw new RuntimeException("stub");
    }
}
