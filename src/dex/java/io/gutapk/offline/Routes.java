package io.gutapk.offline;

/*
 * The admin routes: a table of paths to handlers, the way NanoHTTPD's
 * RouterNanoHTTPD maps an uri to a UriResponder by method. Routes here are
 * exact paths, so the url patterns, the priorities and the file handlers of
 * the router are left out.
 *
 * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#normalizeUri
 * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#L376-L410
 * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#L586-L598
 * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#L642-L651
 * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#L670-L674
 *
 * The Host and Origin header rule of webpack-dev-server, which keeps a web page of the user's browser from reaching a
 * server on 127.0.0.1 (a cross-site request, or a rebound DNS name)
 * https://github.com/webpack/webpack-dev-server/blob/main/lib/Server.js#isValidHost
 * https://github.com/webpack/webpack-dev-server/blob/main/lib/Server.js#parseHostnameFromHeader
 *
 * Not ported: isValidHost allowedHosts ("all" and the list: the hosts allowed are fixed to IP literals and localhost) and options.host
 * (the address listened on is not known here, and it is an IP literal)
 * Not ported: DEFAULT_ALLOWED_PROTOCOLS (file: and browser extension origins: no caller of ours, they get the hostname rule)
 * Not ported: isHostAllowed, isSameOrigin, isOriginAllowed, checkCrossOriginRequest, isUserCORSWildcardEnabled, the :authority header,
 * the Sec-Fetch-Mode and Sec-Fetch-Site tests (allowedOrigins and CORS options; the x-gutapk-admin header does their work)
 * Not ported: the websocket upgrade, the hot and client paths of Server.js (no websocket, no injected client here)
 * Not ported: toString, getUri, initParameter, match, compareTo, setPriority, createUriPattern, parse (url patterns and priorities)
 * Not ported: removeRoute, setRoutePrioritizer, setNotImplemented, setNotImplementedHandler (the table is fixed)
 *
 * Copyright (C) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class Routes implements Http.Handler {

    /** What one route answers, by method. A method it does not handle is a 405. */
    public static abstract class UriResponder {
        public Http.Response get(Http session) {
            return Routes.notAllowed("GET");
        }

        public Http.Response post(Http session) {
            return Routes.notAllowed("POST");
        }

        public Http.Response put(Http session) {
            return Routes.notAllowed("PUT");
        }

        public Http.Response delete(Http session) {
            return Routes.notAllowed("DELETE");
        }

        public Http.Response other(String method, Http session) {
            return Routes.notAllowed(method);
        }
    }

    private final Map<String, UriResponder> router = new HashMap<String, UriResponder>();

    private final Store cache;

    private final Overrides overrides;

    private final Map<Integer, String> origins;

    /** The local game server whose state /state reads and replaces, null when the app has none. */
    private final GameServer game;

    public Routes(Store cache, Overrides overrides, Map<Integer, String> origins) {
        this(cache, overrides, origins, null);
    }

    public Routes(Store cache, Overrides overrides, Map<Integer, String> origins, GameServer game) {
        this.cache = cache;
        this.overrides = overrides;
        this.origins = origins;
        this.game = game;
        addMappings();
    }

    /**
     * default routings
     */
    public void addMappings() {
        addRoute("/status", new StatusHandler());
        addRoute("/entries", new EntriesHandler());
        addRoute("/values", new ValuesHandler());
        addRoute("/set", new SetHandler());
        addRoute("/reset-overrides", new ResetHandler());
        addRoute("/clear", new ClearHandler());
        addRoute("/export", new ExportHandler());
        addRoute("/state", new StateHandler());
    }

    public void addRoute(String url, UriResponder handler) {
        router.put(normalizeUri(url), handler);
    }

    public static String normalizeUri(String value) {
        if (value == null) {
            return value;
        }
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** The header the PC client sends. A page cannot add it to a cross-site request without a preflight, which nothing here answers. */
    static final String ADMIN_HEADER = "x-gutapk-admin";

    static final String ADMIN_HEADER_VALUE = "1";

    /**
     * Extracts and normalizes the hostname from a header, removing brackets for IPv6.
     * https://github.com/webpack/webpack-dev-server/blob/main/lib/Server.js#parseHostnameFromHeader
     *
     * @param header header value
     * @return hostname or null
     */
    static String parseHostnameFromHeader(String header) {
        if (header == null || header.isEmpty()) return null;

        // If the header does not have a scheme, prepend // so it parses as an authority
        String parseUrl = header.matches("^(.+:)?//.*") ? header : "//" + header;
        // new URL(parseUrl, "http://localhost/").hostname, by hand: java.net.URL does not read an authority alone
        String authority = parseUrl.substring(parseUrl.indexOf("//") + 2);
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authority = authority.substring(0, i);
                break;
            }
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }

        String hostname;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return null;
            hostname = authority.substring(0, close + 1);
            port = authority.substring(close + 1);
            if (!port.isEmpty() && !port.startsWith(":")) return null;
        } else {
            int colon = authority.indexOf(':');
            hostname = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? "" : authority.substring(colon);
        }
        if (!port.isEmpty() && !port.substring(1).matches("[0-9]*")) return null;
        // A special scheme has no empty host
        if (hostname.isEmpty()) return null;

        hostname = hostname.toLowerCase(Locale.US);
        // Normalize IPv6: remove brackets if present
        if (hostname.startsWith("[") && hostname.endsWith("]")) {
            hostname = hostname.substring(1, hostname.length() - 1);
        }

        return hostname;
    }

    /** ipaddr.IPv4.isValid, the dotted form only: four numbers of 0 to 255. */
    private static boolean isIPv4(String hostname) {
        String[] parts = hostname.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** ipaddr.IPv6.isValid. The characters are checked first so that no name is ever looked up. */
    private static boolean isIPv6(String hostname) {
        if (!hostname.matches("[0-9a-f:.]+") || hostname.indexOf(':') < 0) return false;
        try {
            return java.net.InetAddress.getByName(hostname) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /**
     * True when the Host (or Origin) header names an IP address, localhost or a subdomain of it, whatever the port.
     * https://github.com/webpack/webpack-dev-server/blob/main/lib/Server.js#isValidHost
     *
     * @param headers headers, their names in lower case
     * @param headerToCheck header to check
     * @param validateHost need to validate host
     * @return true when host is valid, otherwise false
     */
    static boolean isValidHost(Map<String, String> headers, String headerToCheck, boolean validateHost) {
        boolean isOrigin = "origin".equals(headerToCheck);

        // get the Host header and extract hostname
        // we don't care about port not matching
        String header = headers.get(headerToCheck);

        if (header == null || header.isEmpty()) {
            return false;
        }

        // Host and :authority contain a hostname and optional port, never a URL.
        if (!isOrigin && header.matches("(?s).*[/\\\\?#@\\s].*")) {
            return false;
        }

        String hostname = parseHostnameFromHeader(header);

        if (hostname == null) {
            return false;
        }

        // always allow requests with explicit IPv4 or IPv6-address.
        // A note on IPv6 addresses:
        // header will always contain the brackets denoting
        // an IPv6-address in URLs,
        // these are removed from the hostname by parseHostnameFromHeader.
        // For convenience, always allow localhost (hostname == "localhost")
        // and its subdomains (hostname.endsWith(".localhost")).
        boolean isValidHostname = validateHost
            ? isIPv4(hostname)
                || isIPv6(hostname)
                || hostname.equals("localhost")
                || hostname.endsWith(".localhost")
            : false;

        return isValidHostname;
    }

    @Override
    public Http.Response handle(Http session) throws IOException {
        return serve(session);
    }

    public Http.Response serve(Http session) {
        // Try to find match
        return process(session);
    }

    public Http.Response process(Http session) {
        // The PC reaches this through adb forward on 127.0.0.1, where a page in the user's browser can reach it
        // too: a simple no-cors POST to /clear, or a read of /state through a rebound DNS name. The Host and the
        // Origin must name this machine, and the PC client adds a header no page can send without a preflight.
        Map<String, String> headers = session.getHeaders();
        if (!isValidHost(headers, "host", true)) {
            return json(403, error("forbidden", "Invalid Host header"));
        }
        if (headers.containsKey("origin") && !isValidHost(headers, "origin", true)) {
            return json(403, error("forbidden", "Cross-Origin request blocked"));
        }
        if (!ADMIN_HEADER_VALUE.equals(headers.get(ADMIN_HEADER))) {
            return json(403, error("forbidden", "Missing header " + ADMIN_HEADER + ": " + ADMIN_HEADER_VALUE));
        }
        String work = normalizeUri(session.getUri());
        UriResponder responder = router.get(work);
        if (responder == null) {
            return json(404, error("not found", "Routes: /status /entries /values /set /reset-overrides /clear /export /state"));
        }
        String error = "General error!";
        try {
            switch (session.getMethod()) {
                case "GET":
                    return responder.get(session);
                case "POST":
                    return responder.post(session);
                case "PUT":
                    return responder.put(session);
                case "DELETE":
                    return responder.delete(session);
                default:
                    return responder.other(session.getMethod(), session);
            }
        } catch (Exception e) {
            error = "Error: " + e.getClass().getName() + " : " + e.getMessage();
            Server.LOG.warning(error);
        }
        return json(500, error("internal", error));
    }

    static Http.Response notAllowed(String method) {
        return json(405, error("method not allowed", method + " is not served here"));
    }

    static JSONObject error(String error, String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("error", error);
            out.put("message", message);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    static Http.Response json(int status, Object body) {
        return Http.Response.newFixedLengthResponse(status, "application/json", String.valueOf(body));
    }

    private class StatusHandler extends UriResponder {
        @Override
        public Http.Response get(Http session) {
            try {
                JSONObject out = new JSONObject();
                out.put("mode", CacheInterceptor.lastSeen);
                out.put("entries", cache.urls().size());
                out.put("overrides", overrides.count());
                JSONObject ports = new JSONObject();
                for (Map.Entry<Integer, String> origin : new TreeMap<Integer, String>(origins).entrySet()) {
                    ports.put(String.valueOf(origin.getKey()), origin.getValue());
                }
                out.put("origins", ports);
                // The commands nothing recorded and nothing saved yet, by name with how many times each came.
                // https://raw.githubusercontent.com/213YaZ786/GutapK/c52520a/src/dex/java/io/gutapk/offline/Routes.java#status
                JSONObject unknown = new JSONObject();
                if (game != null) {
                    for (Map.Entry<String, Integer> command : game.unknown().entrySet()) {
                        unknown.put(command.getKey(), command.getValue().intValue());
                    }
                }
                out.put("unknown", unknown);
                return json(200, out);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private class EntriesHandler extends UriResponder {
        @Override
        public Http.Response get(Http session) {
            try {
                JSONArray out = new JSONArray();
                for (Store.Entry entry : cache.urls()) {
                    JSONObject item = new JSONObject();
                    item.put("key", entry.endpoint());
                    item.put("url", entry.url());
                    item.put("method", entry.requestMethod);
                    item.put("status", entry.code);
                    item.put("size", entry.body.length);
                    item.put("contentType", entry.contentType() == null ? "" : entry.contentType());
                    item.put("receivedAt", entry.receivedResponseMillis);
                    out.put(item);
                }
                return json(200, out);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private class ValuesHandler extends UriResponder {
        @Override
        public Http.Response get(Http session) {
            try {
                JSONArray out = new JSONArray();
                for (Store.Entry entry : cache.latestOfEach()) {
                    Object root = Overrides.parse(entry.body, entry.contentType());
                    if (root == null) {
                        continue;
                    }
                    for (Overrides.Leaf leaf : Overrides.leaves(root)) {
                        JSONObject item = new JSONObject();
                        item.put("key", entry.endpoint());
                        item.put("pointer", leaf.pointer);
                        item.put("type", leaf.type);
                        item.put("value", leaf.value);
                        Object override = overrides.get(entry.endpoint(), leaf.pointer);
                        if (override != null) {
                            item.put("override", override);
                        }
                        out.put(item);
                    }
                }
                return json(200, out);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private class SetHandler extends UriResponder {
        @Override
        public Http.Response post(Http session) {
            try {
                Object parsed = new JSONTokener(new String(session.getBody(), "UTF-8")).nextValue();
                JSONArray list = parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray().put(parsed);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item == null || item.optString("key").isEmpty() || !item.has("pointer")) {
                        return json(400, error("bad request", "Each item needs key, pointer and value"));
                    }
                    overrides.set(item.getString("key"), item.getString("pointer"), item.isNull("value") ? null : item.get("value"));
                }
                JSONObject out = new JSONObject();
                out.put("set", list.length());
                return json(200, out);
            } catch (JSONException e) {
                return json(400, error("bad request", "The body is not JSON: " + e.getMessage()));
            } catch (IllegalArgumentException e) {
                return json(400, error("bad request", e.getMessage()));
            } catch (IOException e) {
                return json(400, error("bad request", e.getMessage()));
            }
        }
    }

    private class ResetHandler extends UriResponder {
        @Override
        public Http.Response post(Http session) {
            overrides.reset();
            return json(200, ok());
        }
    }

    /**
     * GET /state returns the progress of the local game server as the BSON
     * bytes it keeps in state.bson, PUT /state replaces it with a BSON
     * document that holds a messages list, so GutapK's Admin can edit the
     * game's resources later. A phone without rules answers 404.
     * https://github.com/NanoHttpd/nanohttpd/blob/master/nanolets/src/main/java/org/nanohttpd/router/RouterNanoHTTPD.java#UriResponder
     * Not ported: other (the base class answers 405)
     */
    private class StateHandler extends UriResponder {
        @Override
        public Http.Response get(Http session) {
            if (game == null || !game.ready()) {
                return json(404, error("not found", "No local game server on this phone"));
            }
            return Http.Response.newFixedLengthResponse(200, "application/octet-stream", game.stateBytes());
        }

        @Override
        public Http.Response put(Http session) {
            if (game == null || !game.ready()) {
                return json(404, error("not found", "No local game server on this phone"));
            }
            try {
                game.replaceState(session.getBody());
                return json(200, ok());
            } catch (IllegalArgumentException e) {
                return json(400, error("bad request", "The body is not a state: " + e.getMessage()));
            } catch (IOException e) {
                return json(500, error("internal", "State not saved: " + e.getMessage()));
            }
        }
    }

    private class ClearHandler extends UriResponder {
        @Override
        public Http.Response post(Http session) {
            cache.evictAll();
            return json(200, ok());
        }
    }

    /**
     * GET /export: a zip of every file of the store, kept on the PC to travel
     * with the game. The store is held while the zip is written, so no answer
     * is half replaced in it.
     * https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#addFileToZip
     */
    private class ExportHandler extends UriResponder {
        @Override
        public Http.Response get(Http session) {
            try {
                ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
                ZipOutputStream outZip = new ZipOutputStream(byteOutput);
                try {
                    synchronized (cache) {
                        for (File path : cache.files()) {
                            addFileToZip(outZip, path.getName(), path);
                        }
                    }
                } finally {
                    outZip.close();
                }
                return Http.Response.newFixedLengthResponse(200, "application/zip", byteOutput.toByteArray());
            } catch (IOException e) {
                return json(500, error("internal", "Export: " + e.getMessage()));
            }
        }
    }

    /**
     * This function helps to create zip files.
     *
     * @param outZip     the ZipOutputStream where the data should be stored in
     * @param nameInZip  the path of the file inside the zip
     * @param path       the path of the file on the disk that should be added to zip
     *
     * https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#addFileToZip
     * not ported: OutputStreamConsumer, acceptStream (the overload with a consumer: no caller needs it)
     * not ported: InputStreamConsumer, extractFileFromZip, zipContainsFile, isValidZipFile (the PC side reads the zip, ServerAdmin)
     */
    static void addFileToZip(ZipOutputStream outZip, String nameInZip, File path) throws IOException {
        // java.nio.file.Files is not on the phones this runs on before Android 8, a stream is.
        InputStream inputStream = new FileInputStream(path);
        try {
            addFileToZip(outZip, nameInZip, inputStream);
        } finally {
            inputStream.close();
        }
    }

    /**
     * @param outZip      the ZipOutputStream where the data should be stored in
     * @param nameInZip   the path of the file inside the zip
     * @param inputStream the content to put inside the file
     */
    private static void addFileToZip(ZipOutputStream outZip, String nameInZip, InputStream inputStream) throws IOException {
        outZip.putNextEntry(new ZipEntry(nameInZip));
        // InputStream#transferTo is Java 9, and Android has it from API 33 only.
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outZip.write(buffer, 0, read);
        }
    }

    private static JSONObject ok() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
