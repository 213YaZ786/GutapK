// A second engine of the game, in a process of its own, started with the
// arguments that make the game its own server: the phone hosts the match and
// plays it. Godot 4.5+ ships GodotService, which runs an engine in a Service;
// GutapK declares it in a process of its own (one engine per process). The
// engine steps only while its surface shows, so it is wrapped in a
// SurfaceControlViewHost and shown in a one-pixel SurfaceView added to the
// game's own activity, the way RemoteGodotFragment shows it in a fragment.
//
// https://github.com/godotengine/godot/blob/4.6.3-stable/platform/android/java/lib/src/main/java/org/godotengine/godot/service/RemoteGodotFragment.kt
// https://github.com/godotengine/godot/blob/4.6.3-stable/platform/android/java/lib/src/main/java/org/godotengine/godot/service/GodotService.kt#L67-L94
//
// Not ported: onAttach, onDetach, godotHost (no GodotHost: the game's activity stays the game's)
// Not ported: onCreateView, onViewCreated (the surface is added to the activity's decor view, the fragment's layout is not in every export)
// Not ported: onStart, onStop, stopGodotEngine (the server must keep running while the host goes from one screen of the game to another; it ends with the game's process)
// Not ported: onDestroy (same: no fragment to destroy, stopRemoteGame is there for a restart)
// Not ported: setZOrderOnTop(true) in startRemoteGame (the pixel stays under the game)
// Not ported: TAG (GutapK's own)
//
// Copyright (c) 2014-present Godot Engine contributors, MIT License.
package io.gutapk.local;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Drives a GodotService engine from the game's own activities. */
public final class RemoteGodot implements ServiceConnection, Handler.Callback, SurfaceHolder.Callback, Application.ActivityLifecycleCallbacks {
  private static final String TAG = "GutapK";

  /** Part of every Godot 4.5+ Android export, declared by GutapK in a process of its own. */
  static final String SERVICE = "org.godotengine.godot.service.GodotService";

  // Set of commands from the client to the service.
  private static final int MSG_INIT_ENGINE = 0;
  private static final int MSG_START_ENGINE = MSG_INIT_ENGINE + 1;
  private static final int MSG_STOP_ENGINE = MSG_START_ENGINE + 1;
  private static final int MSG_DESTROY_ENGINE = MSG_STOP_ENGINE + 1;
  private static final int MSG_WRAP_ENGINE_WITH_SCVH = MSG_DESTROY_ENGINE + 1;

  // Set of commands from the service to the client.
  private static final int MSG_ENGINE_ERROR = 100;
  private static final int MSG_ENGINE_STATUS_UPDATE = 101;
  private static final int MSG_ENGINE_RESTART_REQUESTED = 102;

  // Keys to store / retrieve msg payloads.
  private static final String KEY_COMMAND_LINE_PARAMETERS = "commandLineParameters";
  private static final String KEY_HOST_TOKEN = "hostToken";
  private static final String KEY_DISPLAY_ID = "displayId";
  private static final String KEY_WIDTH = "width";
  private static final String KEY_HEIGHT = "height";
  private static final String KEY_SURFACE_PACKAGE = "surfacePackage";
  private static final String KEY_ENGINE_STATUS = "engineStatus";
  private static final String KEY_ENGINE_ERROR = "engineError";

  /** Nobody needs to see the server: it draws into one pixel. */
  private static final int SIZE = 1;

  private final Context context;

  /** Target we publish for receiving messages from the service. */
  private final Messenger messengerForReply;

  /** Messenger for sending messages to the GodotService implementation. */
  private Messenger serviceMessenger;

  private SurfaceView remoteSurface;
  private boolean surfaceReady = false;
  private boolean engineInitialized = false;
  private boolean serviceBound = false;
  private String[] remoteGameArgs;

  RemoteGodot(Context context, String[] args) {
    this.context = context;
    this.remoteGameArgs = args;
    this.messengerForReply = new Messenger(new Handler(Looper.getMainLooper(), this));
  }

  @Override
  public void onServiceConnected(ComponentName name, IBinder service) {
    Log.d(TAG, "Connected to the server engine");
    serviceMessenger = new Messenger(service);
    // Initialize the Godot engine.
    initGodotEngine();
  }

  @Override
  public void onServiceDisconnected(ComponentName name) {
    Log.d(TAG, "Disconnected from the server engine");
    serviceMessenger = null;
    engineInitialized = false;
  }

