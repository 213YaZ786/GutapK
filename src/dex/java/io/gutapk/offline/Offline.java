package io.gutapk.offline;

/*
 * Reads the injected config and starts what it describes: one server per
 * redirected origin on loopback, then the admin socket. NanoHTTPD's
 * SimpleWebServer.main does the same with a command line: defaults, then the
 * options read one by one, then the servers started.
 *
 * https://github.com/NanoHttpd/nanohttpd/blob/master/webserver/src/main/java/org/nanohttpd/webserver/SimpleWebServer.java#main
 * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#sign
 * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#new_id_bytes
 * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#L1065-L1078
 * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#_keyfile_changed
 * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/connect.py#write_connection_file
 * https://android.googlesource.com/platform/packages/modules/NetworkStack/+/refs/heads/main/src/com/android/server/connectivity/NetworkMonitor.java#ProbeThread
 *
 * A kernel's localhost ports are guarded by a random key in a file only the
 * user reads, and every message carries an HMAC of that key. Here the key sits
 * in the app's private files dir, and a port that is already taken is trusted
 * only when its holder signs a fresh nonce with that key (the port is inside
 * the MAC, so a squatter cannot relay the challenge to our server on another
 * port). Without that proof the start fails closed, Offline.Squatted.
 *
 * Not ported: main (start takes the config stream, there is no command line)
 * Not ported: registerPluginForMimeType (no plugins)
 * Not ported: ServerRunner.executeInstance (the servers run on daemon threads, and nothing waits for a key press)
 * Not ported: msg_header, serialize (no message is built, one nonce is signed)
 * Not ported: new_id (the key is raw random bytes, not an id string)
 * Not ported: the ports, transport, signature_scheme, curve keys and kernel_name of write_connection_file (the ports are the config's, the scheme is fixed)
 * Not ported: the sticky bit on the runtime dir (the files dir is private to the app)
 *
 * Copyright (C) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 * Copyright (c) 2015-, Jupyter Development Team, BSD 3-Clause.
 * Copyright (C) The Android Open Source Project, Apache License 2.0.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Offline {

    /** The path every server answers with the proof of who holds its port, see CacheInterceptor. */
    public static final String PROOF = "/.gutapk/proof";

    /** Length of the key kept in the secret file. */
    static final int KEY_BYTES = 32;

    /** Length of the nonce of a challenge. */
    static final int NONCE_BYTES = 16;

    /** Longest wait for a challenge: connect and read each wait 2000 ms, the whole probe a little longer. */
    static final int SOCKET_TIMEOUT_MS = 2000;

    static final int PROBE_WAIT_MS = 5000;

    /** Longest proof read, a hex HMAC-SHA256 is 64 bytes. */
    static final int PROOF_BYTES = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The abstract socket name the admin is bound to, null when it is not bound. */
    public volatile String adminName;

    public static final String LISTEN = "listen.";

    /** target.<port> is the address the app holds in place of that port's origin. */
    public static final String TARGET = "target.";

    /**
     * block.<port> is the status that origin is answered with, on the phone,
     * without a call: true for the default status. Like mitmproxy's block_list
     * option, one spec per origin.
     */
    public static final String BLOCK = "block.";

    /**
     * server.<port> is true when that origin is answered by the local game
     * server (GameServer), from the progress kept on the phone.
     */
    public static final String SERVER = "server.";

    public static final int NO_CONTENT = 204;

    /** The ports of the origins the local game server answers. */
    public final Set<Integer> served = new TreeSet<Integer>();

    /** The local game server, ready only when the server folder holds rules. */
    public GameServer game;

    /** The block list: the port of a blocked origin to the status_code it is answered with. */
    public final Map<Integer, Integer> items = new TreeMap<Integer, Integer>();

    public final List<Server> servers = new ArrayList<Server>();

    public final Map<Integer, String> origins = new TreeMap<Integer, String>();

    public Store cache;

    public Overrides overrides;

    public Admin admin;

    /**
     * A port of the config is held by something that could not sign for it. A
     * SecurityException, which the provider lets through so Android stops the app.
     */
    public static class Squatted extends SecurityException {
        /** The ports named in the message. */
        public Squatted(List<Integer> ports) {
            super("Port " + ports.toString().replaceAll("[\\[\\]]", "") + " held by another app");
        }
    }

    /**
     * The key is random bytes from a secure source, the ones new_id_bytes makes.
     * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#new_id_bytes
     * Not ported: new_id (the hex text of 16 bytes, the key here is the bytes themselves)
     */
    static byte[] new_id_bytes(int length) {
        byte[] buf = new byte[length];
        RANDOM.nextBytes(buf);
        return buf;
    }

    /**
     * The signature of a message: an HMAC-SHA256 of its parts, as lowercase
     * hex. The parts are the port, as decimal text, then the nonce, so the
     * proof of one port is worth nothing on another.
     * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#sign
     * Not ported: the b"" answer of a Session without auth (a key is always set here)
     * Not ported: auth.copy (a Mac is made for each call, nothing is shared between two)
     */
    public static String sign(byte[] key, int port, String nonce) {
        try {
            Mac auth = Mac.getInstance("HmacSHA256");
            auth.init(new SecretKeySpec(key, "HmacSHA256"));
            Mac h = auth;
            String[] msg_list = {Integer.toString(port), nonce};
            for (String m : msg_list) {
                h.update(m.getBytes("US-ASCII"));
            }
            return hexdigest(h.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The key file: read when it holds a key, else made and written for the
     * user only, a temp file then a rename so a reader never sees half a key.
     * A second process of the app reads the key the first one wrote.
     * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/connect.py#write_connection_file
     * Not ported: secure_write (the temp file is made user only here, in the same function)
     * Not ported: used (a word of the docstring, not a function)
     */
    static byte[] write_connection_file(File fname) throws IOException {
        byte[] key = _keyfile_changed(fname);
        if (key != null) {
            return key;
        }
        File runtime_dir = fname.getAbsoluteFile().getParentFile();
        runtime_dir.mkdirs();
        File fd = File.createTempFile("secret", ".tmp", runtime_dir);
        try {
            // Only ever write this file as user read/writeable: it holds the secret that guards the ports.
            fd.setReadable(false, false);
            fd.setWritable(false, false);
            fd.setReadable(true, true);
            fd.setWritable(true, true);
            FileOutputStream f = new FileOutputStream(fd);
            try {
                f.write(new_id_bytes(KEY_BYTES));
                f.getFD().sync();
            } finally {
                f.close();
            }
            if (!fd.renameTo(fname)) {
                throw new IOException("Cannot rename " + fd + " to " + fname);
            }
        } finally {
            fd.delete();
        }
        // The key on disk is the one in force, whoever wrote it last.
        key = _keyfile_changed(fname);
        if (key == null) {
            throw new IOException("Cannot read the key in " + fname);
        }
        return key;
    }

    /**
     * The key of a key file, read whole: null when the file is absent or is
     * not the size of a key, so a half written one is made again.
     * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#_keyfile_changed
     */
    private static byte[] _keyfile_changed(File change) throws IOException {
        if (!change.isFile() || change.length() != KEY_BYTES) {
            return null;
        }
        byte[] key = new byte[KEY_BYTES];
        try (FileInputStream f = new FileInputStream(change)) {
            int n = 0;
            while (n < key.length) {
                int read = f.read(key, n, key.length - n);
                if (read < 0) {
                    return null;
                }
                n += read;
            }
        }
        return key;
    }

    /**
     * A probe connection: no proxy, short timeouts, closed after the answer, no cache.
     * https://android.googlesource.com/platform/packages/modules/NetworkStack/+/refs/heads/main/src/com/android/server/connectivity/NetworkMonitor.java#makeProbeConnection
     * Not ported: mCleartextDnsNetwork (the address is the loopback, NO_PROXY keeps a system proxy out)
     * Not ported: the User-Agent header (mCaptivePortalUserAgent, none is set)
     */
    private static HttpURLConnection makeProbeConnection(URL url, boolean followRedirects) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setConnectTimeout(SOCKET_TIMEOUT_MS);
        conn.setReadTimeout(SOCKET_TIMEOUT_MS);
        conn.setRequestProperty("Connection", "close");
        conn.setUseCaches(false);
        return conn;
    }

    /**
     * At most maxLength characters of a stream, so an answer of any size costs no more than a proof.
     * https://android.googlesource.com/platform/packages/modules/NetworkStack/+/refs/heads/main/src/com/android/server/connectivity/NetworkMonitor.java#readAsString
     */
    static String readAsString(InputStream is, int maxLength, Charset charset) throws IOException {
        final InputStreamReader reader = new InputStreamReader(is, charset);
        final char[] buffer = new char[1000];
        final StringBuilder builder = new StringBuilder();
        int totalReadLength = 0;
        while (totalReadLength < maxLength) {
            final int availableLength = Math.min(maxLength - totalReadLength, buffer.length);
            final int currentLength = reader.read(buffer, 0, availableLength);
            if (currentLength < 0) break; // EOF

            totalReadLength += currentLength;
            builder.append(buffer, 0, currentLength);
        }
        return builder.toString();
    }

    /**
     * The probe runs on its own thread, the main thread forbids network on Android,
     * and counts a latch down when it is done, as NetworkMonitor's does.
     * https://android.googlesource.com/platform/packages/modules/NetworkStack/+/refs/heads/main/src/com/android/server/connectivity/NetworkMonitor.java#ProbeThread
     * Not ported: mDependencies.onThreadCreated (no test hook)
     * Not ported: the HTTPS probe and the early countDown of a conclusive result (one probe, one latch count)
     */
    private static class ProbeThread extends Thread {
        private final CountDownLatch mLatch;
        private final String mHost;
        private final int mPort;
        private final byte[] mKey;
        private final String mNonce;

        ProbeThread(CountDownLatch latch, String host, int port, byte[] key, String nonce) {
            mLatch = latch;
            mHost = host;
            mPort = port;
            mKey = key;
            mNonce = nonce;
            setDaemon(true);
            setName("GutapK Probe");
        }

        private volatile boolean mResult;

        public boolean result() {
            return mResult;
        }

        @Override
        public void run() {
            mResult = sendProbe();
            // Signal this probe has completed.
            mLatch.countDown();
        }

        // sendProbe() is synchronous and blocks until it has the result, any failure is a no.
        private boolean sendProbe() {
            try {
                final URL url = new URL("http", mHost, mPort, PROOF + "?nonce=" + mNonce);
                final HttpURLConnection conn = makeProbeConnection(url, false /* followRedirects */);
                try {
                    if (conn.getResponseCode() != 200) {
                        return false;
                    }
                    final String signature = readAsString(conn.getInputStream(), PROOF_BYTES, StandardCharsets.US_ASCII);
                    // compare_digest: the time does not tell how many bytes matched.
                    return MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII), sign(mKey, mPort, mNonce).getBytes(StandardCharsets.US_ASCII));
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                Server.LOG.log(Level.FINE, "Port " + mPort + " gave no proof: " + e);
                return false;
            }
        }
    }

    /**
     * Whether the holder of a taken port is this app: it must sign a fresh
     * nonce, with the port inside the MAC, as only the owner of the key can.
     * https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#L1065-L1078
     * Not ported: digest_history (a nonce is used once, by construction)
     */
    static boolean ours(String host, int port, byte[] key) {
        final String nonce = hexdigest(new_id_bytes(NONCE_BYTES));
        final CountDownLatch latch = new CountDownLatch(1);
        final ProbeThread probe = new ProbeThread(latch, host, port, key, nonce);
        probe.start();
        try {
            if (!latch.await(PROBE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                probe.interrupt();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            probe.interrupt();
            return false;
        }
        return probe.result();
    }

    /** Lowercase hex, as hexdigest of an HMAC prints it. */
    static String hexdigest(byte[] h) {
        char[] chars = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(h.length * 2);
        for (byte m : h) {
            out.append(chars[(m >> 4) & 0xf]).append(chars[m & 0xf]);
        }
        return out.toString();
    }

    /**
     * Starts the servers described by the config. The sockets are bound before
     * this returns, so the game's first request finds them. A port that is
     * taken, because a second process of the same app got there first, is
     * logged and skipped, once its holder proved it is ours. A port held by
     * anything else stops every server and throws Squatted: the game would
     * send its account tokens to whoever holds it.
     */
    public static Offline start(InputStream source, File rootDirs) throws IOException {
        // Defaults
        String host = "127.0.0.1";
        String admin = null;
        Offline options = new Offline();
        Properties args = new Properties();
        args.load(source);

        // Parse the config, one origin per listen.<port> line.
        Map<Integer, String> moved = new TreeMap<Integer, String>();
        for (String name : args.stringPropertyNames()) {
            if (name.startsWith(LISTEN)) {
                options.origins.put(Integer.parseInt(name.substring(LISTEN.length()).trim()), args.getProperty(name).trim());
            } else if (name.startsWith(TARGET)) {
                moved.put(Integer.parseInt(name.substring(TARGET.length()).trim()), args.getProperty(name).trim());
            } else if (name.startsWith(BLOCK)) {
                // configure: the list is emptied, then each option of block_list is parsed into a spec.
                // https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#configure
                int port = Integer.parseInt(name.substring(BLOCK.length()).trim());
                try {
                    options.items.put(port, parse_spec(args.getProperty(name).trim()));
                } catch (IllegalArgumentException e) {
                    // OptionsError in mitmproxy stops the run, here the origin is only served as any other.
                    Server.LOG.log(Level.WARNING, "Cannot parse block_list option " + name + ": " + e.getMessage());
                }
            } else if (name.startsWith(SERVER)) {
                // server.<port>=true: that origin is answered by the local game server, only when its value is true.
                if ("true".equals(args.getProperty(name).trim())) {
                    options.served.add(Integer.parseInt(name.substring(SERVER.length()).trim()));
                }
            } else if ("admin".equals(name)) {
                admin = args.getProperty(name).trim();
            }
        }
        Map<String, String> targets = new TreeMap<String, String>();
        for (Map.Entry<Integer, String> origin : options.origins.entrySet()) {
            if (moved.containsKey(origin.getKey())) {
                targets.put(origin.getValue(), moved.get(origin.getKey()));
            }
        }

        options.cache = new Store(new File(rootDirs, "cache"));
        options.overrides = new Overrides(new File(rootDirs, "overrides.json"));
        ProxyResponseRenderer renderer = new ProxyResponseRenderer();
        // The rules and the progress of the local game server sit in the server folder, seeded from the assets.
        options.game = new GameServer(new File(rootDirs, "server"));
        // The key that guards the ports sits in a file of the private dir, read when present.
        // https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/connect.py#write_connection_file
        byte[] key = write_connection_file(new File(rootDirs, "secret"));
        List<Integer> ports = new ArrayList<Integer>();
        for (Map.Entry<Integer, String> origin : options.origins.entrySet()) {
            int status_code = options.items.containsKey(origin.getKey()) ? options.items.get(origin.getKey()) : 0;
            GameServer game = options.served.contains(origin.getKey()) && options.game.ready() ? options.game : null;
            Server server = new Server(host, origin.getKey(), new CacheInterceptor(origin.getValue(), options.cache, options.overrides, renderer, targets, status_code, game, key, origin.getKey()));
            try {
                server.start();
                options.servers.add(server);
                Server.LOG.info((status_code == 0 ? "Offline cache for " : "Blocked, answered " + status_code + " without a call: ") + origin.getValue() + " on " + host + ":" + origin.getKey());
            } catch (IOException e) {
                // compare_digest of the signature the holder gives: a port it cannot sign for is not ours.
                // https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#L1065-L1078
                if (ours(host, origin.getKey(), key)) {
                    Server.LOG.log(Level.WARNING, "Port " + origin.getKey() + " not bound, another process of this app serves it: " + e);
                } else {
                    Server.LOG.log(Level.SEVERE, "Port " + origin.getKey() + " not bound, and its holder is not this app: " + e);
                    ports.add(origin.getKey());
                }
            }
        }
        if (!ports.isEmpty()) {
            options.stop();
            throw new Squatted(ports);
        }
        if (admin != null) {
            // A new name per launch, so an app that squats the fixed prefix is not the one the PC finds.
            // https://github.com/jupyter/jupyter_client/blob/main/jupyter_client/session.py#new_id_bytes
            String name = admin + "." + hexdigest(new_id_bytes(NONCE_BYTES));
            try {
                Admin socket = new Admin(name, new Routes(options.cache, options.overrides, options.origins, options.game));
                socket.start();
                options.admin = socket;
                options.adminName = name;
                Server.LOG.info("Offline admin on @" + name);
            } catch (IOException e) {
                Server.LOG.log(Level.WARNING, "Admin socket @" + name + " not bound: " + e);
            }
        }
        return options;
    }

    /**
     * One block.<port> value as a status code: true is the default status, a
     * number is the status itself, as parse_spec reads the status of
     * "/flow-filter/status". The filter is the port, the line's name. A
     * number is read as SimpleWebServer.main reads the port of its options.
     * https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#parse_spec
     * https://github.com/NanoHttpd/nanohttpd/blob/master/webserver/src/main/java/org/nanohttpd/webserver/SimpleWebServer.java#main
     * Not ported: main (start is the Offline.start that already cites it)
     */
    static int parse_spec(String option) {
        String status = option;
        int status_code = NO_CONTENT;
        if (!"true".equals(status)) {
            try {
                status_code = Integer.parseInt(status);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("Invalid HTTP status code: " + status);
            }
        }
        return status_code;
    }

    public void stop() {
        for (Server server : servers) {
            server.stop();
        }
    }
}
