// Compile-time stub of android.widget.LinearLayout, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#L97
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#setOrientation
//
// not ported: LinearLayout (the constructors with an AttributeSet, views are made in code)
// not ported: LayoutParams (the constructors with a weight, from attributes or another LayoutParams, a size is enough)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class LinearLayout extends ViewGroup {
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#L96
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#setGravity
    // not ported: layoutParams (the stub has no body, the phone's LinearLayout reads the gravity)
    public void setGravity(int gravity) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#L2113
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
        }
    }
}
