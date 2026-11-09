package io.gutapk.offline;

/*
 * Reads the injected config and starts what it describes: one server per
 * redirected origin on loopback, then the admin socket. NanoHTTPD's
 * SimpleWebServer.main does the same with a command line: defaults, then the
 * options read one by one, then the servers started.
 *
 * https://github.com/NanoHttpd/nanohttpd/blob/master/webserver/src/main/java/org/nanohttpd/webserver/SimpleWebServer.java#main
 *
 * Not ported: main (start takes the config stream, there is no command line)
 * Not ported: registerPluginForMimeType (no plugins)
 * Not ported: ServerRunner.executeInstance (the servers run on daemon threads, and nothing waits for a key press)
 *
 * Copyright (C) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.logging.Level;

public class Offline {

    public static final String LISTEN = "listen.";

    /** target.<port> is the address the app holds in place of that port's origin. */
    public static final String TARGET = "target.";

    /**
     * block.<port> is the status that origin is answered with, on the phone,
     * without a call: true for the default status. Like mitmproxy's block_list
     * option, one spec per origin.
     */
    public static final String BLOCK = "block.";

    public static final int NO_CONTENT = 204;

    /** The block list: the port of a blocked origin to the status_code it is answered with. */
    public final Map<Integer, Integer> items = new TreeMap<Integer, Integer>();

    public final List<Server> servers = new ArrayList<Server>();

    public final Map<Integer, String> origins = new TreeMap<Integer, String>();

    public Store cache;

    public Overrides overrides;

    public Admin admin;

    /**
     * Starts the servers described by the config. The sockets are bound before
     * this returns, so the game's first request finds them. A port that is
     * taken, because a second process of the same app got there first, is
     * logged and skipped.
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
        for (Map.Entry<Integer, String> origin : options.origins.entrySet()) {
            int status_code = options.items.containsKey(origin.getKey()) ? options.items.get(origin.getKey()) : 0;
            Server server = new Server(host, origin.getKey(), new CacheInterceptor(origin.getValue(), options.cache, options.overrides, renderer, targets, status_code));
            try {
                server.start();
                options.servers.add(server);
                Server.LOG.info((status_code == 0 ? "Offline cache for " : "Blocked, answered " + status_code + " without a call: ") + origin.getValue() + " on " + host + ":" + origin.getKey());
            } catch (IOException e) {
                Server.LOG.log(Level.WARNING, "Port " + origin.getKey() + " not bound, another process serves it: " + e);
            }
        }
        if (admin != null) {
            try {
                Admin socket = new Admin(admin, new Routes(options.cache, options.overrides, options.origins));
                socket.start();
                options.admin = socket;
                Server.LOG.info("Offline admin on @" + admin);
            } catch (IOException e) {
                Server.LOG.log(Level.WARNING, "Admin socket @" + admin + " not bound: " + e);
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
