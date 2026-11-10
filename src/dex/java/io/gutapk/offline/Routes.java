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

    @Override
    public Http.Response handle(Http session) throws IOException {
        return serve(session);
    }

    public Http.Response serve(Http session) {
        // Try to find match
        return process(session);
    }

    public Http.Response process(Http session) {
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
