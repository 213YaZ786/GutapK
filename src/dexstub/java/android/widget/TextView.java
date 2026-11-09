// Compile-time stub of android.widget.TextView, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setText
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#getText
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setTextSize
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setTextColor
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setTextIsSelectable
//
// not ported: TextView (the constructors with an AttributeSet, views are made in code)
// not ported: setText, setTextSize, setTextColor (the overloads with a buffer type, a unit, a resource or a ColorStateList)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;

public class TextView extends View {
    public TextView(Context context) {
        super(context);
    }

    public final void setText(CharSequence text) {
        throw new RuntimeException("stub");
    }

    public CharSequence getText() {
        throw new RuntimeException("stub");
    }

    public void setTextSize(float size) {
        throw new RuntimeException("stub");
    }

    public void setTextColor(int color) {
        throw new RuntimeException("stub");
    }

    public void setTextIsSelectable(boolean selectable) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setGravity
    public void setGravity(int gravity) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setTypeface
    public void setTypeface(Typeface tf) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setLineSpacing
    public void setLineSpacing(float add, float mult) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setAllCaps
    public void setAllCaps(boolean allCaps) {
        throw new RuntimeException("stub");
    }
}
