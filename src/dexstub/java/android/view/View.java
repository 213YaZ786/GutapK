// Compile-time stub of android.view.View, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#L5853
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#L17297
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#L23000
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#L1152-L1166
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setOnClickListener
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setVisibility
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setPadding
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setBackgroundColor
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#OnClickListener
//
// not ported: View (the constructors with an AttributeSet, views are made in code)
// not ported: INVISIBLE (GutapK hides with GONE)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.view;

import android.content.Context;

public class View {
    public static final int VISIBLE = 0x00000000;
    public static final int GONE = 0x00000008;

    public interface OnClickListener {
        void onClick(View v);
    }

    public View(Context context) {
        throw new RuntimeException("stub");
    }

    public final Context getContext() {
        throw new RuntimeException("stub");
    }

    public Display getDisplay() {
        throw new RuntimeException("stub");
    }

    public void setOnClickListener(OnClickListener l) {
        throw new RuntimeException("stub");
    }

    public void setVisibility(int visibility) {
        throw new RuntimeException("stub");
    }

    public void setPadding(int left, int top, int right, int bottom) {
        throw new RuntimeException("stub");
    }

    public void setBackgroundColor(int color) {
        throw new RuntimeException("stub");
    }
}
