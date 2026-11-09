// Compile-time stub of android.widget.ScrollView, never shipped: the Local
// screen scrolls on a small phone, and fills a tall one.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ScrollView.java#L224
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ScrollView.java#setFillViewport
//
// not ported: ScrollView (the constructors with an AttributeSet, views are made in code)
// not ported: fullScroll, smoothScrollTo, scrollTo, pageScroll and the rest (the screen is scrolled by hand)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.widget;

import android.content.Context;

public class ScrollView extends FrameLayout {
    public ScrollView(Context context) {
        super(context);
    }

    public void setFillViewport(boolean fillViewport) {
        throw new RuntimeException("stub");
    }
}
