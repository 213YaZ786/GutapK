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
        for (String name : args.stringPropertyNames()) {
            if (name.startsWith(LISTEN)) {
                options.origins.put(Integer.parseInt(name.substring(LISTEN.length()).trim()), args.getProperty(name).trim());
            } else if ("admin".equals(name)) {
                admin = args.getProperty(name).trim();
            }
        }

        options.cache = new Store(new File(rootDirs, "cache"));
        options.overrides = new Overrides(new File(rootDirs, "overrides.json"));
        ProxyResponseRenderer renderer = new ProxyResponseRenderer();
        for (Map.Entry<Integer, String> origin : options.origins.entrySet()) {
            Server server = new Server(host, origin.getKey(), new CacheInterceptor(origin.getValue(), options.cache, options.overrides, renderer));
            try {
                server.start();
                options.servers.add(server);
                Server.LOG.info("Offline cache for " + origin.getValue() + " on " + host + ":" + origin.getKey());
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

    public void stop() {
        for (Server server : servers) {
            server.stop();
        }
    }
}
