// Compile-time stub of android.content.Intent, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java#setComponent
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java#getData
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java#addFlags
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content;

import android.net.Uri;

public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    public Intent() {
        throw new RuntimeException("stub");
    }

    public Intent setComponent(ComponentName component) {
        throw new RuntimeException("stub");
    }

    public Intent addFlags(int flags) {
        throw new RuntimeException("stub");
    }

    public Uri getData() {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java#setAction
    public Intent setAction(String action) {
        throw new RuntimeException("stub");
    }

    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java#setData
    public Intent setData(Uri data) {
        throw new RuntimeException("stub");
    }
}
