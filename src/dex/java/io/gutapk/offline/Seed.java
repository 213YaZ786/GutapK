package io.gutapk.offline;

/*
 * The recordings that travel inside the offline APK, copied from the game's
 * assets into the cache folder at its first launch, the way EKA2L1 copies an
 * asset folder to its working directory. A file the phone already holds is
 * kept: a recording made on the phone is never replaced by the older one that
 * shipped. The assets are reached through two calls, listing and opening, so
 * the copy runs on the JVM in tests and CacheProvider wires AssetManager to it.
 *
 * https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFolder
 * https://github.com/EKA2L1/EKA2L1/blob/6ad873a07ee3d745cd17179026dca247f6d11f6b/src/emu/android/app/src/main/java/com/github/eka2l1/util/FileUtils.java#copyAssetFile
 *
 * Not ported: copyFiles, copyFileUsingChannel, copyFileFromInputStream, copyFileFromURI (files of the disk, not assets)
 * Not ported: getFilenameFromURI, getFilenameFromPath (content URIs)
 * Not ported: deleteDirectory, moveContents, copyRecursively, writeToFile, readFromFile (nothing is deleted, moved or read here)
 * Not ported: isExternalStorageLegacy, hasDirectStorageAccess, ensureTrailingSeparator, isDirectoryWritable, isUsableWorkingDir (the cache is in the app's private folder)
 * Not ported: getFilePicker, getDirPicker, getNativeDirPicker (no picker)
 *
 * Copyright (c) 2020 EKA2L1 Team, GPL-3.0-or-later.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Seed {

    /** The two calls of AssetManager that the copy needs. */
    public interface Assets {
        String[] list(String path) throws IOException;

        InputStream open(String fileName) throws IOException;
    }

    private Seed() {
    }

    /** Copies the folder srcName of the assets to dstName, true when every file is there afterwards. */
    public static boolean copyAssetFolder(Assets assets, String srcName, String dstName) {
        try {
            boolean result;
            String fileList[] = assets.list(srcName);
            if (fileList == null) return false;

            if (fileList.length == 0) {
                result = copyAssetFile(assets, srcName, dstName);
            } else {
                File file = new File(dstName);
                // The folder is there already after the first launch, that is not a failure.
                result = file.isDirectory() || file.mkdirs();
                for (String filename : fileList) {
                    result &= copyAssetFolder(assets, srcName + File.separator + filename,
                            dstName + File.separator + filename);
                }
            }
            return result;
        } catch (IOException e) {
            Server.LOG.warning("Could not list the recordings " + srcName + ": " + e);
            return false;
        }
    }

    /** Copies one asset, written aside and renamed so a stop half way leaves no truncated file. A file already there is kept. */
    public static boolean copyAssetFile(Assets assets, String srcName, String dstName) {
        File outFile = new File(dstName);
        if (outFile.exists()) {
            return true;
        }
        File aside = new File(dstName + ".tmp");
        try {
            InputStream in = assets.open(srcName);
            OutputStream out = new FileOutputStream(aside);
            try {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                in.close();
                out.close();
            }
            Store.replace(aside, outFile);
            return true;
        } catch (IOException e) {
            aside.delete();
            Server.LOG.warning("Could not copy the recording " + srcName + ": " + e);
            return false;
        }
    }
}
