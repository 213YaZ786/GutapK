// Compile-time stub of android.view.Window, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Window.java#L2087
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Window.java#addFlags
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Window.java#clearFlags
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

public abstract class Window {
    public abstract View getDecorView();

    public void addFlags(int flags) {
        throw new RuntimeException("stub");
    }

    public void clearFlags(int flags) {
        throw new RuntimeException("stub");
    }
}
