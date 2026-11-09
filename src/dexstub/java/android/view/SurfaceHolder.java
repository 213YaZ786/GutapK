// Compile-time stub of android.view.SurfaceHolder, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceHolder.java#L72-L107
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceHolder.java#L159
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

public interface SurfaceHolder {
    interface Callback {
        void surfaceCreated(SurfaceHolder holder);

        void surfaceChanged(SurfaceHolder holder, int format, int width, int height);

        void surfaceDestroyed(SurfaceHolder holder);
    }

    void addCallback(Callback callback);
}
