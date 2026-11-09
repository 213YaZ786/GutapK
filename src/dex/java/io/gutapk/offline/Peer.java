package io.gutapk.offline;

/*
 * The credentials of the process on the other end of the admin socket, and the
 * rule that decides who may talk to it. Credentials is android.net.Credentials,
 * kept as plain Java so the rule can be tested on the JVM.
 *
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/Credentials.java
 *
 * Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
 */
public class Peer {
    /** The uid of the shell user, which adbd runs as: what "adb forward" connects through. */
    public static final int ADB_UID = 2000;

    private final int pid;
    private final int uid;
    private final int gid;

    public Peer(int pid, int uid, int gid) {
        this.pid = pid;
        this.uid = uid;
        this.gid = gid;
    }

    public int getPid() {
        return pid;
    }

    public int getUid() {
        return uid;
    }

    public int getGid() {
        return gid;
    }

    /** Only adbd and the app itself, since any app on the phone can open an abstract socket. */
    public boolean isAllowed(int ownUid) {
        return uid == ADB_UID || uid == ownUid;
    }
}
