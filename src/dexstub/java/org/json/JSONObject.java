// Compile-time stub of the platform's org.json.JSONObject, never shipped. The
// signatures, checked exceptions included, are Android's: on the JVM the tests
// run against the reference org.json instead.
//
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L108-L120
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L125
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L184
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L206
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L216
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L240
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L251
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L267
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L377
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L386
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L395
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L407
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L558
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L571
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L605
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L630
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L663
// https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java#L699
//
// Not ported: JSONObject (the constructors are declared by the class)
//
// Copyright (C) 2010 The Android Open Source Project, Apache License 2.0.
package org.json;

import java.util.Iterator;

public class JSONObject {
    public static final Object NULL = new Object();

    public JSONObject() {
        throw new RuntimeException("stub");
    }

    public JSONObject(String json) throws JSONException {
        throw new RuntimeException("stub");
    }

    public int length() {
        throw new RuntimeException("stub");
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        throw new RuntimeException("stub");
    }

    public JSONObject put(String name, int value) throws JSONException {
        throw new RuntimeException("stub");
    }

    public JSONObject put(String name, long value) throws JSONException {
        throw new RuntimeException("stub");
    }

    public JSONObject put(String name, Object value) throws JSONException {
        throw new RuntimeException("stub");
    }

    public boolean isNull(String name) {
        throw new RuntimeException("stub");
    }

    public boolean has(String name) {
        throw new RuntimeException("stub");
    }

    public Object get(String name) throws JSONException {
        throw new RuntimeException("stub");
    }

    public Object opt(String name) {
        throw new RuntimeException("stub");
    }

    public String getString(String name) throws JSONException {
        throw new RuntimeException("stub");
    }

    public String optString(String name) {
        throw new RuntimeException("stub");
    }

    public JSONArray optJSONArray(String name) {
        throw new RuntimeException("stub");
    }

    public JSONObject optJSONObject(String name) {
        throw new RuntimeException("stub");
    }

    public Iterator<String> keys() {
        throw new RuntimeException("stub");
    }

    @Override
    public String toString() {
        throw new RuntimeException("stub");
    }
}
