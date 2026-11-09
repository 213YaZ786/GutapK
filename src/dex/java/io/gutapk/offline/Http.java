package io.gutapk.offline;

/*
 * Request parsing and response writing of NanoHTTPD, cut down to what a local
 * cache server needs. Ported from
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/HTTPSession.java#execute
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/response/Response.java#send
 *
 * Not ported: decodeMultipartFormData, scipOverNewLine, getBoundaryPositions, parseBody, saveTmpFile, getTmpBucket (form and
 * multipart parsing: the body is handed over as bytes, the game's server reads it itself)
 * Not ported: decodeParms (the query string is forwarded to the origin exactly as it came)
 * Not ported: getCookies, getParms, getParameters (cookies and parameters travel inside the forwarded headers)
 * Not ported: sendBodyWithCorrectTransferAndEncoding, sendBodyWithCorrectEncoding, setChunkedTransfer, useGzipWhenAccepted, setUseGzip
 * (an answer always goes out with a Content-Length and no gzip, so a replayed body is the one that was stored)
 * Not ported: newChunkedResponse, setData, setMimeType, setStatus, addCookieHeader, getCookieHeaders, getData, getRequestMethod, getStatus, getMimeType, closeConnection
 * (nothing here changes an answer after it is built)
 *
 * Copyright (C) 2012 - 2016 nanohttpd
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.logging.Level;

public class Http {

    /** Answers one request, the part of NanoHTTPD that a subclass overrides in serve. */
    public interface Handler {
        Response handle(Http session) throws IOException;
    }

    public static final int BUFSIZE = 8192;

    /** A game request body larger than this is refused rather than held in memory. */
    public static final int MAX_BODY_SIZE = 32 * 1024 * 1024;

    static final String SHUTDOWN = "GutapK Shutdown";

    private final Handler httpd;

    private final OutputStream outputStream;

    private final BufferedInputStream inputStream;

    private int splitbyte;

    private int rlen;

    private String uri;

    private String method;

    private Map<String, String> headers;

    private byte[] body = new byte[0];

    private String queryParameterString;

    private String remoteIp;

    private String protocolVersion;

    public Http(Handler httpd, InputStream inputStream, OutputStream outputStream, String remoteIp) {
        this.httpd = httpd;
        this.inputStream = new BufferedInputStream(inputStream, Http.BUFSIZE);
        this.outputStream = outputStream;
        this.remoteIp = remoteIp;
        this.headers = new HashMap<String, String>();
    }

    /** Thrown by the parsing steps, answered with this status, like NanoHTTPD's ResponseException. */
    static final class ResponseException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int status;

        ResponseException(int status, String message) {
            super(message);
            this.status = status;
        }

        int getStatus() {
            return this.status;
        }
    }

    /**
     * Decodes the sent headers. The path stays percent-encoded, as the game wrote it.
     */
    private void decodeHeader(BufferedReader in, Map<String, String> pre, Map<String, String> headers) throws ResponseException {
        try {
            // Read the request line
            String inLine = in.readLine();
            if (inLine == null) {
                return;
            }

            StringTokenizer st = new StringTokenizer(inLine);
            if (!st.hasMoreTokens()) {
                throw new ResponseException(400, "BAD REQUEST: Syntax error. Usage: GET /example/file.html");
            }

            pre.put("method", st.nextToken());

            if (!st.hasMoreTokens()) {
                throw new ResponseException(400, "BAD REQUEST: Missing URI. Usage: GET /example/file.html");
            }

            String uri = st.nextToken();

            int qmi = uri.indexOf('?');
            if (qmi >= 0) {
                this.queryParameterString = uri.substring(qmi + 1);
                uri = uri.substring(0, qmi);
            } else {
                this.queryParameterString = "";
            }

            if (st.hasMoreTokens()) {
                protocolVersion = st.nextToken();
            } else {
                protocolVersion = "HTTP/1.1";
                Server.LOG.log(Level.FINE, "no protocol version specified, strange. Assuming HTTP/1.1.");
            }
            String line = in.readLine();
            while (line != null && !line.trim().isEmpty()) {
                int p = line.indexOf(':');
                if (p >= 0) {
                    headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
                }
                line = in.readLine();
            }

            pre.put("uri", uri);
        } catch (IOException ioe) {
            throw new ResponseException(500, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
        }
    }

    /**
     * Reads one request, hands it to the handler and writes the answer. A keep-alive
     * connection calls it again, and a SocketException ends the loop.
     */
    public void execute() throws IOException {
        Response r = null;
        try {
            // Read the first 8192 bytes.
            // The full header should fit in here.
            // Do NOT assume that a single read will get the entire header
            // at once!
            byte[] buf = new byte[Http.BUFSIZE];
            this.splitbyte = 0;
            this.rlen = 0;

            int read = -1;
            this.inputStream.mark(Http.BUFSIZE);
            try {
                read = this.inputStream.read(buf, 0, Http.BUFSIZE);
            } catch (IOException e) {
                Server.safeClose(this.inputStream);
                Server.safeClose(this.outputStream);
                throw new SocketException(SHUTDOWN);
            }
            if (read == -1) {
                // socket was been closed
                Server.safeClose(this.inputStream);
                Server.safeClose(this.outputStream);
                throw new SocketException(SHUTDOWN);
            }
            while (read > 0) {
                this.rlen += read;
                this.splitbyte = findHeaderEnd(buf, this.rlen);
                if (this.splitbyte > 0) {
                    break;
                }
                read = this.inputStream.read(buf, this.rlen, Http.BUFSIZE - this.rlen);
            }
            if (this.splitbyte == 0) {
                throw new ResponseException(431, "REQUEST HEADER TOO LARGE OR INCOMPLETE");
            }

            if (this.splitbyte < this.rlen) {
                this.inputStream.reset();
                skipFully(this.splitbyte);
            }

            this.headers.clear();

            // Create a BufferedReader for parsing the header.
            BufferedReader hin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf, 0, this.rlen), "ISO-8859-1"));

            // Decode the header into the method, the uri and the headers
            Map<String, String> pre = new HashMap<String, String>();
            decodeHeader(hin, pre, this.headers);

            if (null != this.remoteIp) {
                this.headers.put("remote-addr", this.remoteIp);
                this.headers.put("http-client-ip", this.remoteIp);
            }

            this.method = pre.get("method");
            if (this.method == null || !this.method.matches("[A-Z]+")) {
                throw new ResponseException(400, "BAD REQUEST: Syntax error. HTTP verb " + this.method + " unhandled.");
            }

            this.uri = pre.get("uri");

            String connection = this.headers.get("connection");
            boolean keepAlive = "HTTP/1.1".equals(protocolVersion) && (connection == null || !connection.matches("(?i).*close.*"));

            String transferEncoding = this.headers.get("transfer-encoding");
            if (transferEncoding != null && !"identity".equalsIgnoreCase(transferEncoding)) {
                throw new ResponseException(501, "NOT IMPLEMENTED: a chunked request body");
            }
            long size = getBodySize();
            if (size > Http.MAX_BODY_SIZE) {
                throw new ResponseException(413, "PAYLOAD TOO LARGE");
            }
            this.body = readBody((int) size);

            // Ok, now do the serve()
            r = httpd.handle(this);

            if (r == null) {
                throw new ResponseException(500, "SERVER INTERNAL ERROR: Serve() returned a null response.");
            } else {
                r.setRequestMethod(this.method);
                r.setKeepAlive(keepAlive);
                r.send(this.outputStream);
            }
            if (!keepAlive || r.isCloseConnection()) {
                throw new SocketException(SHUTDOWN);
            }
        } catch (SocketException e) {
            // throw it out to close socket object (finalAccept)
            throw e;
        } catch (SocketTimeoutException ste) {
            // treat socket timeouts the same way we treat socket exceptions
            // i.e. close the stream & finalAccept object by throwing the
            // exception up the call stack.
            throw ste;
        } catch (IOException ioe) {
            Response resp = Response.newFixedLengthResponse(500, "text/plain", "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
            resp.send(this.outputStream);
            Server.safeClose(this.outputStream);
        } catch (ResponseException re) {
            Response resp = Response.newFixedLengthResponse(re.getStatus(), "text/plain", re.getMessage());
            resp.send(this.outputStream);
            Server.safeClose(this.outputStream);
        }
    }

    /**
     * Find byte index separating header from body. It must be the last byte of
     * the first two sequential new lines.
     */
    private int findHeaderEnd(final byte[] buf, int rlen) {
        int splitbyte = 0;
        while (splitbyte + 1 < rlen) {

            // RFC2616
            if (buf[splitbyte] == '\r' && buf[splitbyte + 1] == '\n' && splitbyte + 3 < rlen && buf[splitbyte + 2] == '\r' && buf[splitbyte + 3] == '\n') {
                return splitbyte + 4;
            }

            // tolerance
            if (buf[splitbyte] == '\n' && buf[splitbyte + 1] == '\n') {
                return splitbyte + 2;
            }
            splitbyte++;
        }
        return 0;
    }

    /** InputStream.skip may skip less than asked, the header bytes must all go. */
    private void skipFully(long count) throws IOException {
        while (count > 0) {
            long skipped = this.inputStream.skip(count);
            if (skipped <= 0) {
                throw new SocketException(SHUTDOWN);
            }
            count -= skipped;
        }
    }

    /**
     * Deduce body length in bytes from the "content-length" header. Unlike
     * NanoHTTPD, bytes after the header are not a body: a request without a
     * content-length has none.
     */
    public long getBodySize() {
        if (this.headers.containsKey("content-length")) {
            try {
                return Math.max(0, Long.parseLong(this.headers.get("content-length")));
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }

    private byte[] readBody(int size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(size, Http.BUFSIZE));
        byte[] buf = new byte[Http.BUFSIZE];
        while (size > 0) {
            int got = this.inputStream.read(buf, 0, Math.min(size, buf.length));
            if (got < 0) {
                throw new SocketException(SHUTDOWN);
            }
            baos.write(buf, 0, got);
            size -= got;
        }
        return baos.toByteArray();
    }

    public final Map<String, String> getHeaders() {
        return this.headers;
    }

    public final String getMethod() {
        return this.method;
    }

    public final String getQueryParameterString() {
        return this.queryParameterString;
    }

    public final String getUri() {
        return this.uri;
    }

    public final byte[] getBody() {
        return this.body;
    }

    public String getRemoteIpAddress() {
        return this.remoteIp;
    }

    /**
     * An answer: status, content type, headers and a body held whole in memory.
     */
    public static class Response {

        private final int status;

        private final String mimeType;

        private final InputStream data;

        private final long contentLength;

        private final Map<String, String> header = new LinkedHashMap<String, String>();

        private final Map<String, String> lowerCaseHeader = new HashMap<String, String>();

        private String requestMethod;

        private boolean keepAlive;

        protected Response(int status, String mimeType, InputStream data, long totalBytes) {
            this.status = status;
            this.mimeType = mimeType;
            if (data == null) {
                this.data = new ByteArrayInputStream(new byte[0]);
                this.contentLength = 0L;
            } else {
                this.data = data;
                this.contentLength = totalBytes;
            }
            this.keepAlive = true;
        }

        public void close() throws IOException {
            if (this.data != null) {
                this.data.close();
            }
        }

        public void addHeader(String name, String value) {
            this.header.put(name, value);
            this.lowerCaseHeader.put(name.toLowerCase(Locale.US), value);
        }

        /**
         * @return {@code true} if connection is to be closed after this Response
         *         has been sent.
         */
        public boolean isCloseConnection() {
            return "close".equals(getHeader("connection"));
        }

        public String getHeader(String name) {
            return this.lowerCaseHeader.get(name.toLowerCase(Locale.US));
        }

        public void setKeepAlive(boolean useKeepAlive) {
            this.keepAlive = useKeepAlive;
        }

        public void setRequestMethod(String requestMethod) {
            this.requestMethod = requestMethod;
        }

        public int getStatusCode() {
            return this.status;
        }

        public static Response newFixedLengthResponse(int status, String mimeType, byte[] data) {
            return new Response(status, mimeType, new ByteArrayInputStream(data), data.length);
        }

        /** Create a text response with known length, in UTF-8. */
        public static Response newFixedLengthResponse(int status, String mimeType, String txt) {
            byte[] bytes = new byte[0];
            if (txt != null) {
                try {
                    bytes = txt.getBytes("UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    Server.LOG.log(Level.SEVERE, "encoding problem, responding nothing", e);
                }
            }
            return newFixedLengthResponse(status, mimeType + "; charset=UTF-8", bytes);
        }

        /** The status line text of the statuses a game server answers. */
        public static String describe(int status) {
            switch (status) {
                case 200: return "200 OK";
                case 201: return "201 Created";
                case 202: return "202 Accepted";
                case 204: return "204 No Content";
                case 206: return "206 Partial Content";
                case 301: return "301 Moved Permanently";
                case 302: return "302 Found";
                case 303: return "303 See Other";
                case 304: return "304 Not Modified";
                case 307: return "307 Temporary Redirect";
                case 308: return "308 Permanent Redirect";
                case 400: return "400 Bad Request";
                case 401: return "401 Unauthorized";
                case 403: return "403 Forbidden";
                case 404: return "404 Not Found";
                case 405: return "405 Method Not Allowed";
                case 408: return "408 Request Timeout";
                case 409: return "409 Conflict";
                case 413: return "413 Payload Too Large";
                case 429: return "429 Too Many Requests";
                case 431: return "431 Request Header Fields Too Large";
                case 500: return "500 Internal Server Error";
                case 501: return "501 Not Implemented";
                case 502: return "502 Bad Gateway";
                case 503: return "503 Service Unavailable";
                case 504: return "504 Gateway Timeout";
                default: return status + " Status";
            }
        }

        /**
         * Sends given response to the socket.
         */
        public void send(OutputStream outputStream) {
            SimpleDateFormat gmtFrmt = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));

            try {
                PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, "ISO-8859-1")), false);
                pw.append("HTTP/1.1 ").append(describe(this.status)).append(" \r\n");
                if (this.mimeType != null) {
                    printHeader(pw, "Content-Type", this.mimeType);
                }
                if (getHeader("date") == null) {
                    printHeader(pw, "Date", gmtFrmt.format(new Date()));
                }
                for (Map.Entry<String, String> entry : this.header.entrySet()) {
                    printHeader(pw, entry.getKey(), entry.getValue());
                }
                if (getHeader("connection") == null) {
                    printHeader(pw, "Connection", (this.keepAlive ? "keep-alive" : "close"));
                }
                long pending = this.data != null ? this.contentLength : 0;
                pending = sendContentLengthHeaderIfNotAlreadyPresent(pw, pending);
                pw.append("\r\n");
                pw.flush();
                // A HEAD answer and the statuses without a body keep their Content-Length and send no bytes.
                if (!"HEAD".equals(this.requestMethod) && this.status != 204 && this.status != 304) {
                    sendBody(outputStream, pending);
                }
                outputStream.flush();
                Server.safeClose(this.data);
            } catch (IOException ioe) {
                Server.LOG.log(Level.SEVERE, "Could not send response to the client", ioe);
            }
        }

        protected void printHeader(PrintWriter pw, String key, String value) {
            pw.append(key).append(": ").append(value).append("\r\n");
        }

        protected long sendContentLengthHeaderIfNotAlreadyPresent(PrintWriter pw, long defaultSize) {
            String contentLengthString = getHeader("content-length");
            long size = defaultSize;
            if (contentLengthString != null) {
                try {
                    size = Long.parseLong(contentLengthString);
                } catch (NumberFormatException ex) {
                    Server.LOG.severe("content-length was no number " + contentLengthString);
                }
            } else {
                pw.print("Content-Length: " + size + "\r\n");
            }
            return size;
        }

        /**
         * Sends the body to the specified OutputStream. The pending parameter
         * limits the maximum amounts of bytes sent unless it is -1, in which case
         * everything is sent.
         */
        private void sendBody(OutputStream outputStream, long pending) throws IOException {
            long BUFFER_SIZE = 16 * 1024;
            byte[] buff = new byte[(int) BUFFER_SIZE];
            boolean sendEverything = pending == -1;
            while (pending > 0 || sendEverything) {
                long bytesToRead = sendEverything ? BUFFER_SIZE : Math.min(pending, BUFFER_SIZE);
                int read = this.data.read(buff, 0, (int) bytesToRead);
                if (read <= 0) {
                    break;
                }
                outputStream.write(buff, 0, read);
                if (!sendEverything) {
                    pending -= read;
                }
            }
        }
    }
}
