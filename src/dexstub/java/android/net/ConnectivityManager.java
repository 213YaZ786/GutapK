// Compile-time stub of android.net.ConnectivityManager, never shipped: the
// calls an offline build answers in the game's place.
//
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/ConnectivityManager.java#L584
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/ConnectivityManager.java#getActiveNetworkInfo
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/ConnectivityManager.java#getNetworkInfo
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/ConnectivityManager.java#getAllNetworkInfo
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/ConnectivityManager.java#isActiveNetworkMetered
//
// Copyright (C) 2008 The Android Open Source Project, Apache License 2.0.
package android.net;

public class ConnectivityManager {
    // Not a constant expression, as Build.VERSION.SDK_INT: the phone's field is read.
    public static final int TYPE_WIFI = Integer.parseInt("1");

    public NetworkInfo getActiveNetworkInfo() {
        throw new RuntimeException("stub");
    }

    public NetworkInfo getNetworkInfo(int networkType) {
        throw new RuntimeException("stub");
    }

    public NetworkInfo[] getAllNetworkInfo() {
        throw new RuntimeException("stub");
    }

    public boolean isActiveNetworkMetered() {
        throw new RuntimeException("stub");
    }
}
