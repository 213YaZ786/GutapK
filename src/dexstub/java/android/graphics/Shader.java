// Compile-time stub of android.graphics.Shader, never shipped: the edge
// treatment of the backdrop's blur.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Shader.java#L84-L120
//
// not ported: REPEAT, MIRROR, DECAL (the blur clamps at the screen's edges)
// not ported: Shader, getLocalMatrix, setLocalMatrix, createNativeInstance (never called by GutapK)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.graphics;

public class Shader {
    public enum TileMode {
        CLAMP
    }
}
