package io.gutapk.offline;

/*
 * Values the user changed, applied to the JSON answers of the game's server, live
 * or replayed. The pointers are RFC 6901 JSON Pointers, parsed the way Jackson's
 * JsonPointer does: segments split at '/', "~1" read as '/' and "~0" as '~', array
 * indexes without leading zeroes. The document is the platform's org.json.
 *
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#compile
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#_parseTail
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#L814-L838
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#_appendEscape
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#_appendEscaped
 * https://github.com/FasterXML/jackson-core/blob/2.x/src/main/java/com/fasterxml/jackson/core/JsonPointer.java#_parseIndex
 *
 * Not ported: _buildPath, PointerParent (a pointer is the list of its segments, not a chain of nested pointers)
 * Not ported: valueOf, empty (compile does both)
 *
 * Copyright (c) 2007- Tatu Saloranta, Apache License 2.0.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class Overrides {

    private final static char SEPARATOR = '/';

    /** Escape character */
    private final static char ESC = '~';

    private final static char ESC_SLASH = '1';
    private final static char ESC_TILDE = '0';

    /** One JSON leaf of an answer: where it sits, its type and its value. */
    public static class Leaf {
        public final String pointer;
        public final String type;
        public final Object value;

        Leaf(String pointer, String type, Object value) {
            this.pointer = pointer;
            this.type = type;
            this.value = value;
        }
    }

    private final File file;

    private JSONObject overrides = new JSONObject();

    public Overrides(File file) {
        this.file = file;
        if (file.isFile()) {
            try {
                overrides = new JSONObject(new String(Store.readAll(file), "UTF-8"));
            } catch (IOException e) {
                Server.LOG.warning("Could not read the overrides: " + e);
            } catch (JSONException e) {
                Server.LOG.warning("The overrides file is not JSON, ignored: " + e);
            }
        }
    }

    private void save() {
        try {
            Store.writeAside(file, overrides.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            Server.LOG.warning("Could not write the overrides: " + e);
        }
    }

    /**
     * Compile a pointer expression into its segments.
     *
     * @throws IllegalArgumentException Thrown if the input does not present a valid JSON Pointer
     *   expression: currently the only such expression is one that does NOT start with
     *   a slash ('/').
     */
    public static List<String> compile(String expr) throws IllegalArgumentException {
        // First quick checks for well-known 'empty' pointer
        if ((expr == null) || expr.length() == 0) {
            return new ArrayList<String>();
        }
        // And then quick validity check:
        if (expr.charAt(0) != SEPARATOR) {
            throw new IllegalArgumentException("Invalid input: JSON Pointer expression must start with '/': " + "\"" + expr + "\"");
        }
        return _parseTail(expr);
    }

    protected static List<String> _parseTail(final String fullPath) {
        List<String> segments = new ArrayList<String>();

        // first char is the contextual slash, skip
        int i = 1;
        final int end = fullPath.length();
        int startOffset = 0;

        while (i < end) {
            char c = fullPath.charAt(i);
            if (c == SEPARATOR) { // common case, got a segment
                segments.add(fullPath.substring(startOffset + 1, i));
                startOffset = i;
                ++i;
                continue;
            }
            ++i;
            // quoting is different; offline this case
            if (c == ESC && i < end) { // possibly, quote
                StringBuilder sb = new StringBuilder(32);
                i = _extractEscapedSegment(fullPath, startOffset + 1, i, sb);
                final String segment = sb.toString();
                if (i < 0) { // end!
                    segments.add(segment);
                    return segments;
                }
                segments.add(segment);
                startOffset = i;
                ++i;
                continue;
            }
            // otherwise, loop on
        }
        // end of the road, no escapes
        segments.add(fullPath.substring(startOffset + 1));
        return segments;
    }

    /**
     * Method called to extract the next segment of the path, in case
     * where we seem to have encountered a (tilde-)escaped character
     * within segment.
     *
     * @return Offset at which slash was encountered, if any, or -1
     *    if expression ended without seeing unescaped slash
     */
    protected static int _extractEscapedSegment(String input, int firstCharOffset, int i, StringBuilder sb) {
        final int end = input.length();
        final int toCopy = i - 1 - firstCharOffset;
        if (toCopy > 0) {
            sb.append(input, firstCharOffset, i - 1);
        }
        i += _appendEscape(sb, input.charAt(i));
        while (i < end) {
            char c = input.charAt(i);
            if (c == SEPARATOR) { // end is nigh!
                return i;
            }
            ++i;
            if (c == ESC && i < end) {
                i += _appendEscape(sb, input.charAt(i));
                continue;
            }
            sb.append(c);
        }
        // end of the road, last segment
        return -1;
    }

    private static int _appendEscape(StringBuilder sb, char c) {
        if (c == ESC_TILDE) {
            sb.append(ESC);
            return 1;
        }
        if (c == ESC_SLASH) {
            sb.append(SEPARATOR);
            return 1;
        }
        // Not a valid escape; just output tilde, do not advance past following char
        sb.append(ESC);
        return 0;
    }

    private static void _appendEscaped(StringBuilder sb, String segment) {
        for (int i = 0, end = segment.length(); i < end; ++i) {
            char c = segment.charAt(i);
            if (c == SEPARATOR) {
                sb.append(ESC).append(ESC_SLASH);
                continue;
            }
            if (c == ESC) {
                sb.append(ESC).append(ESC_TILDE);
                continue;
            }
            sb.append(c);
        }
    }

    private final static int _parseIndex(String str) {
        final int len = str.length();
        // beware of super long indexes; assume we never
        // have arrays over 2 billion entries so ints are fine.
        if (len == 0 || len > 10) {
            return -1;
        }
        // no leading zeroes allowed
        char c = str.charAt(0);
        if (c <= '0') {
            return (len == 1 && c == '0') ? 0 : -1;
        }
        if (c > '9') {
            return -1;
        }
        for (int i = 1; i < len; ++i) {
            c = str.charAt(i);
            if (c > '9' || c < '0') {
                return -1;
            }
        }
        if (len == 10) {
            long l = Long.parseLong(str);
            if (l > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) l;
        }
        return Integer.parseInt(str);
    }

    /**
     * Sets the value the pointer designates, in a parsed document. False when
     * the parent does not exist. The walk is Jackson's matchProperty and
     * matchElement along the segments.
     */
    public static boolean replace(Object node, List<String> segments, Object value) {
        if (segments.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            node = matchProperty(node, segments.get(i));
            if (node == null) {
                return false;
            }
        }
        String last = segments.get(segments.size() - 1);
        try {
            if (node instanceof JSONObject) {
                ((JSONObject) node).put(last, value == null ? JSONObject.NULL : value);
                return true;
            }
            if (node instanceof JSONArray) {
                int index = _parseIndex(last);
                if (index < 0 || index >= ((JSONArray) node).length()) {
                    return false;
                }
                ((JSONArray) node).put(index, value == null ? JSONObject.NULL : value);
                return true;
            }
        } catch (JSONException e) {
            return false;
        }
        return false;
    }

    private static Object matchProperty(Object node, String name) {
        if (node instanceof JSONObject) {
            return ((JSONObject) node).opt(name);
        }
        if (node instanceof JSONArray) {
            int index = _parseIndex(name);
            return index < 0 ? null : ((JSONArray) node).opt(index);
        }
        return null;
    }

    /** Every number, boolean and string of a document, with the pointer that designates it. */
    public static List<Leaf> leaves(Object node) {
        List<Leaf> out = new ArrayList<Leaf>();
        forPath(node, "", out);
        return out;
    }

    private static void forPath(Object node, String pointer, List<Leaf> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                StringBuilder sb = new StringBuilder(pointer).append(SEPARATOR);
                _appendEscaped(sb, key);
                forPath(object.opt(key), sb.toString(), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                forPath(array.opt(i), pointer + SEPARATOR + i, out);
            }
        } else if (node instanceof Number) {
            out.add(new Leaf(pointer, "number", node));
        } else if (node instanceof Boolean) {
            out.add(new Leaf(pointer, "bool", node));
        } else if (node instanceof String) {
            out.add(new Leaf(pointer, "string", node));
        }
    }

    /** The parsed JSON object or array in a body, or null for anything else. */
    public static Object parse(byte[] body, String contentType) {
        try {
            String text = new String(body, "UTF-8").trim();
            boolean json = contentType != null && contentType.toLowerCase().contains("json");
            if (!json && !text.startsWith("{") && !text.startsWith("[")) {
                return null;
            }
            Object node = new JSONTokener(text).nextValue();
            return node instanceof JSONObject || node instanceof JSONArray ? node : null;
        } catch (JSONException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Sets (or replaces) the override of a pointer on an endpoint, "METHOD origin/path". */
    public synchronized void set(String endpoint, String pointer, Object value) {
        compile(pointer);
        JSONArray list = overrides.optJSONArray(endpoint);
        try {
            if (list == null) {
                list = new JSONArray();
                overrides.put(endpoint, list);
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null && pointer.equals(item.optString("pointer"))) {
                    item.put("value", value == null ? JSONObject.NULL : value);
                    save();
                    return;
                }
            }
            JSONObject item = new JSONObject();
            item.put("pointer", pointer);
            item.put("value", value == null ? JSONObject.NULL : value);
            list.put(item);
            save();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The overridden value of a pointer, or null when none is set. */
    public synchronized Object get(String endpoint, String pointer) {
        JSONArray list = overrides.optJSONArray(endpoint);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && pointer.equals(item.optString("pointer"))) {
                return item.opt("value");
            }
        }
        return null;
    }

    public synchronized void reset() {
        overrides = new JSONObject();
        save();
    }

    public synchronized int count() {
        int total = 0;
        Iterator<String> keys = overrides.keys();
        while (keys.hasNext()) {
            JSONArray list = overrides.optJSONArray(keys.next());
            total += list == null ? 0 : list.length();
        }
        return total;
    }

    /**
     * The body with the overrides of its endpoint applied. A body that is not
     * JSON, or an endpoint without overrides, comes back as it is.
     */
    public synchronized byte[] apply(String endpoint, String contentType, byte[] body) {
        JSONArray list = overrides.optJSONArray(endpoint);
        if (list == null || list.length() == 0) {
            return body;
        }
        Object root = parse(body, contentType);
        if (root == null) {
            return body;
        }
        boolean matches = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Object value = item.opt("value");
            matches |= replace(root, compile(item.optString("pointer")), value == JSONObject.NULL ? null : value);
        }
        if (!matches) {
            return body;
        }
        try {
            return root.toString().getBytes("UTF-8");
        } catch (IOException e) {
            return body;
        }
    }
}
