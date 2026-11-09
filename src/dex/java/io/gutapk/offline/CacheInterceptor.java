package io.gutapk.offline;

/*
 * Serves a request from the origin, records the answer, and falls back on the
 * recorded one when the origin is out of reach. OkHttp's CacheInterceptor
 * consults the cache first and goes to the network when it must. This one asks
 * the network first, since the game wants live data whenever there is a
 * network, and answers from the cache when the call fails. The cache hit
 * is the exact request first, then the nearest one recorded for the endpoint
 * (Store.nearest), and a miss is a 502 and not OkHttp's 504 only-if-cached.
 * An origin the user chose to block is answered at once, as mitmproxy's block
 * list answers a matching request, and never reaches the network or the cache.
 *
 * https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#request
 * https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/cache/CacheInterceptor.kt#intercept
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/http/ProxyResponseRenderer.java#proxyResponseError
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/response/Response.java#addHeader
 * https://github.com/ktorio/ktor-samples/blob/main/reverse-proxy/src/main/kotlin/io/ktor/samples/reverseproxy/ReverseProxyApplication.kt#L41-L52
 *
 * Not ported: addHeader (it is Http.Response.addHeader, called here)
 * Not ported: proxyResponseError (it is ProxyResponseRenderer.proxyResponseError, called here)
 * Not ported: stripBody, combine, isEndToEnd, isContentSpecificHeader, cacheWritingResponse (a recorded body is whole in memory, and no conditional get is made)
 * Not ported: requestForCache, trackResponse, eventListener calls (no listeners, lastSeen holds the mode)
 * Not ported: the ktor sample's text/html body rewrite (a page is served as recorded, the overrides edit it)
 *
 * Copyright (C) 2016 Square, Inc., Apache License 2.0.
 */

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CacheInterceptor implements Http.Handler {

    public static final String HEADER = "X-GutapK";
    public static final String LIVE = "live";
    public static final String REPLAY = "replay";
    public static final String MISS = "miss";
    public static final String BLOCKED = "blocked";

    /** What the last request got, for the admin status. */
    public static volatile String lastSeen = "none";

    private final String origin;
    private final Store cache;
    private final Overrides overrides;
    private final ProxyResponseRenderer proceed;

    /** Every origin the app was moved off, to the address it holds instead. */
    private final Map<String, String> targets;

    /** BlockSpec's status_code for this origin, 0 when the origin is not in the block list. */
    private final int status_code;

    public CacheInterceptor(String origin, Store cache, Overrides overrides, ProxyResponseRenderer proceed, Map<String, String> targets) {
        this(origin, cache, overrides, proceed, targets, 0);
    }

    public CacheInterceptor(String origin, Store cache, Overrides overrides, ProxyResponseRenderer proceed, Map<String, String> targets, int status_code) {
        this.origin = origin;
        this.targets = targets;
        this.cache = cache;
        this.overrides = overrides;
        this.proceed = proceed;
        this.status_code = status_code;
    }

    @Override
    public Http.Response handle(Http chain) throws IOException {
        return intercept(chain);
    }

    public Http.Response intercept(Http chain) throws IOException {
        // A blocked origin is answered before anything else: no upgrade check, no network, no record.
        Http.Response blocked = request(chain);
        if (blocked != null) {
            return blocked;
        }
        String upgrade = chain.getHeaders().get("upgrade");
        if (upgrade != null && upgrade.toLowerCase().contains("websocket")) {
            return error(501, "websocket", "WebSocket upgrades are not served by the offline cache");
        }
        String method = chain.getMethod();
        String bodyHash = Store.sha256(chain.getBody());

        Store.Entry networkResponse = null;
        IOException failure = null;
        try {
            networkResponse = proceed.render(origin, chain);
        } catch (ProtocolException e) {
            return error(501, "method", "HttpURLConnection cannot send " + method);
        } catch (IOException e) {
            failure = e;
        }

        if (networkResponse != null) {
            if (networkResponse.code >= 200 && networkResponse.code < 400) {
                // Offer this response to the cache.
                cache.put(networkResponse, chain.getBody());
            }
            lastSeen = LIVE;
            return response(networkResponse, LIVE);
        }

        // The network failed: the exact request if it was seen, else the nearest of its endpoint.
        Store.Entry cacheCandidate = cache.get(method, origin, chain.getUri(), chain.getQueryParameterString(), bodyHash);
        Store.Entry cacheResponse = cacheCandidate != null ? cacheCandidate
                : cache.nearest(method, origin, chain.getUri(), chain.getQueryParameterString(), chain.getBody());

        // If the network failed and the cache is insufficient, fail.
        if (cacheResponse == null) {
            lastSeen = MISS;
            Http.Response unsatisfiable = ProxyResponseRenderer.proxyResponseError("Network", chain, failure);
            unsatisfiable.addHeader(HEADER, MISS);
            return unsatisfiable;
        }
        lastSeen = REPLAY;
        return response(cacheResponse, REPLAY);
    }

    private Http.Response response(Store.Entry cacheResponse, String via) {
        byte[] body = overrides.apply(cacheResponse.endpoint(), cacheResponse.contentType(), cacheResponse.body);
        Http.Response response = Http.Response.newFixedLengthResponse(cacheResponse.code, cacheResponse.contentType(), body);
        for (Map.Entry<String, String> headers : cacheResponse.responseHeaders.entrySet()) {
            if ("location".equalsIgnoreCase(headers.getKey())) {
                // Propagates the location header, removing the origin's domain from
                // it as the ktor sample does with Wikipedia's, so a WebView stays on
                // this server instead of opening the real site. A redirect to another
                // origin the app was moved off goes to that one's address.
                // https://github.com/ktorio/ktor-samples/blob/main/reverse-proxy/src/main/kotlin/io/ktor/samples/reverseproxy/ReverseProxyApplication.kt#L41-L52
                String location = headers.getValue();
                for (Map.Entry<String, String> target : targets.entrySet()) {
                    boolean own = target.getKey().equalsIgnoreCase(origin);
                    String domain = "(?i)^" + Pattern.quote(target.getKey()) + (own ? "(?:/|(?=[?#]|$))" : "(?=[/?#]|$)");
                    location = location.replaceFirst(domain, own ? "/" : Matcher.quoteReplacement(target.getValue()));
                }
                response.addHeader(headers.getKey(), location);
            } else if (!"content-type".equalsIgnoreCase(headers.getKey())) {
                response.addHeader(headers.getKey(), headers.getValue());
            }
        }
        response.addHeader(HEADER, via);
        return response;
    }

    /**
     * The block list's request hook: a request of a blocked origin is answered
     * with an empty response of the chosen status and never sent upstream.
     * This interceptor serves one origin, so its one spec matches every
     * request it gets. Null when the origin is not blocked.
     * https://github.com/mitmproxy/mitmproxy/blob/main/mitmproxy/addons/blocklist.py#request
     * Not ported: flow.response, flow.error, flow.live (a request here has no answer yet, no error and is always live)
     * Not ported: flow.metadata (nothing reads which flows were blocked)
     * Not ported: flow.kill and NO_RESPONSE (a connection is never dropped, the app gets its status)
     * Not ported: the Server header (X-GutapK says who answered)
     */
    private Http.Response request(Http flow) {
        if (status_code == 0) {
            return null;
        }
        Http.Response response = Http.Response.newFixedLengthResponse(status_code, "text/plain", new byte[0]);
        response.addHeader(HEADER, BLOCKED);
        return response;
    }

    private Http.Response error(int code, String message, String detail) {
        Http.Response response = Http.Response.newFixedLengthResponse(code, "application/json",
                "{\"error\":\"" + message + "\",\"message\":\"" + detail + "\"}");
        response.addHeader(HEADER, MISS);
        return response;
    }
}
