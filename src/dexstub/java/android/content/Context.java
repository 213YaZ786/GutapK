// Compile-time stub of android.content.Context, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#L801
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Context.java#L1413
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content;

import android.content.res.AssetManager;

import java.io.File;

public abstract class Context {
    public abstract AssetManager getAssets();

    public abstract File getFilesDir();
}
