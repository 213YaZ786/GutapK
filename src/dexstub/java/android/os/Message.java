// Compile-time stub of android.os.Message, never shipped: what, the data
// bundle and replyTo carry GodotService's protocol.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Message.java#obtain
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Message.java#getData
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.os;

public final class Message {
    public int what;

    public Messenger replyTo;

    public static Message obtain() {
        throw new RuntimeException("stub");
    }

    public Bundle getData() {
        throw new RuntimeException("stub");
    }
}
