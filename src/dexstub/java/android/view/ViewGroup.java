// Compile-time stub of android.view.ViewGroup, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#L702
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#L5087
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#L8296
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#L8393
//
// not ported: ViewGroup (the constructors with an AttributeSet, views are made in code)
// not ported: LayoutParams (the constructors from attributes or another LayoutParams, a size is enough)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

import android.content.Context;

public abstract class ViewGroup extends View {
    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View child, LayoutParams params) {
        throw new RuntimeException("stub");
    }

    public static class LayoutParams {
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#L8312-L8319
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public LayoutParams(int width, int height) {
            throw new RuntimeException("stub");
        }
    }
}
