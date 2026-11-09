package io.gutapk.offline;

/*
 * The disk cache of recorded answers, after OkHttp's Cache and its Entry: one
 * metadata file and one body file per key, the key a hash of the request. OkHttp
 * keys on the url alone, here the method and the request body count too, and a
 * second index file holds the latest answer for method + origin + path.
 *
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#get
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#put
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#key
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#remove
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#evictAll
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#urls
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#writeTo
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#matches
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt#response
 *
 * Not ported: hasNext, next (urls returns a list, the cache is small)
 * Not ported: abortQuietly, update, writeAbortCount, writeSuccessCount (no DiskLruCache editor: a file is written aside and renamed)
 *
 * A request that was never seen as such gets the answer to the nearest one
 * recorded, as WireMock ranks its near misses: the mean of the normalised
 * Levenshtein distances of the query and of the body, the request body kept
 * in a third file for it.
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/verification/NearMissCalculator.java#findNearestTo
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/matching/EqualToPattern.java#match
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/matching/MatchResult.java#aggregate
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/common/Strings.java#L513-L521
 * https://github.com/apache/commons-text/blob/master/src/main/java/org/apache/commons/text/similarity/LevenshteinDistance.java#unlimitedCompare
 *
 * Not ported: NEAR_MISS_COUNT, sortAndTruncate, NearMiss, MemoizingMatchResult (the nearest one is served, not a list)
 * Not ported: getScenarioStateOrNull, customMatchers (no scenarios and no extensions)
 * Not ported: caseInsensitive (bodies are bytes)
 * Not ported: limitedCompare, threshold (every recorded request is ranked)
 * Not ported: aggregateWeighted, WeightedMatchResult (query and body weigh the same, as aggregate gives them)
 *
 * Copyright 2010 Google Inc., 2011 Square Inc., Apache License 2.0.
 * Copyright Thomas Akehurst, Apache License 2.0.
 * Copyright The Apache Software Foundation, Apache License 2.0.
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Store {

    private static final String ENTRY_METADATA = ".0";
    private static final String ENTRY_BODY = ".1";
    private static final String ENTRY_REQUEST = ".2";
    private static final String LATEST = ".latest";

    /** The bytes of a body compared at most, the distance costing their product. */
    static final int COMPARED = 2048;

    /** One recorded answer, with the request that asked for it. */
    public static class Entry {
        public final String origin;
        public final String requestMethod;
        public final String path;
        public final String query;
        public final String requestBodyHash;
        public final int code;
        public final Map<String, String> responseHeaders;
        public final byte[] body;
        public final long receivedResponseMillis;

        public Entry(String origin, String requestMethod, String path, String query, String requestBodyHash, int code,
                Map<String, String> responseHeaders, byte[] body, long receivedResponseMillis) {
            this.origin = origin;
            this.requestMethod = requestMethod;
            this.path = path;
            this.query = query;
            this.requestBodyHash = requestBodyHash;
            this.code = code;
            this.responseHeaders = responseHeaders;
            this.body = body;
            this.receivedResponseMillis = receivedResponseMillis;
        }

        public String url() {
            return origin + path + (query.isEmpty() ? "" : "?" + query);
        }

        /** The key of an override and of the latest index: method, origin and path. */
        public String endpoint() {
            return requestMethod + " " + origin + path;
        }

        public boolean matches(String method, String origin, String path, String query, String bodyHash) {
            return this.origin.equals(origin) && this.requestMethod.equals(method) && this.path.equals(path)
                    && this.query.equals(query) && this.requestBodyHash.equals(bodyHash);
        }

        public String contentType() {
            for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
                if ("content-type".equalsIgnoreCase(header.getKey())) {
                    return header.getValue();
                }
            }
            return null;
        }

        /**
         * Writes the metadata the way OkHttp's Entry does, one value per line:
         * the url, the request method, the status code, then the response headers
         * counted and listed, and the time the answer was received.
         */
        void writeTo(Writer sink) throws IOException {
            sink.write(origin + "\n");
            sink.write(requestMethod + "\n");
            sink.write(path + "\n");
            sink.write(query + "\n");
            sink.write(requestBodyHash + "\n");
            sink.write(code + "\n");
            sink.write(responseHeaders.size() + "\n");
            for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
                sink.write(header.getKey() + ": " + header.getValue().replace('\n', ' ').replace('\r', ' ') + "\n");
            }
            sink.write(receivedResponseMillis + "\n");
        }

        static Entry readFrom(BufferedReader source, byte[] body) throws IOException {
            String origin = source.readLine();
            String requestMethod = source.readLine();
            String path = source.readLine();
            String query = source.readLine();
            String requestBodyHash = source.readLine();
            int code = Integer.parseInt(source.readLine());
            int size = Integer.parseInt(source.readLine());
            Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
            for (int i = 0; i < size; i++) {
                String line = source.readLine();
                int index = line.indexOf(": ");
                responseHeaders.put(line.substring(0, index), line.substring(index + 2));
            }
            long receivedResponseMillis = Long.parseLong(source.readLine());
            return new Entry(origin, requestMethod, path, query, requestBodyHash, code, responseHeaders, body, receivedResponseMillis);
        }
    }

    private final File directory;

    public Store(File directory) {
        this.directory = directory;
        directory.mkdirs();
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file name of an exact answer: method, origin, path, query and the hash of the request body. */
    public static String key(String method, String origin, String path, String query, String bodyHash) {
        try {
            return sha256((method + " " + origin + path + "?" + query + " " + bodyHash).getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file name of the latest-answer index of method + origin + path. */
    public static String latestKey(String endpoint) {
        try {
            return sha256(endpoint.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Entry read(String key) {
        return read(key, true);
    }

    /** The entry of the key, its answer left unread when the request alone is ranked. */
    private Entry read(String key, boolean answer) {
        File metadata = new File(directory, key + ENTRY_METADATA);
        File body = new File(directory, key + ENTRY_BODY);
        if (!metadata.isFile() || !body.isFile()) {
            return null;
        }
        BufferedReader source = null;
        try {
            source = new BufferedReader(new InputStreamReader(new FileInputStream(metadata), "UTF-8"));
            return Entry.readFrom(source, answer ? readAll(body) : new byte[0]);
        } catch (IOException e) {
            return null; // Give up because the cache cannot be read.
        } catch (RuntimeException e) {
            return null; // A truncated entry counts as absent.
        } finally {
            Server.safeClose(source);
        }
    }

    /** The recorded answer to exactly this request, or null. */
    public synchronized Entry get(String method, String origin, String path, String query, String bodyHash) {
        Entry entry = read(key(method, origin, path, query, bodyHash));
        if (entry == null || !entry.matches(method, origin, path, query, bodyHash)) {
            return null;
        }
        return entry;
    }

    /** The newest answer recorded for the method, origin and path, whatever the query and body were. */
    public synchronized Entry latest(String method, String origin, String path) {
        File index = new File(directory, latestKey(method + " " + origin + path) + LATEST);
        if (!index.isFile()) {
            return null;
        }
        try {
            return read(new String(readAll(index), "UTF-8").trim());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The recorded answer whose request is nearest to this one among those of
     * its method, origin and path, the newest among equals. A game that adds a
     * random number to every call and says what it wants in the body gets the
     * answer to the same command, not the last one it was sent.
     * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/verification/NearMissCalculator.java#findNearestTo
     * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/matching/MatchResult.java#aggregate
     * not ported: findNearestTo (it is this method, over the recorded requests)
     * not ported: aggregate, MatchResult, match (the mean of the two distances is a double here)
     * not ported: getDistance (normalisedLevenshteinDistance gives the distance)
     * not ported: isExactMatch (get serves the exact request before nearest is asked)
     */
    public synchronized Entry nearest(String method, String origin, String path, String query, byte[] body) {
        File[] serveEvents = directory.listFiles();
        if (serveEvents == null) {
            return null;
        }
        String requestPattern = method + " " + origin + path;
        String value = latin1(body);
        String nearMiss = null;
        double distance = Double.MAX_VALUE;
        long received = Long.MIN_VALUE;
        for (File serveEvent : serveEvents) {
            String name = serveEvent.getName();
            if (!name.endsWith(ENTRY_METADATA)) {
                continue;
            }
            String key = name.substring(0, name.length() - ENTRY_METADATA.length());
            Entry stubMapping = read(key, false);
            if (stubMapping == null || !stubMapping.endpoint().equals(requestPattern)) {
                continue;
            }
            byte[] expectedValue = new byte[0];
            File request = new File(directory, key + ENTRY_REQUEST);
            if (request.isFile()) {
                try {
                    expectedValue = readAll(request);
                } catch (IOException e) {
                    Server.LOG.fine("Unreadable request " + request + ": " + e);
                }
            }
            double matchResult = (normalisedLevenshteinDistance(stubMapping.query, query) + normalisedLevenshteinDistance(latin1(expectedValue), value)) / 2;
            if (matchResult < distance || (matchResult == distance && stubMapping.receivedResponseMillis > received)) {
                nearMiss = key;
                distance = matchResult;
                received = stubMapping.receivedResponseMillis;
            }
        }
        return nearMiss == null ? null : read(nearMiss);
    }

    /**
     * The first COMPARED bytes, one char each, the String that EqualToPattern
     * matches.
     * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/matching/EqualToPattern.java#match
     */
    private static String latin1(byte[] value) {
        try {
            return new String(value, 0, Math.min(value.length, COMPARED), "ISO-8859-1");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/common/Strings.java#L513-L521
    static double normalisedLevenshteinDistance(String one, String two) {
        if (one == null || two == null) {
            return 1.0;
        }

        double maxDistance = Math.max(one.length(), two.length());
        if (maxDistance == 0) {
            return 0.0; // Two empty values are the same.
        }
        double actualDistance = unlimitedCompare(one, two);
        return (actualDistance / maxDistance);
    }

    // https://github.com/apache/commons-text/blob/master/src/main/java/org/apache/commons/text/similarity/LevenshteinDistance.java#unlimitedCompare
    static int unlimitedCompare(CharSequence left, CharSequence right) {
        int n = left.length(); // length of left
        int m = right.length(); // length of right

        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        if (n > m) {
            // swap the input strings to consume less memory
            final CharSequence tmp = left;
            left = right;
            right = tmp;
            n = m;
            m = right.length();
        }
        final int[] p = new int[n + 1];
        // indexes into strings left and right
        int i; // iterates through left
        int j; // iterates through right
        int upperLeft;
        int upper;
        char rightJ; // jth character of right
        int cost; // cost
        for (i = 0; i <= n; i++) {
            p[i] = i;
        }
        for (j = 1; j <= m; j++) {
            upperLeft = p[0];
            rightJ = right.charAt(j - 1);
            p[0] = j;

            for (i = 1; i <= n; i++) {
                upper = p[i];
                cost = left.charAt(i - 1) == rightJ ? 0 : 1;
                // minimum of cell to the left+1, to the top+1, diagonally left and up +cost
                p[i] = Math.min(Math.min(p[i - 1] + 1, p[i] + 1), upperLeft + cost);
                upperLeft = upper;
            }
        }
        return p[n];
    }

    /** Records the answer, and the request body that asked for it for nearest. */
    public synchronized void put(Entry entry, byte[] requestBody) {
        String key = key(entry.requestMethod, entry.origin, entry.path, entry.query, entry.requestBodyHash);
        try {
            File metadata = new File(directory, key + ENTRY_METADATA);
            File body = new File(directory, key + ENTRY_BODY);
            writeAside(new File(directory, key + ENTRY_REQUEST), requestBody);
            writeAside(body, entry.body);
            Writer sink = null;
            File aside = new File(directory, key + ENTRY_METADATA + ".tmp");
            try {
                sink = new OutputStreamWriter(new FileOutputStream(aside), "UTF-8");
                entry.writeTo(sink);
            } finally {
                Server.safeClose(sink);
            }
            replace(aside, metadata);
            writeAside(new File(directory, latestKey(entry.endpoint()) + LATEST), key.getBytes("UTF-8"));
        } catch (IOException e) {
            Server.LOG.warning("Could not record " + entry.url() + ": " + e);
        }
    }

    public synchronized void remove(String key) {
        new File(directory, key + ENTRY_METADATA).delete();
        new File(directory, key + ENTRY_BODY).delete();
        new File(directory, key + ENTRY_REQUEST).delete();
    }

    /** Deletes all values stored in the cache. */
    public synchronized void evictAll() {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }

    /** Every recorded answer, newest data included. */
    public synchronized List<Entry> urls() {
        List<Entry> entries = new ArrayList<Entry>();
        File[] files = directory.listFiles();
        if (files == null) {
            return entries;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(ENTRY_METADATA)) {
                Entry entry = read(name.substring(0, name.length() - ENTRY_METADATA.length()));
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /** The newest answer of each method + origin + path. */
    public synchronized List<Entry> latestOfEach() {
        List<Entry> entries = new ArrayList<Entry>();
        File[] files = directory.listFiles();
        if (files == null) {
            return entries;
        }
        for (File file : files) {
            if (file.getName().endsWith(LATEST)) {
                try {
                    Entry entry = read(new String(readAll(file), "UTF-8").trim());
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (IOException e) {
                    Server.LOG.fine("Unreadable index " + file + ": " + e);
                }
            }
        }
        return entries;
    }

    /**
     * Every file of the cache that a zip carries (the metadata, body and request
     * files and the latest-answer indexes), not the ones being written aside.
     * https://github.com/TeamNewPipe/NewPipe/blob/009b00ecb68d5036f3ecc6acac11eb09e5b2587c/app/src/main/java/org/schabi/newpipe/util/ZipHelper.java#addFileToZip
     * not ported: addFileToZip (it is Routes.addFileToZip, this lists the path it is given)
     * not ported: acceptStream (the consumer overload of addFileToZip is left out of Routes)
     * not ported: extractFileFromZip (the PC side unzips, in ServerAdmin)
     * not ported: zipContainsFile, isValidZipFile (nothing reads a zip on the phone)
     */
    public synchronized List<File> files() {
        List<File> path = new ArrayList<File>();
        File[] nameInZip = directory.listFiles();
        if (nameInZip == null) {
            return path;
        }
        for (File file : nameInZip) {
            if (file.isFile() && !file.getName().endsWith(".tmp")) {
                path.add(file);
            }
        }
        return path;
    }

    static void writeAside(File target, byte[] data) throws IOException {
        File aside = new File(target.getPath() + ".tmp");
        FileOutputStream sink = new FileOutputStream(aside);
        try {
            sink.write(data);
        } finally {
            sink.close();
        }
        replace(aside, target);
    }

    static void replace(File aside, File target) throws IOException {
        if (!aside.renameTo(target)) {
            target.delete();
            if (!aside.renameTo(target)) {
                throw new IOException("Could not move " + aside + " to " + target);
            }
        }
    }

    static byte[] readAll(File file) throws IOException {
        return readAll(new FileInputStream(file));
    }

    /** Reads a stream to its end and closes it. */
    static byte[] readAll(InputStream source) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            source.close();
        }
    }
}
