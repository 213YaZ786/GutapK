package io.gutapk.offline;

/*
 * Sends the game's request to the original origin and turns the answer into a
 * recorded entry, after WireMock's reverse proxy. WireMock's render builds the
 * upstream request from the incoming one (url from the proxy base url, headers
 * copied except the ones it rewrites), executes it and copies the answer's
 * headers except the forbidden ones. Same steps here with HttpURLConnection.
 *
 * https://github.com/wiremock/wiremock/blob/master/wiremock-core/src/main/java/com/github/tomakehurst/wiremock/http/ProxyResponseRenderer.java
 *
 * Not ported: chooseClient (one client, no forward proxy mode)
 * Not ported: ProhibitedNetworkAddressException handling (the origin is the one GutapK wrote into the app)
 * Not ported: configureDelay, chunkedDribbleDelay, fromProxy (no simulated delays)
 * Not ported: stubCorsEnabled, preserveHostHeader, hostHeaderValue (Host is always the origin's, CORS is a browser matter)
 *
 * Copyright (C) 2011-2026 Thomas Akehurst, Apache License 2.0.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class ProxyResponseRenderer {

    /** A short connect so that no network is noticed fast, a longer read for a slow server. */
    public static final int CONNECT_TIMEOUT = 3000;
    public static final int READ_TIMEOUT = 15000;
    public static final String HOST_HEADER = "host";
    public static final String ACCEPT_ENCODING_HEADER = "accept-encoding";
    public static final String CONTENT_LENGTH = "content-length";

    /** The request headers that belong to the game's own connection, not to the one made upstream. */
    private static final String[] REMOVE_PROXY_REQUEST_HEADERS = {
        "connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "upgrade", "expect", "remote-addr", "http-client-ip"
    };

    /** The only response headers worth keeping: the rest describes the upstream connection. */
    private static final String[] TRANSFERRED_RESPONSE_HEADERS = {
        "content-type", "cache-control", "etag", "last-modified", "expires", "location", "retry-after", "content-language"
    };

    /**
     * Asks the origin and returns what it answered, ready to be recorded.
     *
     * @throws IOException when the origin cannot be reached or does not answer in time
     */
    public Store.Entry render(String origin, Http originalRequest) throws IOException {
        URL proxyUrl = getProxyUrl(origin, originalRequest);
        HttpURLConnection client = (HttpURLConnection) proxyUrl.openConnection();
        client.setConnectTimeout(CONNECT_TIMEOUT);
        client.setReadTimeout(READ_TIMEOUT);
        client.setInstanceFollowRedirects(false);
        client.setUseCaches(false);
        try {
            client.setRequestMethod(originalRequest.getMethod());
            addRequestHeaders(client, originalRequest);

            byte[] body = originalRequest.getBody();
            boolean originalRequestBodyExists = body != null && body.length > 0;

            if (originalRequestBodyExists) {
                client.setDoOutput(true);
                client.setFixedLengthStreamingMode(body.length);
                OutputStream sink = client.getOutputStream();
                try {
                    sink.write(body);
                } finally {
                    sink.close();
                }
            }

            int code = client.getResponseCode();
            Map<String, String> headers = headersFrom(client);
            InputStream source = code >= 400 ? client.getErrorStream() : client.getInputStream();
            byte[] httpResponse = new byte[0];
            if (source != null) {
                if ("gzip".equalsIgnoreCase(client.getContentEncoding())) {
                    source = new GZIPInputStream(source);
                }
                httpResponse = Store.readAll(source);
            }
            return new Store.Entry(origin, originalRequest.getMethod(), originalRequest.getUri(), originalRequest.getQueryParameterString(),
                    Store.sha256(body == null ? new byte[0] : body), code, headers, httpResponse, System.currentTimeMillis());
        } finally {
            client.disconnect();
        }
    }

    private URL getProxyUrl(String origin, Http originalRequest) throws IOException {
        String query = originalRequest.getQueryParameterString();
        return new URL(origin + originalRequest.getUri() + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    /** The 502 the game gets when the origin is out of reach and nothing was recorded. */
    public static Http.Response proxyResponseError(String type, Http originalRequest, Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return Http.Response.newFixedLengthResponse(502, "application/json",
                "{\"error\":\"" + type + " failure trying to reach the origin for " + originalRequest.getMethod() + " "
                        + originalRequest.getUri().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"message\":\""
                        + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }

    private Map<String, String> headersFrom(HttpURLConnection client) {
        Map<String, String> httpHeaders = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> httpHeader : client.getHeaderFields().entrySet()) {
            if (httpHeader.getKey() != null && !httpHeader.getValue().isEmpty() && responseHeaderShouldBeTransferred(httpHeader.getKey())) {
                httpHeaders.put(httpHeader.getKey(), httpHeader.getValue().get(httpHeader.getValue().size() - 1));
            }
        }
        return httpHeaders;
    }

    private void addRequestHeaders(HttpURLConnection client, Http originalRequest) {
        for (Map.Entry<String, String> httpHeader : originalRequest.getHeaders().entrySet()) {
            String lowerCaseKey = httpHeader.getKey().toLowerCase(Locale.ROOT);
            if (removeProxyRequestHeaders(lowerCaseKey)) {
                continue;
            }
            if (HOST_HEADER.equals(lowerCaseKey)) {
                addHostHeader(client);
            } else if (ACCEPT_ENCODING_HEADER.equals(lowerCaseKey)) {
                addAcceptEncodingHeader(client);
            } else if (!CONTENT_LENGTH.equals(lowerCaseKey)) {
                copyHeader(client, httpHeader.getKey(), httpHeader.getValue());
            }
        }
        addAcceptEncodingHeader(client);
    }

    /** The URL carries the origin's host, which is what the server must see: nothing to copy. */
    private void addHostHeader(HttpURLConnection client) {
    }

    /** Identity only: a stored body must be readable, and an override must find JSON, not gzip. */
    private void addAcceptEncodingHeader(HttpURLConnection client) {
        client.setRequestProperty("Accept-Encoding", "identity");
    }

    private static void copyHeader(HttpURLConnection client, String key, String value) {
        client.setRequestProperty(key, value);
    }

    private static boolean removeProxyRequestHeaders(String lowerCaseKey) {
        for (String name : REMOVE_PROXY_REQUEST_HEADERS) {
            if (name.equals(lowerCaseKey)) {
                return true;
            }
        }
        return false;
    }

    public boolean responseHeaderShouldBeTransferred(String key) {
        final String lowerCaseKey = key.toLowerCase(Locale.ROOT);
        for (String name : TRANSFERRED_RESPONSE_HEADERS) {
            if (name.equals(lowerCaseKey)) {
                return true;
            }
        }
        return false;
    }
}
