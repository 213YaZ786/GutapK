// Compile-time stub of android.content.res.Resources, never shipped: the
// system's Material You colours, found by name, and the screen's density and
// night mode.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getIdentifier
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getColor
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getDisplayMetrics
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getConfiguration
//
// not ported: getColor (the overload with a Theme: the system colours are the same in every theme)
// not ported: Theme, getString, getDrawable, getDimension and the rest (never called by GutapK)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    public int getIdentifier(String name, String defType, String defPackage) {
        throw new RuntimeException("stub");
    }

    public int getColor(int id) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getString
    public String getString(int id) {
        throw new RuntimeException("stub");
    }

    public DisplayMetrics getDisplayMetrics() {
        throw new RuntimeException("stub");
    }

    public Configuration getConfiguration() {
        throw new RuntimeException("stub");
    }
}
