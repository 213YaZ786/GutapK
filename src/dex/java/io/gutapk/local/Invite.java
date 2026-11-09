package io.gutapk.local;

/*
 * The invitation of a Local match. The host's QR code is a web link to the
 * host's phone, which a phone's camera opens in the browser:
 *     http://<host's address>:<page port>/#p=<relay port>&k=<key>
 * The key sits after the #, which a browser never sends. The page served
 * there opens the game with a link of its own,
 *     gutapk://<package>/join?h=<host's address>&p=<relay port>&k=<key>
 * through an intent: URL, and offers the game's APK to a friend who does not
 * have it yet. The page is NanoHTTPD's hello server, the addresses are read
 * as the Java tutorial lists them, the link is read as android.net.Uri reads
 * a query parameter:
 * https://github.com/NanoHttpd/nanohttpd/blob/master/samples/src/main/java/org/nanohttpd/samples/http/HelloServer.java#L63-L80
 * https://docs.oracle.com/javase/tutorial/networking/nifs/listing.html
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/Uri.java#L1721-L1764
 *
 * Not ported: main, HelloServer() (the server is GutapK's own Server, started by the Local screen)
 * Not ported: serve (Http.Handler names it handle), getParms and the form (the page asks nothing)
 * Not ported: getDisplayName, printf (the addresses are kept, not shown)
 * Not ported: leftbar, load, search, showcode, showleft, toggleleft (the tutorial page's own scripts, not its sample)
 * Not ported: isOpaque, NOT_HIERARCHICAL, encode(key) (the keys are GutapK's own letters, the query is read from the link)
 *
 * Copyright (c) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 * Copyright (c) 1995, 2008, Oracle and/or its affiliates, BSD licence of the Java Tutorials.
 * Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
 */

import io.gutapk.offline.Http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;

public final class Invite implements Http.Handler {
    /** The scheme of the link that opens the game, and the path of a friend's. */
    public static final String SCHEME = "gutapk";
    public static final String JOIN = "/join";
    static final String HOST = "h";
    static final String PORT = "p";
    static final String KEY = "k";
    static final String APK = "/game.apk";
    private static final String APK_TYPE = "application/vnd.android.package-archive";

    private final String packageName;
    private final File apk;

    /** The page of the game packageName, which offers apk to a friend without it. */
    public Invite(String packageName, File apk) {
        this.packageName = packageName;
        this.apk = apk;
    }

    /** The link of the QR code. */
    public static String link(InetAddress address, int pagePort, int relayPort, byte[] key) {
        return "http://" + address.getHostAddress() + ":" + pagePort + "/#" + PORT + "=" + relayPort + "&" + KEY + "=" + Hex.encodeHexString(key);
    }

    /** The link that opens the game of packageName on a friend's phone, for a friend who types it. */
    public static String join(String packageName, InetAddress address, int relayPort, byte[] key) {
        return SCHEME + "://" + packageName + JOIN + "?" + HOST + "=" + address.getHostAddress() + "&" + PORT + "=" + relayPort + "&" + KEY + "=" + Hex.encodeHexString(key);
    }

    @Override
    public Http.Response handle(Http session) throws IOException {
        String uri = session.getUri();
        Tunnel.LOG.info(session.getMethod() + " '" + uri + "' ");
        if (APK.equals(uri) && apk != null && apk.isFile()) {
            Http.Response response = Http.Response.newFixedLengthResponse(200, APK_TYPE, new FileInputStream(apk), apk.length());
            response.addHeader("Content-Disposition", "attachment; filename=\"" + packageName + ".apk\"");
            return response;
        }
        if (!"/".equals(uri)) {
            return Http.Response.newFixedLengthResponse(404, "text/plain", "Not Found");
        }
        String msg = "<!doctype html><html><head><meta name=viewport content='width=device-width'><title>" + packageName + "</title></head>"
            + "<body style='font-family:sans-serif;text-align:center;padding:2em'>\n"
            + "<h1>Join the match</h1>\n"
            + "<p><a id=open style='display:inline-block;padding:1em 2em;background:#1a73e8;color:#fff;border-radius:8px;text-decoration:none'>Open the game</a></p>\n"
            + "<p><a href='" + APK.substring(1) + "'>No game yet? Download it from your friend</a></p>\n"
            // The key never leaves the friend's phone: the # part stays in the browser.
            + "<script>var l='intent://" + packageName + JOIN + "?" + HOST + "='+location.hostname+'&'+location.hash.slice(1)"
            + "+'#Intent;scheme=" + SCHEME + ";package=" + packageName + ";end';"
            + "document.getElementById('open').href=l;location.href=l;</script>\n";
        msg += "</body></html>\n";
        return Http.Response.newFixedLengthResponse(200, "text/html", msg);
    }

    /**
     * The IPv4 addresses of the phone's networks, Wi-Fi and hotspot ones
     * first: where friends nearby can reach the host.
     */
    public static List<InetAddress> addresses() throws SocketException {
        List<InetAddress> first = new ArrayList<InetAddress>();
        List<InetAddress> others = new ArrayList<InetAddress>();
        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        if (nets == null) {
            return first;
        }
        for (NetworkInterface netint : Collections.list(nets)) {
            if (!netint.isUp() || netint.isLoopback()) {
                continue;
            }
            String name = netint.getName();
            boolean wifi = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("eth");
            Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
            for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                    (wifi ? first : others).add(inetAddress);
                }
            }
        }
        first.addAll(others);
        return first;
    }

    /** The value of key in a link's query, null when it has none. */
    public static String getQueryParameter(String query, String key) {
        if (query == null) {
            return null;
        }
        final int length = query.length();
        int start = 0;
        do {
            int nextAmpersand = query.indexOf('&', start);
            int end = nextAmpersand != -1 ? nextAmpersand : length;

            int separator = query.indexOf('=', start);
            if (separator > end || separator == -1) {
                separator = end;
            }

            if (separator - start == key.length() && query.regionMatches(start, key, 0, key.length())) {
                if (separator == end) {
                    return "";
                } else {
                    String encodedValue = query.substring(separator + 1, end);
                    try {
                        return URLDecoder.decode(encodedValue, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        Tunnel.LOG.log(Level.SEVERE, "encoding problem", e);
                        return encodedValue;
                    }
                }
            }

            // Move start to end of name.
            if (nextAmpersand != -1) {
                start = nextAmpersand + 1;
            } else {
                break;
            }
        } while (true);
        return null;
    }
}
