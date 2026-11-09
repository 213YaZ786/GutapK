package io.gutapk.local;

/*
 * What GutapK read from the game and wrote in assets/gutapk/local.properties:
 * the port its client connects to on 127.0.0.1, whether it speaks UDP, its
 * own screen and the arguments that make it its own server. The config and
 * its loading are shadowsocks-java's, a properties file in place of JSON:
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/SSConfig.java
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/config/SSConfigFactory.java#L24-L52
 *
 * Not ported: SERVER, SERVER_PORT, LOCAL_ADDRESS, PASSWORD, METHOD, TIMEOUT, UDP_PORT, FAST_OPEN, DEFAULT_METHOD, DEFAULT_TIMEOUT (the host's address, port and key come from the invitation, the cipher is one, the datagrams share the stream's port)
 * Not ported: serverPort, localAddress, password, timeout, method, fastOpen, udpPort and their getters and setters (same)
 * Not ported: loadFromPath, loadFromCommandLine, SSJSONObject, getString, getInt, getBoolean, hasOption, getOptionValue (a properties file in the game's assets)
 *
 * Copyright 2020 xingty, Apache License 2.0.
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Config {
    /** Where the config sits in the game's assets. */
    public static final String CONFIG = "gutapk/local.properties";
    public static final String LOCAL_PORT = "local_port";
    public static final String UDP = "udp";
    public static final String ACTIVITY = "activity";
    public static final String ENGINE_ARG = "engine.arg.";
    /** The game's own link to a server, the friend's Play opens it; none for a game without one. */
    public static final String JOIN = "join";

    private int localPort;
    private boolean udp;
    private String activity;
    /** The server as the game's own link gives it, mindustry://127.0.0.1:6567 say. */
    private String server;
    private List<String> args = new ArrayList<String>();

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public boolean isUdp() {
        return udp;
    }

    public void setUdp(boolean udp) {
        this.udp = udp;
    }

    public String getActivity() {
        return activity;
    }

    public void setActivity(String activity) {
        this.activity = activity;
    }

    // https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/SSConfig.java#getServer
    public String getServer() {
        return server;
    }

    // https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/core/SSConfig.java#setServer
    public void setServer(String server) {
        this.server = server;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public static Config build(InputStream in) throws IOException {
        Properties obj = new Properties();
        obj.load(in);
        String localPort = obj.getProperty(LOCAL_PORT);
        if (localPort == null || localPort.trim().isEmpty()) {
            throw new IllegalArgumentException("local_port must not be empty");
        }
        String activity = obj.getProperty(ACTIVITY);
        if (activity == null || activity.isEmpty()) {
            throw new IllegalArgumentException("activity must not be empty");
        }
        boolean enableUDP = Boolean.parseBoolean(obj.getProperty(UDP, "false"));
        List<String> args = new ArrayList<String>();
        for (int i = 0; obj.getProperty(ENGINE_ARG + i) != null; i++) {
            args.add(obj.getProperty(ENGINE_ARG + i));
        }

        Config config = new Config();
        config.setLocalPort(Integer.parseInt(localPort.trim()));
        config.setUdp(enableUDP);
        config.setActivity(activity);
        String server = obj.getProperty(JOIN);
        config.setServer(server == null || server.isEmpty() ? null : server);
        config.setArgs(args);

        return config;
    }

    @Override
    public String toString() {
        return "Config{" +
                "localPort=" + localPort +
                ", udp=" + udp +
                ", activity='" + activity + '\'' +
                ", server='" + server + '\'' +
                ", args=" + args +
                '}';
    }
}
