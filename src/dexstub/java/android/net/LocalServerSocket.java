// Compile-time stub of android.net.LocalServerSocket, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalServerSocket.java#accept
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalServerSocket.java#L42-L44
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalServerSocket.java#L112-L114
//
// Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
package android.net;

import java.io.Closeable;
import java.io.IOException;

public class LocalServerSocket implements Closeable {
    public LocalServerSocket(String name) throws IOException {
        throw new RuntimeException("stub");
    }

    public LocalSocket accept() throws IOException {
        throw new RuntimeException("stub");
    }

    @Override
    public void close() throws IOException {
        throw new RuntimeException("stub");
    }
}
