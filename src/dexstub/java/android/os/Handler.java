// Compile-time stub of android.os.Handler, never shipped: the receiver of
// GodotService's replies, on the main looper.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Handler.java#L84-L89
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Handler.java#L176
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.os;

public class Handler {
    public interface Callback {
        boolean handleMessage(Message msg);
    }

    public Handler(Looper looper, Callback callback) {
        throw new RuntimeException("stub");
    }
}
