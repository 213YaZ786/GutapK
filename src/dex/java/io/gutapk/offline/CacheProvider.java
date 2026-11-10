// Starts the offline cache server before the game's Application.onCreate, the
// way Firebase starts itself: a ContentProvider declared in the manifest whose
// onCreate does the work. It is declared exported=false with a high initOrder,
// and every method but onCreate answers null or 0.
//
// https://github.com/firebase/firebase-android-sdk/blob/bb62df1b4c1c3337734c6a4bc63534bed6f138f7/firebase-common/src/main/java/com/google/firebase/provider/FirebaseInitProvider.java
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L2753-L2755
//
// The bound name of the admin socket is new at each launch. dump prints it, and
// "adb shell dumpsys activity provider <package>/<this class>" reaches dump in
// the game's own process, so the PC reads the name from the process that holds it.
//
// Not ported: getStartupTime (no startup time is reported)
// Not ported: attachInfo, checkContentProviderAuthority (the authority is written into the manifest by GutapK, and a crash here would take the game down)
// Not ported: the "nothing to dump" line of dump (this one prints the admin name)
//
// Copyright 2018 Google LLC, Apache License 2.0.
// Copyright (C) 2006 The Android Open Source Project, Apache License 2.0.
package io.gutapk.offline;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts the offline cache server at app startup time. */
public class CacheProvider extends ContentProvider {
  private static final String TAG = "GutapK";

  /** Where the injected config sits in the game's assets. */
  static final String CONFIG = "gutapk/offline.properties";

  private static AtomicBoolean currentlyInitializing = new AtomicBoolean(false);

  /** The offline server of this process, for dump. */
  private static volatile Offline started;

  public static boolean isCurrentlyInitializing() {
    return currentlyInitializing.get();
  }

  /** Called before Application#onCreate(). */
  @Override
  public boolean onCreate() {
    try {
      currentlyInitializing.set(true);
      Context context = getContext();
      if (context == null) {
        Log.w(TAG, "No application context, the offline cache is not started");
        return false;
      }
      started = start(context);
      return false;
    } catch (Offline.Squatted e) {
      // A port is held by another app: the game would send its tokens there, so it must not start.
      Log.e(TAG, "Offline cache refused, a port is held by another app", e);
      throw e;
    } catch (Throwable e) {
      // The game must start even if the cache cannot.
      Log.e(TAG, "Offline cache initialization unsuccessful", e);
      return false;
    } finally {
      currentlyInitializing.set(false);
    }
  }

  /** Where the recordings kept on the PC sit in the game's assets. */
  static final String RECORDINGS = "gutapk/recordings";

  /** Where the local game server's rules.bson and state.bson sit in the game's assets. */
  static final String SERVER = "gutapk/server";

  private static Offline start(final Context context) throws IOException {
    InputStream source = context.getAssets().open(CONFIG);
    try {
      File root = new File(context.getFilesDir(), "gutapk-offline");
      // The recordings that travel with the game join the cache before the server
      // reads it, so the very first launch can answer with no network.
      // https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFolder
      // Not ported: the other helpers of FileUtils (see Seed)
      final AssetManager assets = context.getAssets();
      Seed.Assets seed = new Seed.Assets() {
        @Override
        public String[] list(String path) throws IOException {
          return assets.list(path);
        }

        @Override
        public InputStream open(String fileName) throws IOException {
          return assets.open(fileName);
        }
      };
      String[] fileList = assets.list(RECORDINGS);
      if (fileList != null && fileList.length != 0) {
        Seed.copyAssetFolder(seed, RECORDINGS, new File(root, "cache").getPath());
      }
      // The rules and the progress of the local game server join the server folder the same way,
      // a file the phone holds already is kept: the player's progress survives an install over the old one.
      // The rules are the APK's own, so a build learnt from more recordings brings its rules in place of the old ones.
      String[] serverList = assets.list(SERVER);
      if (serverList != null && serverList.length != 0) {
        new File(new File(root, "server"), "rules.bson").delete();
        Seed.copyAssetFolder(seed, SERVER, new File(root, "server").getPath());
      }
      return Offline.start(source, root);
    } finally {
      source.close();
    }
  }

  /**
   * Print the Provider's state: one line, the admin socket's bound name.
   * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java#L2753-L2755
   */
  @Override
  public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    Offline offline = started;
    String name = offline == null ? null : offline.adminName;
    writer.println("admin " + (name == null ? "none" : name));
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    return null;
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    return 0;
  }
}
