// Compile-time stub of android.view.WindowManager, never shipped: the flag
// that keeps the host's screen on while friends join.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java#L1991
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java#L2800
//
// not ported: LayoutParams (its constructors and every field but FLAG_KEEP_SCREEN_ON, a flag is all GutapK sets)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

public interface WindowManager {
    class LayoutParams extends ViewGroup.LayoutParams {
        public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;

        public LayoutParams() {
            super(MATCH_PARENT, MATCH_PARENT);
        }
    }
}
