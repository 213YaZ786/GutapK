// Compile-time stub of android.os.Build, never shipped: the SDK level the
// local host checks before using SurfaceControlViewHost (API 30).
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Build.java#L335
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Build.java#L412
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.os;

public class Build {
    public static class VERSION {
        // Not a constant expression: javac would copy a literal into the
        // calling code instead of reading the phone's field.
        public static final int SDK_INT = Integer.parseInt("0");
    }
}
