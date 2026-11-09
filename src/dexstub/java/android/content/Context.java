// Compile-time stub of android.content.Context, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#L801
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#L1413
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#getApplicationContext
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#bindService
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#startActivity
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#getPackageName
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.io.File;

public abstract class Context {
    public static final int BIND_AUTO_CREATE = 0x0001;

    public abstract AssetManager getAssets();

    public abstract File getFilesDir();

    public abstract Context getApplicationContext();

    public abstract String getPackageName();

    public abstract boolean bindService(Intent service, ServiceConnection conn, int flags);

    public abstract void unbindService(ServiceConnection conn);

    public abstract void startActivity(Intent intent);

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#getApplicationInfo
    public abstract ApplicationInfo getApplicationInfo();

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#getResources
    public abstract Resources getResources();
}
