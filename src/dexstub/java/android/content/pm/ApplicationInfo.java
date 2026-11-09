// Compile-time stub of android.content.pm.ApplicationInfo, never shipped:
// where the game's APK is installed, which the host offers to friends.
//
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ApplicationInfo.java#L976
//
// Copyright (C) 2007 The Android Open Source Project, Apache License 2.0.
package android.content.pm;

public class ApplicationInfo {
    public String sourceDir;

    // The game's name on the invitation page. Declared by PackageItemInfo,
    // which ApplicationInfo extends: the phone finds the fields there.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageItemInfo.java#L136-L143
    // not ported: PackageItemInfo, name, packageName, icon, banner, logo, metaData, loadLabel (the label is read from labelRes or nonLocalizedLabel)
    public int labelRes;
    public CharSequence nonLocalizedLabel;
}
