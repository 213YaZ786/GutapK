package io.gutapk.local;

/*
 * The Material You glass of the Local screen, as Kyant's liquid glass draws
 * its buttons over a backdrop, and GutapK's Modules kit its zones: frosted
 * panels with a light rim, capsule buttons with a tint or a surface colour,
 * over a blurred backdrop of colour halos. The colours are the phone's own,
 * from its wallpaper, on Android 12 and later (the system_accent and
 * system_neutral shades, found by name), a teal palette before. The backdrop
 * is blurred with a RenderEffect on Android 12 and later; before, its radial
 * gradients are soft enough alone.
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/colors.xml#L246-L322
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java#getIdentifier
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/GradientDrawable.java#setCornerRadius
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/RenderEffect.java#createBlurEffect
 *
 * Not ported: LiquidButton (a Composable: the Local screen's buttons are views, their capsule is drawn by capsule)
 * Not ported: vibrancy, lens, InteractiveHighlight, layerBlock, pressProgress (Compose's shaders and press motion: the Local screen is plain views, a backdrop blur and a tint)
 * Not ported: BlendMode.Hue (no blend on a view's background, the tint is drawn with alpha alone)
 * Not ported: the other shades of system_accent1..3 and system_neutral1..2 (the screen uses the Material 3 roles below)
 * Not ported: createBlurEffect (its body and the overload with an input effect: the phone's RenderEffect blurs, the stub only names it)
 * Not ported: createOffsetEffect, createBitmapEffect, createBlendModeEffect, createShaderEffect, createRuntimeShaderEffect, createChainEffect, createColorFilterEffect, getNativeInstance (one blur is enough)
 *
 * Copyright (c) Kyant, Apache License 2.0.
 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
 */

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

final class Glass {
    private static final int WHITE = 0xFFFFFFFF;
    /** RenderEffect and the system shades, Android 12. */
    private static final int S = 31;

    final boolean night;
    final float density;
    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int tertiary;
    final int surface;
    final int surfaceContainerLow;
    final int onSurface;
    final int onSurfaceVariant;
    final int errorContainer;
    final int onErrorContainer;

    private final Resources resources;

    /** The roles of Material 3 from the shades of the phone, light or night as its theme. */
    Glass(Resources resources) {
        this.resources = resources;
        night = (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        density = resources.getDisplayMetrics().density;
        if (night) {
            primary = getColor("system_accent1_200", 0xFF80D5C8);
            onPrimary = getColor("system_accent1_800", 0xFF003731);
            primaryContainer = getColor("system_accent1_700", 0xFF005048);
            onPrimaryContainer = getColor("system_accent1_100", 0xFF9CF2E4);
            tertiary = getColor("system_accent3_200", 0xFFB1C6FF);
            surface = getColor("system_neutral1_900", 0xFF0E1514);
            surfaceContainerLow = getColor("system_neutral1_800", 0xFF161D1C);
            onSurface = getColor("system_neutral1_100", 0xFFDDE4E2);
            onSurfaceVariant = getColor("system_neutral2_200", 0xFFBEC9C6);
            errorContainer = 0xFF93000A;
            onErrorContainer = 0xFFFFDAD6;
        } else {
            primary = getColor("system_accent1_600", 0xFF006A60);
            onPrimary = getColor("system_accent1_0", WHITE);
            primaryContainer = getColor("system_accent1_100", 0xFF9CF2E4);
            onPrimaryContainer = getColor("system_accent1_900", 0xFF00201C);
            tertiary = getColor("system_accent3_600", 0xFF4A6396);
            surface = getColor("system_neutral1_10", 0xFFF4FBF9);
            surfaceContainerLow = getColor("system_neutral1_50", 0xFFEFF5F3);
            onSurface = getColor("system_neutral1_900", 0xFF161D1C);
            onSurfaceVariant = getColor("system_neutral2_700", 0xFF3F4947);
            errorContainer = 0xFFFFDAD6;
            onErrorContainer = 0xFF410002;
        }
    }

    /** The phone's colour of that name, the fallback before Android 12 or when it has none. */
    private int getColor(String name, int fallback) {
        if (Build.VERSION.SDK_INT < S) {
            return fallback;
        }
        int id = resources.getIdentifier(name, "color", "android");
        return id == 0 ? fallback : resources.getColor(id);
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    /** color with the opacity alpha, from 0 to 1, as Color.copy(alpha) gives it. */
    static int copy(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** A rounded rectangle of surfaceColor, with a rim of colour rim when it is not 0. */
    GradientDrawable shape(int surfaceColor, float radius, int rim) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(surfaceColor);
        if (rim != 0) {
            drawable.setStroke(Math.max(1, dp(1)), rim);
        }
        return drawable;
    }

    /** A frosted panel: white glass by day, the dim surface by night, behind a light rim. */
    GradientDrawable zone() {
        return shape(night ? copy(surfaceContainerLow, 0.40f) : copy(WHITE, 0.42f), 24, rim());
    }

    /** A panel inside a zone, of the accent's tint. */
    GradientDrawable tint() {
        return shape(night ? copy(primary, 0.22f) : copy(primaryContainer, 0.55f), 16, 0);
    }

    /** A capsule of surfaceColor, a glass one with a rim when glass is set. */
    GradientDrawable capsule(int surfaceColor, boolean glass) {
        return shape(surfaceColor, 999, glass ? rim() : 0);
    }

    private int rim() {
        return night ? copy(WHITE, 0.14f) : copy(WHITE, 0.75f);
    }

    /**
     * The backdrop the panels float over: the surface and four halos of the
     * accent colours, each at an offset of the screen fading out at a scale of
     * its width, blurred together on Android 12 and later.
     */
    View backdrop(Context context) {
        FrameLayout backdrop = new FrameLayout(context);
        backdrop.setBackgroundColor(surface);
        float width = resources.getDisplayMetrics().widthPixels;
        float progress = night ? 0.6f : 1f;
        int[] tint = {copy(primary, 0.35f * progress), copy(tertiary, 0.30f * progress), copy(primaryContainer, 0.80f * progress), copy(primary, 0.22f * progress)};
        float[][] offset = {{0.15f, 0.18f}, {0.95f, 0.45f}, {0.25f, 0.85f}, {0.80f, 0.95f}};
        float[] scale = {0.75f, 0.75f, 0.75f, 0.45f};
        for (int i = 0; i < tint.length; i++) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            drawable.setGradientCenter(offset[i][0], offset[i][1]);
            drawable.setGradientRadius(width * scale[i]);
            drawable.setColors(new int[]{tint[i], tint[i] & 0x00FFFFFF});
            View view = new View(context);
            view.setBackground(drawable);
            backdrop.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (Build.VERSION.SDK_INT >= S) {
            float blur = dp(40);
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
        return backdrop;
    }
}
