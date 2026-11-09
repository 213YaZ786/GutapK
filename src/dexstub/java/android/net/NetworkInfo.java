// Compile-time stub of android.net.NetworkInfo, never shipped: what the
// offline build's network answer makes and reads.
//
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#L76-L100
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#L167-L179
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#getType
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#isConnected
// https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/framework/src/android/net/NetworkInfo.java#setDetailedState
// not ported: NetworkInfo(NetworkInfo), isConnectedOrConnecting, isAvailable, setIsAvailable, isFailover, setFailover, isRoaming, setRoaming, getState, getReason, getExtraInfo, setExtraInfo (a stub holds only what the offline build calls)
// not ported: getTypeName, getSubtype, getSubtypeName, setSubtype, isSuspended, toString, toShortString (same)
// not ported: describeContents, writeToParcel, CREATOR, createFromParcel, newArray (never parcelled here)
//
// Copyright (C) 2008 The Android Open Source Project, Apache License 2.0.
package android.net;

public class NetworkInfo {
    public enum State {
        CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING, DISCONNECTED, UNKNOWN
    }

    public enum DetailedState {
        IDLE, SCANNING, CONNECTING, AUTHENTICATING, OBTAINING_IPADDR, CONNECTED, SUSPENDED, DISCONNECTING, DISCONNECTED, FAILED, BLOCKED, VERIFYING_POOR_LINK, CAPTIVE_PORTAL_CHECK
    }

    public NetworkInfo(int type, int subtype, String typeName, String subtypeName) {
        throw new RuntimeException("stub");
    }

    public int getType() {
        throw new RuntimeException("stub");
    }

    public boolean isConnected() {
        throw new RuntimeException("stub");
    }

    public void setDetailedState(DetailedState detailedState, String reason, String extraInfo) {
        throw new RuntimeException("stub");
    }
}
