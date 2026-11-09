// Compile-time stub of android.widget.FrameLayout, never shipped: an
// activity's decor view is one.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/FrameLayout.java#L84
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/FrameLayout.java#L429
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/FrameLayout.java#L459
//
// not ported: FrameLayout (the constructors with an AttributeSet, the layout is the activity's own)
// not ported: LayoutParams (the constructors with a gravity, from attributes or another LayoutParams, a size is enough)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class FrameLayout extends ViewGroup {
    public FrameLayout(Context context) {
        super(context);
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
