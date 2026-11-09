// Compile-time stub of android.graphics.Typeface, never shipped: the medium
// weight of the Local screen's titles and buttons, the monospace of its link.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Typeface.java#L107-L113
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Typeface.java#L229-L230
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Typeface.java#L931
//
// not ported: DEFAULT, SANS_SERIF, SERIF, ITALIC, BOLD_ITALIC (a family name and NORMAL are enough)
// not ported: create (the overloads with a family typeface or a weight, API 28)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.graphics;

public class Typeface {
    // Read on the phone, never copied by javac: null is no constant expression.
    public static final Typeface MONOSPACE = null;
    public static final int NORMAL = 0;
    public static final int BOLD = 1;

    public static Typeface create(String familyName, int style) {
        throw new RuntimeException("stub");
    }
}
