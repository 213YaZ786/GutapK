// Starts the offline cache server before the game's Application.onCreate, the
// way Firebase starts itself: a ContentProvider declared in the manifest whose
// onCreate does the work. It is declared exported=false with a high initOrder,
// and every method but onCreate answers null or 0.
//
// https://github.com/firebase/firebase-android-sdk/blob/bb62df1b4c1c3337734c6a4bc63534bed6f138f7/firebase-common/src/main/java/com/google/firebase/provider/FirebaseInitProvider.java
//
// Not ported: getStartupTime (no startup time is reported)
// Not ported: attachInfo, checkContentProviderAuthority (the authority is written into the manifest by GutapK, and a crash here would take the game down)
//
// Copyright 2018 Google LLC, Apache License 2.0.
package io.gutapk.offline;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts the offline cache server at app startup time. */
public class CacheProvider extends ContentProvider {
  private static final String TAG = "GutapK";

  /** Where the injected config sits in the game's assets. */
  static final String CONFIG = "gutapk/offline.properties";

  private static AtomicBoolean currentlyInitializing = new AtomicBoolean(false);

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
      start(context);
      return false;
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

  private static void start(final Context context) throws IOException {
    InputStream source = context.getAssets().open(CONFIG);
    try {
      File root = new File(context.getFilesDir(), "gutapk-offline");
      // The recordings that travel with the game join the cache before the server
      // reads it, so the very first launch can answer with no network.
      // https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFolder
      // Not ported: the other helpers of FileUtils (see Seed)
      final AssetManager assets = context.getAssets();
      String[] fileList = assets.list(RECORDINGS);
      if (fileList != null && fileList.length != 0) {
        Seed.copyAssetFolder(new Seed.Assets() {
          @Override
          public String[] list(String path) throws IOException {
            return assets.list(path);
          }

          @Override
          public InputStream open(String fileName) throws IOException {
            return assets.open(fileName);
          }
        }, RECORDINGS, new File(root, "cache").getPath());
      }
      Offline.start(source, root);
    } finally {
      source.close();
    }
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
