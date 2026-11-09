package io.gutapk.offline;

/*
 * The listener of NanoHTTPD: a server socket, an accept loop on a daemon thread
 * and one thread per client. Ported from
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/NanoHTTPD.java#start
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/ServerRunnable.java
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/ClientHandler.java
 *
 * Not ported: getBindException, hasBinded (the socket is bound in start, which throws when the port is taken)
 * Not ported: createServerRunnable, createClientHandler (the two runnables are built here)
 * Not ported: setAsyncRunner, setTempFileManagerFactory, setServerSocketFactory, getServerSocketFactory (one thread per client, no temp files)
 * Not ported: makeSecure, mimeTypes, getMimeTypeForFile, decodeParameters, decodePercent, handle, serve (the handler answers, nothing is served from files)
 * Not ported: wasStarted, isAlive (nothing asks)
 *
 * Copyright (C) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Server {

    /** On Android java.util.logging reaches logcat, the logger name becomes the tag. */
    public static final Logger LOG = Logger.getLogger("GutapK");

    /**
     * Maximum time to wait on Socket.getInputStream().read() (in milliseconds)
     * This is required as the Keep-Alive HTTP connections would otherwise block
     * the socket reading thread forever (or as long the browser is open).
     */
    public static final int SOCKET_READ_TIMEOUT = 5000;

    public final String hostname;

    public final int myPort;

    private volatile ServerSocket myServerSocket;

    private final Http.Handler handler;

    private Thread myThread;

    public Server(String hostname, int port, Http.Handler handler) {
        this.hostname = hostname;
        this.myPort = port;
        this.handler = handler;
    }

    public static final void safeClose(Object closeable) {
        try {
            if (closeable != null) {
                if (closeable instanceof Closeable) {
                    ((Closeable) closeable).close();
                } else if (closeable instanceof Socket) {
                    ((Socket) closeable).close();
                } else if (closeable instanceof ServerSocket) {
                    ((ServerSocket) closeable).close();
                } else {
                    throw new IllegalArgumentException("Unknown object to close");
                }
            }
        } catch (IOException e) {
            Server.LOG.log(Level.SEVERE, "Could not close", e);
        }
    }

    public ServerSocket getMyServerSocket() {
        return myServerSocket;
    }

    public final int getListeningPort() {
        return this.myServerSocket == null ? -1 : this.myServerSocket.getLocalPort();
    }

    /**
     * Start the server.
     *
     * @throws IOException
     *             if the socket is in use.
     */
    public void start() throws IOException {
        start(Server.SOCKET_READ_TIMEOUT);
    }

    /**
     * Starts the server (in setDaemon(true) mode).
     */
    public void start(final int timeout) throws IOException {
        start(timeout, true);
    }

    /**
     * Start the server. The socket is bound before this returns, on the calling
     * thread: the game's first request, a few milliseconds later, must find it
     * listening, and a port already taken must be reported here.
     *
     * @param timeout
     *            timeout to use for socket connections.
     * @param daemon
     *            start the thread daemon or not.
     * @throws IOException
     *             if the socket is in use.
     */
    public void start(final int timeout, boolean daemon) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(hostname != null ? new InetSocketAddress(hostname, myPort) : new InetSocketAddress(myPort));
        this.myServerSocket = serverSocket;

        ServerRunnable serverRunnable = new ServerRunnable(this, timeout);
        this.myThread = new Thread(serverRunnable);
        this.myThread.setDaemon(daemon);
        this.myThread.setName("GutapK Listener " + getListeningPort());
        this.myThread.start();
    }

    /**
     * Stop the server.
     */
    public void stop() {
        try {
            safeClose(this.myServerSocket);
            if (this.myThread != null) {
                this.myThread.join();
            }
        } catch (Exception e) {
            Server.LOG.log(Level.SEVERE, "Could not stop all connections", e);
        }
    }

    /**
     * The runnable that will be used for the main listening thread.
     */
    public static class ServerRunnable implements Runnable {

        private Server httpd;

        private final int timeout;

        public ServerRunnable(Server httpd, int timeout) {
            this.httpd = httpd;
            this.timeout = timeout;
        }

        @Override
        public void run() {
            do {
                try {
                    final Socket finalAccept = httpd.getMyServerSocket().accept();
                    if (this.timeout > 0) {
                        finalAccept.setSoTimeout(this.timeout);
                    }
                    final InputStream inputStream = finalAccept.getInputStream();
                    Thread t = new Thread(new ClientHandler(httpd.handler, inputStream, finalAccept.getOutputStream(), finalAccept, "127.0.0.1"));
                    t.setDaemon(true);
                    t.setName("GutapK Client " + httpd.myPort);
                    t.start();
                } catch (IOException e) {
                    Server.LOG.log(Level.FINE, "Communication with the client broken", e);
                }
            } while (!httpd.getMyServerSocket().isClosed());
        }
    }

    /**
     * The runnable that will be used for every new client connection. The
     * admin socket uses it too, with a local socket for acceptSocket.
     */
    public static class ClientHandler implements Runnable {

        private final Http.Handler httpd;

        private final InputStream inputStream;

        private final OutputStream outputStream;

        private final Closeable acceptSocket;

        private final String remoteIp;

        public ClientHandler(Http.Handler httpd, InputStream inputStream, OutputStream outputStream, Closeable acceptSocket, String remoteIp) {
            this.httpd = httpd;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.acceptSocket = acceptSocket;
            this.remoteIp = remoteIp;
        }

        public void close() {
            Server.safeClose(this.inputStream);
            Server.safeClose(this.acceptSocket);
        }

        @Override
        public void run() {
            try {
                Http session = new Http(httpd, this.inputStream, this.outputStream, this.remoteIp);
                while (true) {
                    session.execute();
                }
            } catch (Exception e) {
                // When the socket is closed by the client,
                // we throw our own SocketException
                // to break the "keep alive" loop above. If
                // the exception was anything other
                // than the expected SocketException OR a
                // SocketTimeoutException, print the
                // stacktrace
                if (!(e instanceof SocketException && Http.SHUTDOWN.equals(e.getMessage())) && !(e instanceof SocketTimeoutException)) {
                    Server.LOG.log(Level.FINE, "Communication with the client broken, or an bug in the handler code", e);
                }
            } finally {
                Server.safeClose(this.outputStream);
                Server.safeClose(this.inputStream);
                Server.safeClose(this.acceptSocket);
            }
        }
    }
}
