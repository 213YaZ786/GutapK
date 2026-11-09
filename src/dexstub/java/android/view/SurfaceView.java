// Compile-time stub of android.view.SurfaceView, never shipped: the window
// the second engine draws into, through a SurfaceControlViewHost.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java#L436
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java#L466
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java#L838
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java#L2087
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java#L2164
//
// not ported: SurfaceView (the constructors with an AttributeSet, the view is made in code)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

import android.content.Context;
import android.os.IBinder;

public class SurfaceView extends View {
    public SurfaceView(Context context) {
        super(context);
    }

    public SurfaceHolder getHolder() {
        throw new RuntimeException("stub");
    }

    public void setZOrderOnTop(boolean onTop) {
        throw new RuntimeException("stub");
    }

    public IBinder getHostToken() {
        throw new RuntimeException("stub");
    }

    public void setChildSurfacePackage(SurfaceControlViewHost.SurfacePackage p) {
        throw new RuntimeException("stub");
    }
}
