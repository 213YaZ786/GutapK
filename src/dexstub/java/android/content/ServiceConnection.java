// Compile-time stub of android.content.ServiceConnection, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ServiceConnection.java#onServiceConnected
//
// not ported: onBindingDied, onNullBinding (default methods, the abstract two are enough to implement it)
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content;

import android.os.IBinder;

public interface ServiceConnection {
    void onServiceConnected(ComponentName name, IBinder service);

    void onServiceDisconnected(ComponentName name);
}
