package io.gutapk.local;

/*
 * The Local screen GutapK adds to a game. From its launcher entry, Host starts
 * the relay, the page of the invitation and, with the game's next screen, its
 * server in a second engine, and shows the QR code friends scan; Play opens
 * the game. From a friend's invitation (gutapk://<package>/join?...) the relay
 * to the host starts and the game opens, talking to its own address as
 * always. The servers are static: they outlive the screen, the game's process
 * keeps them. Built as ShareViaHttp's activities share a file, its link shown
 * as text and as a QR code, with ZXing's bitmap of the code and the views of
 * ApiDemos made in code:
 * https://github.com/marcosdiez/shareviahttp/blob/master/ShareViaHttp/app/src/main/java/com/MarcosDiez/shareviahttp/activities/BaseActivity.java#L45-L217
 * https://github.com/marcosdiez/shareviahttp/blob/master/ShareViaHttp/app/src/main/java/com/MarcosDiez/shareviahttp/activities/SendFileActivity.java#L48-L68
 * https://github.com/zxing/zxing/blob/master/android/src/com/google/zxing/client/android/encode/QRCodeEncoder.java#L329-L359
 * https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/view/ScrollView2.java
 *
 * Not ported: HANDLER_CONNECTION_START, HANDLER_CONNECTION_END, sendConnectionStartMessage, sendConnectionEndMessage, mHandler, handleMessage (the game shows who joined)
 * Not ported: setupTextViews, setupNavigationViews (no layout file to find the views in: onCreate makes them)
 * Not ported: setupToolbar, share, changeIp, createChangeIpDialog, saveServerUrlToClipboard (the code is for a camera, the link under it can be selected)
 * Not ported: populateUriPath, uriList, getFileUris, getUrisForActionSendMultiple, setupShareContainingFolderButton, onClickButtonShareContainingFolder, buttonShareContainingFolderShouldBeVisible (one file shared: the game)
 * Not ported: showNotification, cancelNotification, initNotification, onCreateOptionsMenu, onOptionsItemSelected, showPrivacyPolicy, rate_this_app, openInPlayStore, onBackPressed (the match stays until Stop)
 * Not ported: guessAppropriateEncoding, MultiFormatWriter, BitMatrix, hints (GutapK's own QrCode, byte mode in UTF-8)
 *
 * Copyright (c) 2011, Marcos Diez, BSD licence.
 * Copyright (C) 2008 ZXing authors, Apache License 2.0.
 * Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
 */

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.gutapk.offline.Server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class LocalActivity extends Activity {
    private static final String TAG = "GutapK";
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    /** Pixels per module, the image view scales the code to the screen. */
    private static final int MODULE = 12;
    private static final int QUIET = 4;
    private static final int TIMEOUT = 5000;

    // The match of the process, as ShareViaHttp keeps its server.
    protected static Server httpServer = null;
    protected static Tunnel tunnel = null;
    protected static RemoteGodot engine = null;
    protected static String preferredServerUrl;
    protected static CharSequence message;

    protected Config config;
    protected TextView link_msg;
    protected TextView status;
    protected ImageView qrImage;
    protected View startServer;
    protected View play;
    protected View stopServer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            InputStream in = getAssets().open(Config.CONFIG);
            try {
                config = Config.build(in);
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Local cannot read its config", e);
            finish();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        layout.addView(textView(fr() ? "Local : jouer avec des amis à côté" : "Local: play with friends nearby", 22), p);
        status = textView("", 16);
        layout.addView(status, p);
        qrImage = new ImageView(this);
        qrImage.setAdjustViewBounds(true);
        layout.addView(qrImage, p);
        link_msg = textView("", 12);
        link_msg.setTextIsSelectable(true);
        layout.addView(link_msg, p);
        startServer = buttonView(fr() ? "Héberger une partie" : "Host a match");
        layout.addView(startServer, p);
        play = buttonView(fr() ? "Jouer" : "Play");
        layout.addView(play, p);
        stopServer = buttonView(fr() ? "Arrêter la partie" : "Stop the match");
        layout.addView(stopServer, p);
        setContentView(layout);
        createViewClickListener();

        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data != null && Invite.SCHEME.equals(data.getScheme()) && Invite.JOIN.equals(data.getPath())) {
            join(data.getEncodedQuery());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setLinkMessageToView();
    }

    protected void createViewClickListener() {
        startServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                initHttpServer();
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                play();
            }
        });
        stopServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopServer();
                RemoteGodot p = engine;
                engine = null;
                if (p != null) {
                    ((Application) getApplicationContext()).unregisterActivityLifecycleCallbacks(p);
                    try {
                        p.stopRemoteGame();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Local could not stop the server engine", e);
                    }
                }
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                message = fr() ? "La partie est arrêtée." : "The match is stopped.";
                setLinkMessageToView();
            }
        });
    }

    /** The relay, the page and the server engine; the network on a thread of its own. */
    protected void initHttpServer() {
        if (config.getArgs().isEmpty()) {
            message = fr() ? "Ce jeu ne sait pas héberger." : "This game cannot host.";
            setLinkMessageToView();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        message = fr() ? "Démarrage…" : "Starting…";
        setLinkMessageToView();
        final File apk = new File(getApplicationInfo().sourceDir);
        final String packageName = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                CharSequence msg;
                try {
                    List<InetAddress> listOfServerUris = Invite.addresses();
                    if (listOfServerUris.isEmpty()) {
                        throw new IOException(fr() ? "pas de réseau, activez le Wi-Fi ou le point d'accès" : "no network, turn Wi-Fi or the hotspot on");
                    }
                    byte[] key = Aead.newKey();
                    tunnel = Tunnel.host(key, 0, config.getLocalPort(), config.isUdp());
                    httpServer = new Server(null, 0, new Invite(packageName, apk));
                    httpServer.start(TIMEOUT);
                    preferredServerUrl = Invite.link(listOfServerUris.get(0), httpServer.getListeningPort(), tunnel.getListeningPort(), key);
                    msg = fr() ? "Vos amis scannent ce code avec l'appareil photo, sur le même Wi-Fi ou votre point d'accès. Puis Jouer." : "Friends scan this code with their camera, on the same Wi-Fi or your hotspot. Then Play.";
                } catch (IOException e) {
                    Log.e(TAG, "Local cannot host", e);
                    stopServer();
                    msg = (fr() ? "Impossible d'héberger : " : "Cannot host: ") + e.getMessage();
                }
                final CharSequence shown = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (httpServer != null && engine == null) {
                            // The server engine starts with the game's next screen.
                            engine = new RemoteGodot(getApplicationContext(), config.getArgs().toArray(new String[0]));
                            ((Application) getApplicationContext()).registerActivityLifecycleCallbacks(engine);
                        }
                        message = shown;
                        setLinkMessageToView();
                    }
                });
            }
        }).start();
    }

    /** A friend's relay to the host of the invitation, then the game. */
    protected void join(final String query) {
        stopServer();
        message = fr() ? "Connexion à l'hôte…" : "Reaching the host…";
        setLinkMessageToView();
        new Thread(new Runnable() {
            @Override
            public void run() {
                CharSequence msg;
                boolean joined = false;
                try {
                    String host = Invite.getQueryParameter(query, Invite.HOST);
                    String port = Invite.getQueryParameter(query, Invite.PORT);
                    String key = Invite.getQueryParameter(query, Invite.KEY);
                    if (host == null || port == null || key == null) {
                        throw new IOException(fr() ? "invitation incomplète" : "incomplete invitation");
                    }
                    InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), Integer.parseInt(port));
                    tunnel = Tunnel.join(Hex.decodeHex(key), Collections.singletonList(address), config.getLocalPort(), config.isUdp());
                    msg = (fr() ? "Connecté à " : "Connected to ") + host;
                    joined = true;
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Local cannot join", e);
                    tunnel = null;
                    msg = (fr() ? "Impossible de rejoindre : " : "Cannot join: ") + e.getMessage();
                }
                final CharSequence shown = msg;
                final boolean start = joined;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        message = shown;
                        setLinkMessageToView();
                        if (start) {
                            play();
                        }
                    }
                });
            }
        }).start();
    }

    /** The game's own screen, which GutapK read from its manifest. */
    protected void play() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, config.getActivity()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    protected static synchronized void stopServer() {
        Tunnel t = tunnel;
        tunnel = null;
        if (t != null) {
            t.close();
        }
        Server s = httpServer;
        httpServer = null;
        if (s != null) {
            s.stop();
        }
        preferredServerUrl = null;
    }

    /** The screen as the match stands: the message, the code and its link, the buttons that apply. */
    protected void setLinkMessageToView() {
        boolean hosting = httpServer != null && tunnel != null;
        boolean joined = httpServer == null && tunnel != null;
        status.setText(message != null ? message : (fr() ? "Hébergez, ou scannez le code d'un ami avec l'appareil photo." : "Host, or scan a friend's code with your camera."));
        link_msg.setText(hosting && preferredServerUrl != null ? preferredServerUrl : "");
        generateBarCodeIfPossible();
        startServer.setVisibility(tunnel == null ? View.VISIBLE : View.GONE);
        play.setVisibility(hosting || joined ? View.VISIBLE : View.GONE);
        stopServer.setVisibility(tunnel != null ? View.VISIBLE : View.GONE);
    }

    public void generateBarCodeIfPossible() {
        String contents = link_msg.getText().toString();
        if (contents.isEmpty()) {
            qrImage.setVisibility(View.GONE);
            return;
        }
        qrImage.setImageBitmap(encodeAsBitmap(contents));
        qrImage.setVisibility(View.VISIBLE);
    }

    static Bitmap encodeAsBitmap(String contents) {
        QrCode result = QrCode.encode(contents);
        int width = (result.size + 2 * QUIET) * MODULE;
        int height = width;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int mx = x / MODULE - QUIET;
                int my = y / MODULE - QUIET;
                boolean dark = mx >= 0 && my >= 0 && mx < result.size && my < result.size && result.isDark(mx, my);
                pixels[offset + x] = dark ? BLACK : WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private TextView textView(String text, float size) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(size);
        textView.setTextColor(BLACK);
        textView.setPadding(0, 16, 0, 16);
        return textView;
    }

    private Button buttonView(String text) {
        Button buttonView = new Button(this);
        buttonView.setText(text);
        return buttonView;
    }

    private static boolean fr() {
        return "fr".equals(Locale.getDefault().getLanguage());
    }
}
