// Compile-time stub of android.graphics.Bitmap, never shipped: the picture
// of the Local screen's QR code.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Bitmap.java#createBitmap
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Bitmap.java#setPixels
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Bitmap.java#L483-L560
//
// not ported: createBitmap (the overloads with a display, colors, a matrix or a source bitmap, one size is enough)
// not ported: Config (ALPHA_8, RGB_565, ARGB_4444, RGBA_F16, HARDWARE, RGBA_1010102 and nativeInt: one config is used)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.graphics;

public final class Bitmap {
    public enum Config {
        ARGB_8888
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        throw new RuntimeException("stub");
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        throw new RuntimeException("stub");
    }
}
