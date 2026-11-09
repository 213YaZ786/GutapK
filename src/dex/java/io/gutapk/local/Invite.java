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
import java.util.Locale;
import java.util.Map;
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
    private final String label;
    private final Glass glass;

    /**
     * The page of the game packageName, named label, which offers apk to a
     * friend without it, in the colours of the host's glass.
     */
    public Invite(String packageName, File apk, String label, Glass glass) {
        this.packageName = packageName;
        this.apk = apk;
        this.label = label;
        this.glass = glass;
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
        // HelloServer's page, in the friend's language (its browser's header
        // as HelloServer reads its parms) and in the host's Material You glass
        // as Kyant's liquid glass draws a capsule over a backdrop: the host's
        // colour roles, a blurred backdrop of halos, frosted panels.
        // https://github.com/NanoHttpd/nanohttpd/blob/master/samples/src/main/java/org/nanohttpd/samples/http/HelloServer.java#L63-L80
        // https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/IHTTPSession.java#getHeaders
        // https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt#L36-L60
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#surface
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#surfaceContainerLow
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#onSurface
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#onSurfaceVariant
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#primary
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#onPrimary
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#primaryContainer
        // https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/MaterialDynamicColors.java#tertiary
        // not ported: serve (Http.Handler names it handle), getParms, username and the form (the page asks nothing)
        // not ported: LiquidButton, vibrancy, lens, interactiveHighlight, onClick (Compose's: the capsule is a link styled in CSS)
        // not ported: colorSpec, DynamicColor (the roles come from the host's Glass, read from the phone's shades)
        // not ported: surface, surfaceContainerLow, onSurface, onSurfaceVariant, primary, onPrimary, primaryContainer, tertiary (the functions: here they are Glass's fields of the same names)
        Map<String, String> parms = session.getHeaders();
        boolean fr = parms.get("accept-language") != null && parms.get("accept-language").startsWith("fr");
        String name = htmlEncode(label);
        long size = apk != null && apk.isFile() ? Math.max(1, Math.round(apk.length() / 1048576.0)) : 0;
        int white = 0xFFFFFFFF;
        String msg = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>" + name + "</title><style>\n";
        msg += "body{margin:0;min-height:100vh;display:flex;flex-direction:column;font-family:system-ui,Roboto,sans-serif;background:" + rgba(glass.surface) + ";color:" + rgba(glass.onSurface) + "}\n";
        msg += ".backdrop{position:fixed;inset:-40px;z-index:-1;filter:blur(40px);background:"
            + "radial-gradient(circle at 15% 18%," + rgba(Glass.copy(glass.primary, 0.35f)) + ",transparent 55%),"
            + "radial-gradient(circle at 95% 45%," + rgba(Glass.copy(glass.tertiary, 0.30f)) + ",transparent 60%),"
            + "radial-gradient(circle at 25% 85%," + rgba(Glass.copy(glass.primaryContainer, 0.80f)) + ",transparent 55%),"
            + "radial-gradient(circle at 80% 95%," + rgba(Glass.copy(glass.primary, 0.22f)) + ",transparent 40%)}\n";
        msg += "main{flex:1;display:flex;flex-direction:column;gap:14px;box-sizing:border-box;width:100%;max-width:440px;margin:0 auto;padding:24px 20px}\n";
        msg += ".zone{background:" + rgba(glass.night ? Glass.copy(glass.surfaceContainerLow, 0.40f) : Glass.copy(white, 0.42f)) + ";-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);"
            + "border:1px solid " + rgba(Glass.copy(white, glass.night ? 0.14f : 0.75f)) + ";border-radius:24px;padding:14px 16px}\n";
        msg += ".chip{align-self:center;border-radius:999px;padding:8px 14px;font-size:12px;color:" + rgba(glass.onSurfaceVariant) + "}\n";
        msg += ".hero{display:flex;flex-direction:column;align-items:center;gap:10px;margin-top:10vh;text-align:center}\n";
        msg += ".tile{width:84px;height:84px;border-radius:26px;display:flex;align-items:center;justify-content:center;font-size:36px;font-weight:500;background:" + rgba(glass.primary) + ";color:" + rgba(glass.onPrimary) + "}\n";
        msg += "h1{margin:0;font-size:24px;font-weight:500}p{margin:0;color:" + rgba(glass.onSurfaceVariant) + "}.grow{flex:1}\n";
        msg += "a{color:inherit;text-decoration:none}.capsule{display:block;text-align:center;border-radius:999px;padding:16px;font-size:16px;font-weight:500;background:" + rgba(glass.primary) + ";color:" + rgba(glass.onPrimary) + "}\n";
        msg += ".download{display:flex;gap:14px;align-items:center}.download b{font-weight:500}.download small{color:" + rgba(glass.onSurfaceVariant) + "}.arrow{font-size:22px;color:" + rgba(glass.primary) + "}\n";
        msg += "</style></head><body><div class=backdrop></div><main>\n";
        msg += "<div class='zone chip' id=host></div>\n";
        msg += "<div class=hero><div class=tile>" + (name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT)) + "</div><h1>" + name + "</h1>"
            + "<p>" + (fr ? "Votre ami vous invite à jouer" : "Your friend invites you to play") + "</p></div>\n";
        msg += "<div class=grow></div>\n";
        msg += "<a id=open class=capsule>" + (fr ? "Ouvrir le jeu" : "Open the game") + "</a>\n";
        msg += "<a class='zone download' href='" + APK.substring(1) + "'><span class=arrow>↓</span><span><b>" + (fr ? "Pas encore le jeu ?" : "No game yet?") + "</b><br>"
            + "<small>" + (fr ? "Le télécharger chez votre ami" : "Download it from your friend") + (size > 0 ? " · " + size + (fr ? " Mo" : " MB") : "") + "</small></span></a>\n";
        msg += "</main>\n"
            // The key never leaves the friend's phone: the # part stays in the browser.
            + "<script>document.getElementById('host').textContent=location.host;"
            + "var l='intent://" + packageName + JOIN + "?" + HOST + "='+location.hostname+'&'+location.hash.slice(1)"
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

    /** s with the characters HTML gives a meaning to written as references: the game's name on the page. */
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/text/TextUtils.java#htmlEncode
    static String htmlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        char c;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            switch (c) {
            case '<':
                sb.append("&lt;");
                break;
            case '>':
                sb.append("&gt;");
                break;
            case '&':
                sb.append("&amp;");
                break;
            case '\'':
                // &apos; does not appear in HTML 4: &#39; works everywhere.
                sb.append("&#39;");
                break;
            case '"':
                sb.append("&quot;");
                break;
            default:
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** color as CSS writes it, its alpha, red, green and blue read as Color reads them. */
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Color.java#L1242-L1271
    // not ported: alpha, red, green, blue (the four one-line getters, written inline)
    static String rgba(int color) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >>> 24) / 255f);
    }
}
