// Compile-time stub of android.os.Bundle and the BaseBundle calls on it,
// never shipped: the payload of GodotService's messages.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Bundle.java#putBinder
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Bundle.java#getParcelable
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BaseBundle.java#putInt
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BaseBundle.java#putStringArray
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BaseBundle.java#getString
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.os;

public final class Bundle {
    public void putBinder(String key, IBinder value) {
        throw new RuntimeException("stub");
    }

    public <T extends Parcelable> T getParcelable(String key) {
        throw new RuntimeException("stub");
    }

    public void putInt(String key, int value) {
        throw new RuntimeException("stub");
    }

    public void putStringArray(String key, String[] value) {
        throw new RuntimeException("stub");
    }

    public String getString(String key) {
        throw new RuntimeException("stub");
    }
}
