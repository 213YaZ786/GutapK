// Compile-time stub of android.net.LocalSocket, never shipped.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocket.java#getPeerCredentials
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocket.java#L205-L206
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocket.java#L228-L229
//
// Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
package android.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class LocalSocket implements Closeable {
    public InputStream getInputStream() throws IOException {
        throw new RuntimeException("stub");
    }

    public OutputStream getOutputStream() throws IOException {
        throw new RuntimeException("stub");
    }

    @Override
    public void close() throws IOException {
        throw new RuntimeException("stub");
    }

    public Credentials getPeerCredentials() throws IOException {
        throw new RuntimeException("stub");
    }
}
