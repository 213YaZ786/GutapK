// Compile-time stub of android.content.ContentProvider: the signatures the offline
// cache server calls or overrides, with bodies that throw. It is the compile
// classpath of src/dex and never shipped, the phone has the real class.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1112-L1114
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1447
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1545-L1547
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1753
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1915
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L1990-L1991
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L2045-L2046
//
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package android.content;

import android.database.Cursor;
import android.net.Uri;

public abstract class ContentProvider {
    public final Context getContext() {
        throw new RuntimeException("stub");
    }

    public abstract boolean onCreate();

    public abstract Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder);

    public abstract String getType(Uri uri);

    public abstract Uri insert(Uri uri, ContentValues values);

    public abstract int delete(Uri uri, String selection, String[] selectionArgs);

    public abstract int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);
}
