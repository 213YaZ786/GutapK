// Compile-time stub of android.content.res.Configuration, never shipped:
// whether the phone is in its night theme.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Configuration.java#L770-L798
//
// not ported: UI_MODE_NIGHT_UNDEFINED, UI_MODE_NIGHT_NO, UI_MODE_TYPE_MASK and the other fields (only the night bits are read)
//
// Copyright (C) 2008 The Android Open Source Project, Apache License 2.0.
package android.content.res;

public final class Configuration {
    public static final int UI_MODE_NIGHT_MASK = 0x30;
    public static final int UI_MODE_NIGHT_YES = 0x20;

    public int uiMode;
}
