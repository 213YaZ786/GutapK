package io.gutapk.local;

/*
 * The relay of Local: the game on each phone talks to its own address, and
 * the relay carries what it says to the host's phone sealed with the key of
 * the QR code (Aead). On the host it listens on the network and hands each
 * friend to the game's own server on 127.0.0.1; on a friend's phone it
 * listens where the game connects, 127.0.0.1 and the game's port, and carries
 * it to the host. A stream (TCP, a WebSocket game) is relayed connection by
 * connection; a datagram (UDP, an ENet game) is sealed alone, and a friend's
 * address is its session on the host, idle sessions closed.
 *
 * The relays of shadowsocks-java with blocking sockets and a thread per
 * direction in place of Netty's channels:
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/net/tcp/TCPRelayHandler.java#L27-L117
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/net/udp/UDPRelayHandler.java#L55-L110
 * https://github.com/xingty/shadowsocks-java/blob/main/src/main/java/org/wiyi/ss/net/udp/SSServerUDPRelayHandler.java#L35-L58
 * and the listener of NanoHTTPD, a server socket bound before start returns
 * and an accept loop on a daemon thread:
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/ServerRunnable.java#L60-L81
 * https://github.com/NanoHttpd/nanohttpd/blob/master/core/src/main/java/org/nanohttpd/protocols/http/NanoHTTPD.java#safeClose
 *
 * Not ported: channelRead0, getRelayInitializer, initChannel, onTargetChannelConnectFailure, exceptionCaught, IdleStateHandler, channelIdle (Netty's pipeline; a thread reads, a socket timeout ends an idle session)
 * Not ported: source, getSource (no channel is kept: each thread holds its two sockets)
 * Not ported: initRelayChannel, initAddress, getName, UDPConnectionHolder, SSRequest, AttrKeys (there is no SOCKS request: the target is the game's server or the host)
 * Not ported: bindException, hasBinded, getBindException (the socket is bound in start, which throws when the port is taken)
 *
 * Copyright 2020 xingty, Apache License 2.0.
 * Copyright (C) 2012 - 2016 nanohttpd, BSD 3-Clause, the licence text sits in Http.java.
 */

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Tunnel implements Closeable {
    public static final Logger LOG = Logger.getLogger("GutapK");

    /** The first chunk a friend sends: the host opens nothing for a stream that does not start with it. */
    static final byte[] HELLO = "GutapK Local 1".getBytes(StandardCharsets.US_ASCII);
    /** How long a friend tries each of the host's addresses, and the host waits for the first chunk. */
    static final int TIMEOUT = 3000;
    /** A friend's datagram session on the host ends after this long without a word. */
    static final int IDLE = 120000;
    private static final int DATAGRAM = 64 * 1024;

    private final byte[] ssKey;
    private final boolean host;
    private final boolean udp;
    /** On the host the game's server, on a friend's phone the host's relay. */
    private final InetSocketAddress target;
    /** The salts seen, so nothing recorded is played again. */
    private final PingPongBloom seen = new PingPongBloom();

    private volatile ServerSocket myServerSocket;
    private volatile DatagramSocket myDatagramSocket;
    private Thread myThread;
    private Thread myDatagramThread;
    /** On the host, the socket to the game's server of each friend's address. */
    private final Map<SocketAddress, DatagramSocket> sessions = new ConcurrentHashMap<SocketAddress, DatagramSocket>();
    /** On a friend's phone, the socket to the host, and where the game sends from. */
    private DatagramSocket upstream;
    private volatile SocketAddress game;

    private Tunnel(byte[] ssKey, boolean host, boolean udp, InetSocketAddress target) {
        this.ssKey = ssKey.clone();
        this.host = host;
        this.udp = udp;
        this.target = target;
    }

    /**
     * The host's relay: listens on every address, on port (0 for any, the
     * QR code says which), and hands each friend to the game's server on
     * 127.0.0.1:gamePort. A UDP game is relayed on the same port number; the
     * stream listener stays, a friend tries it to find the host.
     */
    public static Tunnel host(byte[] ssKey, int port, int gamePort, boolean udp) throws IOException {
        Tunnel tunnel = new Tunnel(ssKey, true, udp, new InetSocketAddress(InetAddress.getByName("127.0.0.1"), gamePort));
        tunnel.start(new InetSocketAddress(port));
        return tunnel;
    }

    /**
     * A friend's relay: finds which of the host's addresses answers, then
     * listens on 127.0.0.1:gamePort, where the game connects.
     */
    public static Tunnel join(byte[] ssKey, List<InetSocketAddress> hosts, int gamePort, boolean udp) throws IOException {
        Tunnel tunnel = new Tunnel(ssKey, false, udp, getRelayAddress(hosts));
        tunnel.start(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), gamePort));
        return tunnel;
    }

    /** The first of the host's addresses its relay answers on. */
    static InetSocketAddress getRelayAddress(List<InetSocketAddress> hosts) throws IOException {
        IOException last = new IOException("no address of the host");
        for (InetSocketAddress address : hosts) {
            Socket probe = new Socket();
            try {
                probe.connect(address, TIMEOUT);
                return address;
            } catch (IOException e) {
                last = e;
            } finally {
                safeClose(probe);
            }
        }
        throw last;
    }

    /** The port the relay listens on, for the QR code. */
    public int getListeningPort() {
        return this.myServerSocket == null ? -1 : this.myServerSocket.getLocalPort();
    }

    /** On the host the game's server, on a friend's phone where the host was found. */
    public InetSocketAddress getTarget() {
        return target;
    }

    private void start(InetSocketAddress bind) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(bind);
        this.myServerSocket = serverSocket;
        if (udp) {
            try {
                // The datagrams on the stream listener's port number: one port goes in the QR code.
                this.myDatagramSocket = new DatagramSocket(new InetSocketAddress(bind.getAddress(), serverSocket.getLocalPort()));
                if (!host) {
                    this.upstream = new DatagramSocket();
                }
            } catch (IOException e) {
                safeClose(serverSocket);
                safeClose(this.myDatagramSocket);
                throw e;
            }
            this.myDatagramThread = daemon(new Runnable() {
                @Override
                public void run() {
                    datagrams();
                }
            }, "GutapK Local datagrams " + getListeningPort());
            if (!host) {
                daemon(new Runnable() {
                    @Override
                    public void run() {
                        fromHost();
                    }
                }, "GutapK Local from host");
            }
        }
        this.myThread = daemon(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        }, "GutapK Local " + getListeningPort());
    }

    /** Stops the relay and every connection and session through it. */
    @Override
    public void close() {
        safeClose(this.myServerSocket);
        safeClose(this.myDatagramSocket);
        safeClose(this.upstream);
        for (DatagramSocket session : sessions.values()) {
            safeClose(session);
        }
        sessions.clear();
        try {
            if (this.myThread != null) {
                this.myThread.join();
            }
            if (this.myDatagramThread != null) {
                this.myDatagramThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void accept() {
        do {
            try {
                final Socket finalAccept = this.myServerSocket.accept();
                daemon(new Runnable() {
                    @Override
                    public void run() {
                        createTargetChannel(finalAccept);
                    }
                }, "GutapK Local stream");
            } catch (IOException e) {
                LOG.log(Level.FINE, "Communication with the client broken", e);
            }
        } while (!this.myServerSocket.isClosed());
    }

    /**
     * Connects one accepted connection to the target: on the host the
     * accepted one is sealed and the game's server plain, on a friend's phone
     * the other way round.
     */
    private void createTargetChannel(Socket accepted) {
        Socket socket = new Socket();
        try {
            if (host) {
                DataInputStream inputStream = new DataInputStream(accepted.getInputStream());
                // The friend proves the key before anything reaches the game.
                accepted.setSoTimeout(TIMEOUT);
                Aead opened = Aead.decrypt(ssKey, inputStream, seen);
                byte[] hello = opened.read(inputStream);
                if (hello == null) {
                    // A friend finding the host: nothing to relay.
                    closeConnection(accepted, socket);
                    return;
                }
                if (!Arrays.equals(hello, HELLO)) {
                    throw new GeneralSecurityException("not a GutapK friend");
                }
                accepted.setSoTimeout(0);
                socket.connect(target, TIMEOUT);
                socket.setTcpNoDelay(true);
                accepted.setTcpNoDelay(true);
                doRelay(accepted, socket, opened, inputStream, Aead.encrypt(ssKey, accepted.getOutputStream()));
            } else {
                socket.connect(target, TIMEOUT);
                socket.setTcpNoDelay(true);
                accepted.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                Aead sealing = Aead.encrypt(ssKey, out);
                sealing.write(out, HELLO, 0, HELLO.length);
                doRelay(socket, accepted, null, new DataInputStream(socket.getInputStream()), sealing);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Local stream ended", e);
            closeConnection(accepted, socket);
        } catch (GeneralSecurityException e) {
            LOG.log(Level.WARNING, "Local refused a stream from " + accepted.getRemoteSocketAddress() + ": " + e.getMessage());
            closeConnection(accepted, socket);
        }
    }

    /**
     * Relays until both sides end: the sealed socket's chunks opened into
     * the plain one, the plain one's bytes sealed into the sealed one.
     * opened is null while the sealed side's salt is still to read.
     */
    private void doRelay(final Socket sealed, final Socket plain, final Aead opened, final DataInputStream sealedIn, final Aead sealing) {
        Thread seal = daemon(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = plain.getInputStream();
                    OutputStream out = sealed.getOutputStream();
                    byte[] buffer = new byte[Aead.CHUNK_SIZE_MASK];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        sealing.write(out, buffer, 0, read);
                        out.flush();
                    }
                    sealed.shutdownOutput();
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Local stream ended", e);
                    closeConnection(sealed, plain);
                } catch (GeneralSecurityException e) {
                    LOG.log(Level.WARNING, "Local could not seal", e);
                    closeConnection(sealed, plain);
                }
            }
        }, "GutapK Local seal");
        try {
            Aead opening = opened != null ? opened : Aead.decrypt(ssKey, sealedIn, host ? seen : null);
            OutputStream out = plain.getOutputStream();
            byte[] data;
            while ((data = opening.read(sealedIn)) != null) {
                out.write(data);
                out.flush();
            }
            plain.shutdownOutput();
            seal.join();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Local stream ended", e);
        } catch (GeneralSecurityException e) {
            LOG.log(Level.WARNING, "Local refused a chunk from " + sealed.getRemoteSocketAddress() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeConnection(sealed, plain);
        }
    }

    private static void closeConnection(Socket sealed, Socket plain) {
        safeClose(plain);
        safeClose(sealed);
    }

    /**
     * The datagram listener: on the host, a friend's sealed datagram goes
     * to the game's server through that friend's session; on a friend's
     * phone, the game's datagram is sealed to the host.
     */
    private void datagrams() {
        byte[] buffer = new byte[DATAGRAM];
        DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
        while (!this.myDatagramSocket.isClosed()) {
            try {
                msg.setLength(buffer.length);
                this.myDatagramSocket.receive(msg);
                if (host) {
                    byte[] plaintext = Aead.decryptAll(ssKey, buffer, msg.getOffset(), msg.getLength(), seen);
                    initUDPRelayChannel(msg.getSocketAddress()).send(new DatagramPacket(plaintext, plaintext.length, target));
                } else {
                    game = msg.getSocketAddress();
                    byte[] ciphertext = Aead.encryptAll(ssKey, buffer, msg.getOffset(), msg.getLength());
                    upstream.send(new DatagramPacket(ciphertext, ciphertext.length, target));
                }
            } catch (GeneralSecurityException e) {
                LOG.log(Level.FINE, "Local dropped a datagram from " + msg.getSocketAddress() + ": " + e.getMessage());
            } catch (IOException e) {
                LOG.log(Level.FINE, "Local datagram not relayed", e);
            }
        }
    }

    /** The host's session of one friend: a socket to the game's server, its answers sealed back. */
    private DatagramSocket initUDPRelayChannel(final SocketAddress friend) throws IOException {
        DatagramSocket known = sessions.get(friend);
        if (known != null) {
            return known;
        }
        final DatagramSocket channel = new DatagramSocket();
        channel.setSoTimeout(IDLE);
        sessions.put(friend, channel);
        daemon(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[DATAGRAM];
                DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!channel.isClosed()) {
                        msg.setLength(buffer.length);
                        channel.receive(msg);
                        byte[] ciphertext = Aead.encryptAll(ssKey, buffer, msg.getOffset(), msg.getLength());
                        myDatagramSocket.send(new DatagramPacket(ciphertext, ciphertext.length, friend));
                    }
                } catch (SocketTimeoutException e) {
                    LOG.log(Level.FINE, "Local session of " + friend + " idle, closed");
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Local session of " + friend + " ended", e);
                } catch (GeneralSecurityException e) {
                    LOG.log(Level.WARNING, "Local could not seal", e);
                } finally {
                    sessions.remove(friend, channel);
                    safeClose(channel);
                }
            }
        }, "GutapK Local session " + friend);
        return channel;
    }

    /** On a friend's phone, the host's sealed datagrams opened to the game. */
    private void fromHost() {
        byte[] buffer = new byte[DATAGRAM];
        DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
        while (!upstream.isClosed()) {
            try {
                msg.setLength(buffer.length);
                upstream.receive(msg);
                SocketAddress to = game;
                if (to == null || !target.equals(msg.getSocketAddress())) {
                    continue;
                }
                byte[] plaintext = Aead.decryptAll(ssKey, buffer, msg.getOffset(), msg.getLength(), seen);
                myDatagramSocket.send(new DatagramPacket(plaintext, plaintext.length, to));
            } catch (GeneralSecurityException e) {
                LOG.log(Level.FINE, "Local dropped a datagram from the host: " + e.getMessage());
            } catch (IOException e) {
                LOG.log(Level.FINE, "Local datagram not relayed", e);
            }
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName(name);
        thread.start();
        return thread;
    }

    static void safeClose(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not close", e);
        }
    }
}
