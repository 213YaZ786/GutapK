// Compile-time stub of the platform's org.json.JSONException, never shipped. It is
// a checked exception on Android, so the server code must catch it.
//
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONException.java#L44-L48
//
// Copyright (C) 2010 The Android Open Source Project, Apache License 2.0.
package org.json;

public class JSONException extends Exception {
    private static final long serialVersionUID = 1L;

    public JSONException(String s) {
        super(s);
    }
}
