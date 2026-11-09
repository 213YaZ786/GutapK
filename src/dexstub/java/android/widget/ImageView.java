// Compile-time stub of android.widget.ImageView, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ImageView.java#setImageBitmap
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ImageView.java#setAdjustViewBounds
//
// not ported: ImageView (the constructors with an AttributeSet, views are made in code)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;

public class ImageView extends View {
    public ImageView(Context context) {
        super(context);
    }

    public void setImageBitmap(Bitmap bm) {
        throw new RuntimeException("stub");
    }

    public void setAdjustViewBounds(boolean adjustViewBounds) {
        throw new RuntimeException("stub");
    }
}
