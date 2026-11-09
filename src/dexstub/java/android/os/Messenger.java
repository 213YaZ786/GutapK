// Compile-time stub of android.os.Messenger, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Messenger.java#Messenger
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Messenger.java#send
//
// not ported: getBinder, equals, hashCode, describeContents, writeToParcel, createFromParcel, newArray, readMessengerOrNullFromParcel, writeMessengerOrNullToParcel (the local host only sends through it)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.os;

public final class Messenger {
    public Messenger(Handler target) {
        throw new RuntimeException("stub");
    }

    public Messenger(IBinder target) {
        throw new RuntimeException("stub");
    }

    public void send(Message message) throws RemoteException {
        throw new RuntimeException("stub");
    }
}