  /** Handler of incoming messages from GodotService. */
  @Override
  public boolean handleMessage(Message msg) {
    try {
      switch (msg.what) {
        case MSG_ENGINE_STATUS_UPDATE: {
          String engineStatus = msg.getData().getString(KEY_ENGINE_STATUS);
          Log.d(TAG, "Server engine status " + engineStatus);
          if ("INITIALIZED".equals(engineStatus)) {
            wrapEngine();
          } else if ("SCVH_CREATED".equals(engineStatus)) {
            SurfaceControlViewHost.SurfacePackage surfacePackage = msg.getData().getParcelable(KEY_SURFACE_PACKAGE);
            if (surfacePackage == null || remoteSurface == null) {
              Log.e(TAG, "Unable to retrieve surface package from GodotService");
            } else {
              remoteSurface.setChildSurfacePackage(surfacePackage);
              if (!engineInitialized) {
                engineInitialized = true;
                startGodotEngine();
              }
            }
          } else if ("DESTROYED".equals(engineStatus)) {
            engineInitialized = false;
          }
          return true;
        }
        case MSG_ENGINE_ERROR:
          Log.e(TAG, "Server engine error " + msg.getData().getString(KEY_ENGINE_ERROR));
          return true;
        case MSG_ENGINE_RESTART_REQUESTED: {
          // Retrieve the current game args since stopping the engine will clear them out.
          String[] currentArgs = remoteGameArgs;
          stopRemoteGame();
          remoteGameArgs = currentArgs;
          startRemoteGame();
          return true;
        }
        default:
          return false;
      }
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to handle message from the server engine", e);
      return true;
    }
  }

  void startRemoteGame() {
    Log.d(TAG, "Starting the server engine");
    context.bindService(new Intent().setComponent(new ComponentName(context, SERVICE)), this, Context.BIND_AUTO_CREATE);
    serviceBound = true;
  }

  void stopRemoteGame() throws RemoteException {
    Log.d(TAG, "Stopping the server engine");
    if (serviceBound) {
      if (serviceMessenger != null) {
        Message message = Message.obtain();
        message.what = MSG_DESTROY_ENGINE;
        message.replyTo = messengerForReply;
        serviceMessenger.send(message);
      }
      context.unbindService(this);
      serviceBound = false;
      engineInitialized = false;
    }
  }

  private void initGodotEngine() {
    if (!serviceBound || serviceMessenger == null) {
      return;
    }
    try {
      Message message = Message.obtain();
      message.what = MSG_INIT_ENGINE;
      message.getData().putStringArray(KEY_COMMAND_LINE_PARAMETERS, remoteGameArgs);
      message.replyTo = messengerForReply;
      serviceMessenger.send(message);
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to initialize the server engine", e);
    }
  }

  // Asks the service for its engine's surface, sized to the pixel shown.
  private void wrapEngine() throws RemoteException {
    if (serviceMessenger == null || remoteSurface == null || !surfaceReady) {
      return;
    }
    Message message = Message.obtain();
    message.what = MSG_WRAP_ENGINE_WITH_SCVH;
    Bundle data = message.getData();
    data.putBinder(KEY_HOST_TOKEN, remoteSurface.getHostToken());
    data.putInt(KEY_DISPLAY_ID, remoteSurface.getDisplay().getDisplayId());
    data.putInt(KEY_WIDTH, SIZE);
    data.putInt(KEY_HEIGHT, SIZE);
    message.replyTo = messengerForReply;
    serviceMessenger.send(message);
  }

  private void startGodotEngine() throws RemoteException {
    Message message = Message.obtain();
    message.what = MSG_START_ENGINE;
    message.replyTo = messengerForReply;
    serviceMessenger.send(message);
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    try {
      if (!serviceBound) {
        startRemoteGame();
      } else if (engineInitialized) {
        // A new activity: the engine's surface moves to its pixel.
        wrapEngine();
      }
    } catch (RemoteException e) {
      Log.e(TAG, "Unable to show the server engine", e);
    }
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    surfaceReady = false;
  }

  @Override
  public void onActivityResumed(Activity activity) {
    // The game's screens only: the Local screen has no game to play beside.
    if (activity instanceof LocalActivity || (remoteSurface != null && remoteSurface.getContext() == activity)) {
      return;
    }
    surfaceReady = false;
    remoteSurface = new SurfaceView(activity);
    remoteSurface.getHolder().addCallback(this);
    ((ViewGroup) activity.getWindow().getDecorView()).addView(remoteSurface, new FrameLayout.LayoutParams(SIZE, SIZE));
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    if (remoteSurface != null && remoteSurface.getContext() == activity) {
      remoteSurface = null;
      surfaceReady = false;
    }
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
  }

  @Override
  public void onActivityStarted(Activity activity) {
  }

  @Override
  public void onActivityPaused(Activity activity) {
  }

  @Override
  public void onActivityStopped(Activity activity) {
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
  }
}
