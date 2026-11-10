package io.gutapk.offline;

/*
 * The listener of NanoHTTPD: a server socket, an accept loop on a daemon thread
 * and one thread per client. Ported from
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/NanoHTTPD.java#start
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/ServerRunnable.java
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/ClientHandler.java
 *
 * The accept loop counts its clients the way Tomcat's acceptor does, so that no more than MAX_CONNECTIONS threads run at once
 * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/Acceptor.java#run
 * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#countUpOrAwaitConnection
 * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#countDownConnection
 * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/Acceptor.java#handleExceptionWithDelay
 *
 * Not ported: Acceptor pause (isPaused, AcceptorState, pauseStart: the listener is never paused, stop closes the socket)
 * Not ported: Acceptor stopCalled, stopLatch, stopMillis, getState, setThreadName, getThreadName (stop closes the server socket and joins the thread)
 * Not ported: Acceptor destroySocket (a socket is never accepted while stopping: accept fails on the closed server socket)
 * Not ported: Acceptor ExceptionUtils.handleThrowable (it rethrows VirtualMachineError, which would kill the app here)
 * Not ported: AbstractEndpoint maxConnections == -1 (counting off: the limit is always on), initializeConnectionLatch, releaseConnectionLatch, releaseAll at stop (the latch is static and shared by every server, stop must not release the others)
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

    /**
     * How many clients may be open at once, over every server of the app. Each one is a thread, and a
     * body may be 32 MB: any app on the phone can reach the loopback ports, so without a limit a loop
     * of connects ends in an OutOfMemoryError. 64 is far above what a game opens.
     */
    public static final int MAX_CONNECTIONS = 64;

    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;

    /** One latch for all the servers, as the memory and the threads they use are one. */
    private static final LimitLatch connectionLimitLatch = new LimitLatch(MAX_CONNECTIONS);

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

    /**
     * Count up or await a connection slot.
     * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#countUpOrAwaitConnection
     */
    static void countUpOrAwaitConnection() throws InterruptedException {
        connectionLimitLatch.countUpOrAwait();
    }

    /**
     * Count down a connection, -1 when the caller holds no latch (the admin socket counts nothing).
     * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#countDownConnection
     */
    static long countDownConnection(LimitLatch latch) {
        if (latch != null) {
            long result = latch.countDown();
            if (result < 0) {
                Server.LOG.warning("Incorrect connection count, the latch was counted down more often than up");
            }
            return result;
        } else {
            return -1;
        }
    }

    /**
     * Count down a connection of the shared latch.
     * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#countDownConnection
     */
    static long countDownConnection() {
        return countDownConnection(connectionLimitLatch);
    }

    /**
     * Check if the endpoint is running: its server socket is open until stop closes it.
     * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/AbstractEndpoint.java#isRunning
     */
    boolean isRunning() {
        return !getMyServerSocket().isClosed();
    }

    /** The open clients now, for a test. */
    static long connections() {
        return connectionLimitLatch.getCount();
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
                // The listener may wait for a free slot, where closing the socket does not reach it.
                this.myThread.interrupt();
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

        private final Server endpoint;

        private final int timeout;

        public ServerRunnable(Server endpoint, int timeout) {
            this.endpoint = endpoint;
            this.timeout = timeout;
        }

        /**
         * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/Acceptor.java#run
         */
        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until the server socket is closed
            while (endpoint.isRunning()) {
                try {
                    // if we have reached max connections, wait
                    Server.countUpOrAwaitConnection();

                    Socket socket;
                    try {
                        // Accept the next incoming connection from the server socket
                        socket = serverSocketAccept();
                    } catch (Exception e) {
                        // We didn't get a socket
                        Server.countDownConnection();
                        if (endpoint.isRunning()) {
                            // Introduce delay if necessary
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw e;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket: setSocketOptions() hands it to its own thread,
                    // which counts the connection down when it ends
                    if (!setSocketOptions(socket)) {
                        closeSocket(socket);
                    }
                } catch (InterruptedException e) {
                    // stop() interrupts a listener that waits for a slot, isRunning then ends the loop
                } catch (Throwable t) {
                    // Tomcat's handleThrowable rethrows the VirtualMachineErrors. Here nothing may
                    // escape: a thread that cannot be created is the OutOfMemoryError this limit is
                    // meant to survive, and an Error reaching the end of this thread would kill the
                    // whole app (see ClientHandler.run).
                    Server.LOG.log(Level.FINE, "Communication with the client broken", t);
                }
            }
        }

        /**
         * Handles exceptions where a delay is required to prevent a Thread from entering a tight loop which will consume
         * CPU and may also trigger large amounts of logging. For example, this can happen if the ulimit for open files is
         * reached.
         * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/Acceptor.java#handleExceptionWithDelay
         */
        protected int handleExceptionWithDelay(int currentErrorDelay) {
            // Don't delay on first exception
            if (currentErrorDelay > 0) {
                try {
                    Thread.sleep(currentErrorDelay);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            // On subsequent exceptions, start the delay at 50ms, doubling the delay
            // on every subsequent exception until the delay reaches 1.6 seconds.
            if (currentErrorDelay == 0) {
                return INITIAL_ERROR_DELAY;
            } else if (currentErrorDelay < MAX_ERROR_DELAY) {
                return currentErrorDelay * 2;
            } else {
                return MAX_ERROR_DELAY;
            }
        }

        private Socket serverSocketAccept() throws IOException {
            return endpoint.getMyServerSocket().accept();
        }

        /** Counts the connection down again when the socket cannot be handed on, as Tomcat's closeSocket does. */
        private void closeSocket(Socket socket) {
            Server.countDownConnection();
            Server.safeClose(socket);
        }

        private boolean setSocketOptions(Socket socket) {
            try {
                if (this.timeout > 0) {
                    socket.setSoTimeout(this.timeout);
                }
                final InputStream inputStream = socket.getInputStream();
                Thread t = new Thread(new ClientHandler(endpoint.handler, inputStream, socket.getOutputStream(), socket, "127.0.0.1", connectionLimitLatch));
                t.setDaemon(true);
                t.setName("GutapK Client " + endpoint.myPort);
                t.start();
                return true;
            } catch (Throwable t) {
                Server.LOG.log(Level.FINE, "Could not set the socket up", t);
                return false;
            }
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

        /** The latch this client holds a share of, null when it was not counted (the admin socket). */
        private final LimitLatch connectionLimitLatch;

        /** A client nothing counts, as the admin socket builds it. */
        public ClientHandler(Http.Handler httpd, InputStream inputStream, OutputStream outputStream, Closeable acceptSocket, String remoteIp) {
            this(httpd, inputStream, outputStream, acceptSocket, remoteIp, null);
        }

        public ClientHandler(Http.Handler httpd, InputStream inputStream, OutputStream outputStream, Closeable acceptSocket, String remoteIp, LimitLatch connectionLimitLatch) {
            this.httpd = httpd;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.acceptSocket = acceptSocket;
            this.remoteIp = remoteIp;
            this.connectionLimitLatch = connectionLimitLatch;
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
            } catch (StackOverflowError | OutOfMemoryError e) {
                // Android's RuntimeInit KillApplicationHandler kills the whole app on a throwable no thread
                // caught. A request made to overflow the stack or fill the heap must cost its own client,
                // not the game: log, close in finally, never rethrow.
                Server.LOG.log(Level.WARNING, "Client dropped: " + e.getClass().getName(), e);
            } finally {
                Server.safeClose(this.outputStream);
                Server.safeClose(this.inputStream);
                Server.safeClose(this.acceptSocket);
                // The share taken before accept goes back once, whatever ended the client
                Server.countDownConnection(this.connectionLimitLatch);
            }
        }
    }
}
