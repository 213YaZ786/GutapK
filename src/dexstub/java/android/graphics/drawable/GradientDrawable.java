// Compile-time stub of android.graphics.drawable.GradientDrawable, never
// shipped: the Local screen's glass panels, pill buttons and colour halos.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#L111-L141
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setCornerRadius
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setStroke
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setShape
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setGradientType
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setGradientCenter
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setGradientRadius
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setColors
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setColor
//
// not ported: GradientDrawable (the constructor with an orientation and colors, the colors are set after)
// not ported: LINE, RING, LINEAR_GRADIENT, SWEEP_GRADIENT, Orientation (rectangles and radial halos only)
// not ported: setCornerRadii, setStroke, setColor, setColors (the overloads with dashes, a ColorStateList or offsets)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.graphics.drawable;

public class GradientDrawable extends Drawable {
    public static final int RECTANGLE = 0;
    public static final int OVAL = 1;
    public static final int RADIAL_GRADIENT = 1;

    public GradientDrawable() {
        throw new RuntimeException("stub");
    }

    public void setCornerRadius(float radius) {
        throw new RuntimeException("stub");
    }

    public void setStroke(int width, int color) {
        throw new RuntimeException("stub");
    }

    public void setShape(int shape) {
        throw new RuntimeException("stub");
    }

    public void setGradientType(int gradient) {
        throw new RuntimeException("stub");
    }

    public void setGradientCenter(float x, float y) {
        throw new RuntimeException("stub");
    }

    public void setGradientRadius(float gradientRadius) {
        throw new RuntimeException("stub");
    }

    public void setColors(int[] colors) {
        throw new RuntimeException("stub");
    }

    public void setColor(int argb) {
        throw new RuntimeException("stub");
    }
}
