// Compile-time stub of android.graphics.RenderEffect, never shipped: the blur
// of the Local screen's backdrop, API 31.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/RenderEffect.java#L85-L127
//
// not ported: createBlurEffect (the overload with an input effect, one blur is enough)
// not ported: createOffsetEffect, createBitmapEffect, createColorFilterEffect, createBlendModeEffect, createChainEffect, createShaderEffect, createRuntimeShaderEffect (never called by GutapK)
//
// Copyright (C) 2020 The Android Open Source Project, Apache License 2.0.
package android.graphics;

public final class RenderEffect {
    public static RenderEffect createBlurEffect(float radiusX, float radiusY, Shader.TileMode edgeTreatment) {
        throw new RuntimeException("stub");
    }
}
