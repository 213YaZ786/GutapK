package io.gutapk.offline;

/*
 * The admin channel: an abstract unix socket that only the adb daemon (uid 2000,
 * reached with adb forward tcp:N localabstract:name) and the app itself may use.
 * Android lets any app connect to an abstract socket, so the peer's uid, read
 * from the socket's credentials, is the check.
 *
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalSocket.java#getPeerCredentials
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/LocalServerSocket.java#accept
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/Credentials.java#getUid
 * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Process.java#myUid
 *
 * Not ported: getFileDescriptor, getLocalSocketAddress (nothing needs the descriptor or the address)
 * Not ported: getPeerCredentials, getUid, myUid (platform methods of the accepted socket and of the process, called from run)
 */

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import java.io.IOException;
import java.util.logging.Level;

public class Admin implements Runnable {

    private final LocalServerSocket impl;

    private final Http.Handler handler;

    /** Binds the abstract name now, so a name already taken is reported to the caller. */
    public Admin(String name, Http.Handler handler) throws IOException {
        this.impl = new LocalServerSocket(name);
        this.handler = handler;
    }

    public void start() {
        Thread thread = new Thread(this);
        thread.setDaemon(true);
        thread.setName("GutapK Admin");
        thread.start();
    }

    @Override
    public void run() {
        while (true) {
            LocalSocket accepted;
            try {
                accepted = impl.accept();
            } catch (IOException e) {
                Server.LOG.log(Level.FINE, "Admin socket closed", e);
                return;
            }
            try {
                Credentials credentials = accepted.getPeerCredentials();
                Peer peer = new Peer(credentials.getPid(), credentials.getUid(), credentials.getGid());
                if (!peer.isAllowed(Process.myUid())) {
                    Server.LOG.warning("Admin refused uid " + peer.getUid());
                    Server.safeClose(accepted);
                    continue;
                }
                Thread client = new Thread(new Server.ClientHandler(handler, accepted.getInputStream(), accepted.getOutputStream(), accepted, "adb"));
                client.setDaemon(true);
                client.setName("GutapK Admin Client");
                client.start();
            } catch (IOException e) {
                Server.LOG.log(Level.FINE, "Admin client broken", e);
                Server.safeClose(accepted);
            }
        }
    }

    public void close() throws IOException {
        impl.close();
    }
}
