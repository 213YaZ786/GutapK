package io.gutapk.local;

/*
 * The Local screen GutapK adds to a game. From its launcher entry, Host starts
 * the relay, the page of the invitation and, with the game's next screen, its
 * server in a second engine (none for a game that hosts from its own menu),
 * and shows the QR code friends scan; Play opens the game. From a friend's
 * invitation (gutapk://<package>/join?...) the relay to the host starts and
 * the game opens, talking to its own address as always, through its own link
 * to a server when it has one. The servers are static: they outlive the screen, the game's process
 * keeps them. The screen is drawn in Material You glass (Glass): the phone's
 * own colours, frosted panels and capsule buttons over a blurred backdrop. Built as ShareViaHttp's activities share a file, its link shown
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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    /** The host's note under the code: the port to host on, for a game that hosts from its menu. */
    protected static CharSequence note;
    /** A friend's relay is on its way to the host: Cancel stops it. */
    protected static volatile boolean joining;
    private static final String MEDIUM = "sans-serif-medium";

    protected Config config;
    protected Glass glass;
    protected TextView link_msg;
    protected TextView note_msg;
    protected TextView status;
    protected ImageView qrImage;
    protected View qrCard;
    protected Button startServer;
    protected Button play;
    protected Button stopServer;

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
        // The layout wrapped in a ScrollView as ApiDemos wraps its own, over
        // the glass backdrop: the content scrolls on a small phone. The views
        // are ShareViaHttp's, a capsule for each button as Kyant's liquid
        // glass draws its own.
        // https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/view/ScrollView2.java#L31-L52
        // https://github.com/marcosdiez/shareviahttp/blob/master/ShareViaHttp/app/src/main/java/com/MarcosDiez/shareviahttp/activities/BaseActivity.java#L45-L217
        // https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt#L36-L60
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ScrollView.java#setFillViewport
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/LinearLayout.java#generateLayoutParams
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Gravity.java#L62-L76
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Typeface.java#L107-L113
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/Typeface.java#create
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setBackground
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setLineSpacing
        // not ported: R.layout.scroll_view_2, findViewById (the views are made in code)
        // not ported: LiquidButton, vibrancy, lens, interactiveHighlight (Compose's: the capsule is a drawable)
        // not ported: onCreate, onClick, createViewClickListener, initHttpServer, setLinkMessageToView, generateBarCodeIfPossible (this method and the ones below)
        // not ported: handleMessage, createChangeIpDialog, sendConnectionStartMessage, sendConnectionEndMessage, setupTextViews, setupNavigationViews, setupToolbar, populateUriPath, saveServerUrlToClipboard (left out, the head of the file says why)
        // not ported: background, create, fillViewport, lineSpacing (the phone's views and typefaces do the work, the stubs only name the calls)
        // not ported: generateLayoutParams (the AttributeSet one: the screen's own generateLayoutParams sizes a view and its margin)
        glass = new Glass(getResources());
        FrameLayout root = new FrameLayout(this);
        root.addView(glass.backdrop(this), new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        root.addView(scrollView, new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(glass.dp(20), glass.dp(48), glass.dp(20), glass.dp(24));
        scrollView.addView(layout, new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView textView = textView("⇄", 22, glass.onPrimary);
        textView.setGravity(Gravity.CENTER);
        textView.setBackground(glass.shape(glass.primary, 16, 0));
        header.addView(textView, new LinearLayout.LayoutParams(glass.dp(48), glass.dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(glass.dp(14), 0, 0, 0);
        textView = textView("Local", 22, glass.onSurface);
        textView.setTypeface(Typeface.create(MEDIUM, Typeface.NORMAL));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titles.addView(textView, p);
        titles.addView(textView(fr() ? "Jouer avec des amis à côté" : "Play with friends nearby", 14, glass.onSurfaceVariant), p);
        header.addView(titles, p);
        layout.addView(header, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20));

        status = textView("", 16, glass.onSurface);
        status.setLineSpacing(0, 1.25f);
        status.setBackground(glass.zone());
        status.setPadding(glass.dp(18), glass.dp(16), glass.dp(18), glass.dp(16));
        layout.addView(status, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12));

        // The code on a white card inside a panel, its link under it.
        LinearLayout qrPanel = new LinearLayout(this);
        qrPanel.setOrientation(LinearLayout.VERTICAL);
        qrPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        qrPanel.setBackground(glass.zone());
        qrPanel.setPadding(glass.dp(16), glass.dp(16), glass.dp(16), glass.dp(14));
        qrImage = new ImageView(this);
        qrImage.setAdjustViewBounds(true);
        qrImage.setBackground(glass.shape(WHITE, 16, 0));
        qrImage.setPadding(glass.dp(6), glass.dp(6), glass.dp(6), glass.dp(6));
        qrPanel.addView(qrImage, generateLayoutParams(glass.dp(240), 10));
        link_msg = textView("", 11, glass.onSurfaceVariant);
        link_msg.setTypeface(Typeface.MONOSPACE);
        link_msg.setGravity(Gravity.CENTER_HORIZONTAL);
        link_msg.setTextIsSelectable(true);
        qrPanel.addView(link_msg, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0));
        qrCard = qrPanel;
        layout.addView(qrPanel, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12));

        note_msg = textView("", 13, glass.night ? glass.onSurface : glass.onPrimaryContainer);
        note_msg.setLineSpacing(0, 1.2f);
        note_msg.setBackground(glass.tint());
        note_msg.setPadding(glass.dp(16), glass.dp(12), glass.dp(16), glass.dp(12));
        layout.addView(note_msg, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12));

        // The buttons at the foot of a tall screen.
        layout.addView(new View(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        startServer = buttonView(fr() ? "Héberger une partie" : "Host a match", glass.capsule(glass.primary, false), glass.onPrimary);
        layout.addView(startServer, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 10));
        play = buttonView(fr() ? "Jouer" : "Play", glass.capsule(Glass.copy(glass.primaryContainer, glass.night ? 0.30f : 0.60f), true), glass.night ? glass.onSurface : glass.onPrimaryContainer);
        layout.addView(play, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 10));
        stopServer = buttonView(fr() ? "Arrêter la partie" : "Stop the match", glass.capsule(Glass.copy(glass.errorContainer, glass.night ? 0.45f : 0.65f), true), glass.onErrorContainer);
        layout.addView(stopServer, generateLayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0));
        setContentView(root);
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
        // No arguments: the game hosts from its own menu, no second engine.
        final boolean ownMenu = config.getArgs().isEmpty();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        message = fr() ? "Démarrage…" : "Starting…";
        setLinkMessageToView();
        final File apk = new File(getApplicationInfo().sourceDir);
        final String packageName = getPackageName();
        // The game's name for the invitation page, as PackageItemInfo.loadLabel reads it.
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageItemInfo.java#L136-L143
        int labelRes = getApplicationInfo().labelRes;
        CharSequence nonLocalizedLabel = getApplicationInfo().nonLocalizedLabel;
        final String label = labelRes != 0 ? getResources().getString(labelRes) : nonLocalizedLabel != null ? nonLocalizedLabel.toString() : packageName;
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
                    httpServer = new Server(null, 0, new Invite(packageName, apk, label, glass));
                    httpServer.start(TIMEOUT);
                    preferredServerUrl = Invite.link(listOfServerUris.get(0), httpServer.getListeningPort(), tunnel.getListeningPort(), key);
                    msg = fr() ? "Vos amis scannent ce code avec l'appareil photo, sur le même Wi-Fi ou votre point d'accès. Puis Jouer." : "Friends scan this code with their camera, on the same Wi-Fi or your hotspot. Then Play.";
                    if (ownMenu) {
                        // The relay reaches the port GutapK read: another one typed in the game's menu would be out of reach.
                        int port = config.getLocalPort();
                        note = fr()
                                ? "Hébergez la partie depuis le menu du jeu, sur le port " + port + " (ne le changez pas)."
                                : "Host the match from the game's menu, on port " + port + " (leave it as it is).";
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Local cannot host", e);
                    stopServer();
                    msg = (fr() ? "Impossible d'héberger : " : "Cannot host: ") + e.getMessage();
                }
                final CharSequence shown = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (httpServer != null && engine == null && !ownMenu) {
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
        joining = true;
        final String host = Invite.getQueryParameter(query, Invite.HOST);
        // The steps of the join: the invitation read, the relay on its way, the game next.
        message = fr()
                ? "✓   Invitation lue\n◉   Connexion à " + host + "…\n○   Le jeu s'ouvre"
                : "✓   Invitation read\n◉   Reaching " + host + "…\n○   The game opens";
        setLinkMessageToView();
        new Thread(new Runnable() {
            @Override
            public void run() {
                CharSequence msg;
                boolean joined = false;
                try {
                    String port = Invite.getQueryParameter(query, Invite.PORT);
                    String key = Invite.getQueryParameter(query, Invite.KEY);
                    if (host == null || port == null || key == null) {
                        throw new IOException(fr() ? "invitation incomplète" : "incomplete invitation");
                    }
                    InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), Integer.parseInt(port));
                    tunnel = Tunnel.join(Hex.decodeHex(key), Collections.singletonList(address), config.getLocalPort(), config.isUdp());
                    // Cancelled while the relay reached the host: stopServer
                    // closes it, whether Cancel came before the relay or after.
                    if (!joining) {
                        stopServer();
                        return;
                    }
                    joining = false;
                    msg = fr()
                            ? "✓   Invitation lue\n✓   Connecté à " + host + "\n✓   Le jeu s'ouvre"
                            : "✓   Invitation read\n✓   Connected to " + host + "\n✓   The game opens";
                    joined = true;
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Local cannot join", e);
                    if (!joining) {
                        return;
                    }
                    joining = false;
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

    /**
     * The game's own screen, which GutapK read from its manifest. A friend's,
     * for a game with a link of its own to a server, with that link: the game
     * connects to its own address, the relay's, as its handleIntent reads it.
     * https://github.com/Anuken/Mindustry/blob/master/android/src/mindustry/android/AndroidLauncher.java#handleIntent
     */
    protected void play() {
        Intent intent = new Intent();
        if (tunnel != null && httpServer == null && config.getServer() != null) {
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(config.getServer()));
        }
        intent.setComponent(new ComponentName(this, config.getActivity()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    protected static synchronized void stopServer() {
        joining = false;
        note = null;
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
    // https://github.com/marcosdiez/shareviahttp/blob/master/ShareViaHttp/app/src/main/java/com/MarcosDiez/shareviahttp/activities/BaseActivity.java#setLinkMessageToView
    // not ported: setPaintFlags, UNDERLINE_TEXT_FLAG (the link is plain text under the code)
    protected void setLinkMessageToView() {
        boolean hosting = httpServer != null && tunnel != null;
        boolean joined = httpServer == null && tunnel != null;
        boolean idle = tunnel == null && !joining;
        status.setText(message != null ? message : (fr() ? "Hébergez, ou scannez le code d'un ami avec l'appareil photo." : "Host, or scan a friend's code with your camera."));
        link_msg.setText(hosting && preferredServerUrl != null ? preferredServerUrl : "");
        generateBarCodeIfPossible();
        // Before a match, what Local is; while hosting, the port to host on.
        CharSequence shown = idle
                ? (fr() ? "Tout ce qui passe entre les téléphones est chiffré, sans internet." : "Everything between the phones is encrypted, no internet needed.")
                : hosting ? note : null;
        note_msg.setText(shown != null ? shown : "");
        note_msg.setVisibility(shown != null ? View.VISIBLE : View.GONE);
        startServer.setVisibility(idle ? View.VISIBLE : View.GONE);
        play.setText(idle ? (fr() ? "Jouer seul" : "Play alone") : (fr() ? "Jouer" : "Play"));
        play.setVisibility(joining ? View.GONE : View.VISIBLE);
        stopServer.setText(hosting ? (fr() ? "Arrêter la partie" : "Stop the match")
                : joined ? (fr() ? "Quitter la partie" : "Leave the match")
                : (fr() ? "Annuler" : "Cancel"));
        stopServer.setVisibility(idle ? View.GONE : View.VISIBLE);
    }

    // https://github.com/marcosdiez/shareviahttp/blob/master/ShareViaHttp/app/src/main/java/com/MarcosDiez/shareviahttp/activities/BaseActivity.java#generateBarCodeIfPossible
    public void generateBarCodeIfPossible() {
        String contents = link_msg.getText().toString();
        if (contents.isEmpty()) {
            qrCard.setVisibility(View.GONE);
            return;
        }
        qrImage.setImageBitmap(encodeAsBitmap(contents));
        qrCard.setVisibility(View.VISIBLE);
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

    private TextView textView(String text, float size, int color) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    // A capsule button: the background drawn by Glass, no press shadow, the
    // label as written.
    // https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/view/ScrollView2.java#L44-L46
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#setStateListAnimator
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java#setAllCaps
    // not ported: onCreate, R.layout.scroll_view_2, findViewById (the sample's own screen)
    // not ported: allCaps, stateListAnimator (the phone's Button applies them, the stub only names the calls)
    private Button buttonView(String text, Drawable background, int color) {
        Button buttonView = new Button(this);
        buttonView.setText(text);
        buttonView.setAllCaps(false);
        buttonView.setTextSize(15);
        buttonView.setTextColor(color);
        buttonView.setTypeface(Typeface.create(MEDIUM, Typeface.NORMAL));
        buttonView.setBackground(background);
        buttonView.setStateListAnimator(null);
        buttonView.setPadding(glass.dp(24), glass.dp(15), glass.dp(24), glass.dp(15));
        return buttonView;
    }

    // A view's size in the column, a margin of bottom dp under it.
    // https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/view/ScrollView2.java#L40-L43
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ViewGroup.java#setMargins
    // not ported: onCreate, R.layout.scroll_view_2, findViewById (the sample's own screen)
    // not ported: leftMargin, topMargin, rightMargin, bottomMargin, margins, requestLayout (set by the phone's setMargins)
    private LinearLayout.LayoutParams generateLayoutParams(int width, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                width,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(0, 0, 0, glass.dp(bottom));
        return p;
    }

    private static boolean fr() {
        return "fr".equals(Locale.getDefault().getLanguage());
    }
}
