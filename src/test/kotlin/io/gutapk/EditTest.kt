package io.gutapk

import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.IconImage
import io.gutapk.core.edit.IconRefusal
import io.gutapk.core.edit.Label
import io.gutapk.core.edit.Modern
import io.gutapk.core.edit.Neutralise
import io.gutapk.core.edit.PackageId
import io.gutapk.core.edit.PackageIdRefusal
import io.gutapk.core.edit.Sdk
import io.gutapk.core.edit.SdkProblem
import io.gutapk.core.edit.Security
import io.gutapk.core.edit.Size
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.Storage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The themed icon edit works on the text APKEditor decodes, so it is tested
// on that text directly, in the layout APKEditor 1.4.9 writes.
class EditTest {
    private val adaptive = """
        <?xml version='1.0' encoding='utf-8' ?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
          <background android:drawable="@color/ic_launcher_background" />
          <foreground android:drawable="@drawable/ic_launcher_foreground" />
        </adaptive-icon>
    """.trimIndent()

    @Test
    fun addsAMonochromeLayerFromTheForeground() {
        val out = Edit.withMonochrome(adaptive)
        val expected = """
            <?xml version='1.0' encoding='utf-8' ?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@color/ic_launcher_background" />
              <foreground android:drawable="@drawable/ic_launcher_foreground" />
              <monochrome android:drawable="@drawable/ic_launcher_foreground" />
            </adaptive-icon>
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun leavesAThemedIconAlone() {
        val themed = Edit.withMonochrome(adaptive)!!
        assertEquals(themed, Edit.withMonochrome(themed))
    }

    @Test
    fun skipsAnInlineForeground() {
        val inline = """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <foreground>
                <inset android:drawable="@drawable/logo" android:inset="16dp" />
              </foreground>
            </adaptive-icon>
        """.trimIndent()
        assertNull(Edit.withMonochrome(inline))
    }

    // The entries of a decoded public.xml are not sorted. Taking the id after
    // the last line gave 0x7f04001e here, already ic_unarchive's, and
    // APKEditor silently dropped that resource.
    private val publicXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
          <public id="0x7f04001e" type="drawable" name="ic_unarchive" />
          <public id="0x7f020004" type="color" name="ic_launcher_background" />
          <public id="0x7f04001d" type="drawable" name="ic_settings" />
        </resources>
    """.trimIndent()

    @Test
    fun declaresTheIconPastTheHighestId() {
        val out = Edit.withPublic(publicXml, "drawable", "gutapk_icon")!!
        assertTrue(out.contains("""<public id="0x7f04001f" type="drawable" name="gutapk_icon" />"""))
        assertEquals(out, Edit.withPublic(out, "drawable", "gutapk_icon"))
        assertNull(Edit.withPublic(publicXml, "mipmap", "gutapk_icon"))
    }

    @Test
    fun pointsForegroundAndMonochromeAtThePicture() {
        val themed = Edit.withMonochrome(adaptive)!!
        val out = Edit.withIcon(themed, "@drawable/gutapk_icon")!!
        assertTrue(out.contains("""<foreground android:drawable="@drawable/gutapk_icon" />"""))
        assertTrue(out.contains("""<monochrome android:drawable="@drawable/gutapk_icon" />"""))
        assertTrue(out.contains("""<background android:drawable="@color/ic_launcher_background" />"""))
    }

    private fun png(dir: Path, name: String, width: Int, height: Int): Path {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.RED
        g.fillRect(0, 0, width, height)
        g.dispose()
        val file = dir.resolve(name)
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    @Test
    fun checksAndPlacesThePicture() {
        val dir = Files.createTempDirectory("gutapk-icon")
        try {
            val good = png(dir, "good.png", 512, 512)
            assertNull(IconImage.check(good).refusal)
            assertEquals(IconRefusal.NOT_SQUARE, IconImage.check(png(dir, "wide.png", 600, 500)).refusal)
            assertEquals(IconRefusal.TOO_SMALL, IconImage.check(png(dir, "small.png", 200, 200)).refusal)
            val text = dir.resolve("text.png")
            Files.writeString(text, "not an image")
            assertEquals(IconRefusal.NOT_PNG, IconImage.check(text).refusal)

            // Opaque in the centre, transparent outside the safe zone.
            val layer = IconImage.foreground(good)
            assertEquals(432, layer.width)
            assertEquals(0xff, layer.getRGB(216, 216) ushr 24)
            assertEquals(0, layer.getRGB(10, 10) ushr 24)
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun checksPackageIds() {
        assertNull(PackageId.check("com.example.clone"))
        assertEquals(PackageIdRefusal.FORMAT, PackageId.check("example"))
        assertEquals(PackageIdRefusal.FORMAT, PackageId.check("com.Example.app"))
        assertEquals(PackageIdRefusal.FORMAT, PackageId.check("com.1example"))
        assertEquals(PackageIdRefusal.RESERVED, PackageId.check("com.google.clone"))
        assertEquals(PackageIdRefusal.RESERVED, PackageId.check("android.clone"))
        assertEquals(PackageIdRefusal.RESERVED, PackageId.check("org.lineageos.x"))
        assertNull(PackageId.check("com.googlex.app"))
    }

    // Identity moves, code does not: class names stay, relative ones become
    // absolute, the app's own permission and authorities follow the new id.
    @Test
    fun renamesThePackage() {
        val sep = PackageId.AUTHORITY_SEPARATOR
        val manifest = """
            <manifest android:versionCode="42"
                      package="com.old.app" xmlns:android="http://schemas.android.com/apk/res/android">
              <permission android:name="com.old.app.OWN_PERMISSION" android:protectionLevel="signature" />
              <uses-permission android:name="com.old.app.OWN_PERMISSION" />
              <uses-permission android:name="android.permission.INTERNET" />
              <application android:name=".App" android:label="@string/app_name">
                <activity android:name="com.old.app.MainActivity" />
                <activity android:name="Settings" />
                <provider android:name="androidx.startup.InitializationProvider"
                          android:authorities="com.old.app.androidx-startup${sep}com.other.lib" />
                <meta-data android:name="preloaded" android:value="x" />
              </application>
            </manifest>
        """.trimIndent()
        val r = PackageId.rename(manifest, "com.new.clone")
        assertEquals("com.old.app", r.from)
        assertTrue(r.text.contains("package=\"com.new.clone\""))
        assertTrue(r.text.contains("android:name=\"com.old.app.App\""))
        assertTrue(r.text.contains("android:name=\"com.old.app.MainActivity\""))
        assertTrue(r.text.contains("android:name=\"com.old.app.Settings\""))
        assertTrue(r.text.contains("<permission android:name=\"com.new.clone.OWN_PERMISSION\""))
        assertTrue(r.text.contains("<uses-permission android:name=\"com.new.clone.OWN_PERMISSION\""))
        assertTrue(r.text.contains("android.permission.INTERNET"))
        assertTrue(r.text.contains("android:authorities=\"com.new.clone.androidx-startup${sep}com.other.lib\""))
        assertTrue(r.text.contains("<meta-data android:name=\"preloaded\""))
        assertFalse(r.text.contains("package=\"com.old.app\""))
        assertEquals(1, r.permissions)
        assertEquals(1, r.authorities)
        assertEquals(2, r.expanded)
        assertEquals(
            mapOf(
                "com.old.app" to "com.new.clone",
                "com.old.app.OWN_PERMISSION" to "com.new.clone.OWN_PERMISSION",
                "com.old.app.androidx-startup" to "com.new.clone.androidx-startup",
            ),
            r.map,
        )
    }

    // apktool writes the id last, APKEditor first. Both must give the same
    // next id.
    @Test
    fun readsApktoolPublicXml() {
        val apktool = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <public type="drawable" name="ic_unarchive" id="0x7f04001e" />
                <public type="color" name="ic_launcher_background" id="0x7f020004" />
                <public type="drawable" name="ic_settings" id="0x7f04001d" />
            </resources>
        """.trimIndent()
        val out = Edit.withPublic(apktool, "drawable", "gutapk_icon")!!
        assertTrue(out.contains("id=\"0x7f04001f\""))
        assertEquals(out, Edit.withPublic(out, "drawable", "gutapk_icon"))
    }

    // apktool keeps the SDK levels in apktool.yml.
    @Test
    fun setsSdkInApktoolYml() {
        val yml = "version: 3.0.3\nsdkInfo:\n  minSdkVersion: 31\n  targetSdkVersion: 37\nversionInfo:\n  versionCode: 42\n"
        val lowered = Edit.yamlSdk(yml, "minSdkVersion", 26)
        assertTrue(lowered.contains("  minSdkVersion: 26\n"))
        assertTrue(lowered.contains("  targetSdkVersion: 37\n"))
        assertEquals(yml, Edit.yamlSdk(yml, "minSdkVersion", null))

        val noTarget = "sdkInfo:\n  minSdkVersion: 31\nversionInfo:\n  versionCode: 42\n"
        assertTrue(Edit.yamlSdk(noTarget, "targetSdkVersion", 35).contains("sdkInfo:\n  targetSdkVersion: 35\n  minSdkVersion: 31"))

        val noSection = "version: 3.0.3\n"
        assertEquals("version: 3.0.3\nsdkInfo:\n  minSdkVersion: 26\n", Edit.yamlSdk(noSection, "minSdkVersion", 26))
    }

    // Whole strings follow the rename, longer ones that only start with the
    // old id stay: a class name, a preferences file.
    @Test
    fun renamesWholeLiteralsOnly() {
        val map = mapOf("com.old.app" to "com.new.clone", "com.old.app.files" to "com.new.clone.files")
        val smali = """
            const-string v0, "com.old.app"
            const-string v1, "com.old.app.files"
            const-string v2, "content://com.old.app.files/shared/a.png"
            const-string v3, "com.old.app.MainActivity"
            const-string v4, "com.old.app_preferences"
        """.trimIndent()
        val (out, n) = PackageId.renameLiterals(smali, map)
        assertEquals(3, n)
        assertTrue(out.contains("const-string v0, \"com.new.clone\""))
        assertTrue(out.contains("const-string v1, \"com.new.clone.files\""))
        assertTrue(out.contains("\"content://com.new.clone.files/shared/a.png\""))
        assertTrue(out.contains("\"com.old.app.MainActivity\""))
        assertTrue(out.contains("\"com.old.app_preferences\""))

        val strings = "<string name=\"authority\">com.old.app.files</string>"
        assertEquals("<string name=\"authority\">com.new.clone.files</string>", PackageId.renameLiterals(strings, map).first)
        val json = "\"package_name\": \"com.old.app\","
        assertEquals("\"package_name\": \"com.new.clone\",", PackageId.renameLiterals(json, map).first)
    }

    @Test
    fun setsApplicationAttributes() {
        val manifest = "<manifest>\n  <application android:label=\"@string/app_name\"\n      android:extractNativeLibs=\"true\">\n  </application>\n</manifest>"
        val back = Modern.setAppAttr(manifest, "android:enableOnBackInvokedCallback", "true")
        assertTrue(back.contains("<application android:enableOnBackInvokedCallback=\"true\" android:label="))
        val libs = Modern.setAppAttr(manifest, "android:extractNativeLibs", "false")
        assertTrue(libs.contains("android:extractNativeLibs=\"false\""))
        assertFalse(libs.contains("android:extractNativeLibs=\"true\""))
    }

    @Test
    fun readsLocaleFolders() {
        assertEquals("fr", Modern.localeTag("values-fr"))
        assertEquals("pt-BR", Modern.localeTag("values-pt-rBR"))
        assertEquals("es-419", Modern.localeTag("values-es-r419"))
        assertEquals("sr-Latn", Modern.localeTag("values-b+sr+Latn"))
        assertEquals("fr", Modern.localeTag("values-fr-v21"))
        assertNull(Modern.localeTag("values-night"))
        assertNull(Modern.localeTag("values-v31"))
        assertNull(Modern.localeTag("values-car"))
        assertNull(Modern.localeTag("values"))
        assertEquals("pt-BR", Modern.localeTag("values-mcc724-mnc05-pt-rBR"))
        assertNull(Modern.localeTag("values-hdr"))
        assertNull(Modern.localeTag("values-mcc310"))
        assertTrue(Modern.localeConfigXml(listOf("fr", "pt-BR")).contains("<locale android:name=\"pt-BR\" />"))
    }

    // Libraries read from the APK must be stored, in either engine's list.
    @Test
    fun storesNativeLibraries() {
        val json = "{\n  \"extensions\": [\n    \".png\"\n  ],\n  \"paths\": []\n}"
        val stored = Modern.storeSoApkEditor(json)
        assertTrue(stored.contains("\".so\""))
        assertEquals(stored, Modern.storeSoApkEditor(stored))
        assertTrue(Modern.storeSoApkEditor("{\"extensions\": [], \"paths\": []}").contains("\".so\""))

        val yml = "version: 3.0.3\ndoNotCompress:\n- arsc\n- dex\nsdkInfo:\n  minSdkVersion: 31\n"
        val added = Modern.storeSoApktool(yml)
        assertTrue(added.contains("doNotCompress:\n- so\n- arsc"))
        assertEquals(added, Modern.storeSoApktool(added))
        assertTrue(Modern.storeSoApktool("version: 3.0.3\n").endsWith("doNotCompress:\n- so\n"))
    }

    // Plain http off everywhere, user certificates out, and a block that
    // held only them trusts the system instead of nothing.
    @Test
    fun tightensANetworkConfig() {
        val config = """
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
              <domain-config cleartextTrafficPermitted="true">
                <domain includeSubdomains="true">example.com</domain>
                <trust-anchors>
                  <certificates src="user" />
                </trust-anchors>
              </domain-config>
            </network-security-config>
        """.trimIndent()
        val out = Security.strictNetworkConfig(config)
        assertFalse(out.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(out.contains("src=\"user\""))
        assertTrue(out.contains("<certificates src=\"system\" />"))
        assertTrue(out.contains("<base-config cleartextTrafficPermitted=\"false\" />"))
        assertEquals(out, Security.strictNetworkConfig(out))

        val base = "<network-security-config>\n  <base-config>\n  </base-config>\n</network-security-config>"
        assertTrue(Security.strictNetworkConfig(base).contains("<base-config cleartextTrafficPermitted=\"false\">"))
        assertTrue(Security.newNetworkConfig().contains("<certificates src=\"system\" />"))
    }

    // Only the tracker's own components are disabled, the app's and
    // Firebase's init provider stay. Opt-outs follow the chosen trackers.
    @Test
    fun silencesATracker() {
        val manifest = """
            <manifest package="com.example.app">
              <application android:label="x">
                <activity android:name=".MainActivity" android:exported="true">
                </activity>
                <service android:name="com.google.android.gms.measurement.AppMeasurementService" android:exported="false" />
                <receiver android:name="com.google.android.gms.measurement.AppMeasurementReceiver">
                </receiver>
                <provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="x.firebaseinitprovider" />
                <meta-data android:name="google_analytics_adid_collection_enabled" android:value="true" />
              </application>
            </manifest>
        """.trimIndent()
        val prefixes = setOf("com.google.firebase.analytics.FirebaseAnalytics", "com.google.android.gms.measurement.")
        val (out, n) = Neutralise.disableComponents(manifest, prefixes)
        assertEquals(2, n)
        assertTrue(out.contains("AppMeasurementService\" android:exported=\"false\" android:enabled=\"false\" />"))
        assertTrue(out.contains("AppMeasurementReceiver\" android:enabled=\"false\">"))
        assertFalse(out.contains("FirebaseInitProvider\" android:authorities=\"x.firebaseinitprovider\" android:enabled"))
        assertFalse(out.contains("MainActivity\" android:exported=\"true\" android:enabled"))

        val opts = Neutralise.optOuts(prefixes)
        assertEquals("true", opts["firebase_analytics_collection_deactivated"])
        assertEquals("false", opts["google_analytics_adid_collection_enabled"])
        assertNull(opts["firebase_crashlytics_collection_enabled"])

        var meta = out
        opts.forEach { (k, v) -> meta = Neutralise.setMetaData(meta, k, v) }
        assertTrue(meta.contains("<meta-data android:name=\"google_analytics_adid_collection_enabled\" android:value=\"false\" />"))
        assertTrue(meta.contains("<meta-data android:name=\"firebase_analytics_collection_deactivated\" android:value=\"true\" />"))
        assertEquals(1, Regex("google_analytics_adid_collection_enabled").findAll(meta).count())
    }

    @Test
    fun readsFolderLanguages() {
        assertEquals("fr", Size.languageOf("values-fr"))
        assertEquals("fr", Size.languageOf("drawable-fr-rCA-xxhdpi"))
        assertEquals("sr", Size.languageOf("raw-b+sr+Latn"))
        assertNull(Size.languageOf("values"))
        assertNull(Size.languageOf("values-night"))
        assertNull(Size.languageOf("mipmap-anydpi-v26"))
        assertNull(Size.languageOf("values-car"))
        // Fix 6: network codes come before the language, hdr is a colour mode.
        assertEquals("fr", Size.languageOf("values-mcc310-fr"))
        assertEquals("pt", Size.languageOf("values-mcc724-mnc05-pt-rBR"))
        assertNull(Size.languageOf("values-mcc310"))
        assertNull(Size.languageOf("values-hdr"))
    }

    // Debug lines go, every instruction stays, the final newline too.
    @Test
    fun stripsDebugLines() {
        val smali = listOf(
            ".method public run()V",
            "    .registers 2",
            "    .line 42",
            "    const-string v0, \"x\"",
            "    .local v0, \"name\"",
            "    return-void",
            ".end method",
            "",
        ).joinToString("\n")
        val (out, n) = Size.stripDebug(smali)
        assertEquals(2, n)
        assertFalse(out.contains(".line"))
        assertFalse(out.contains(".local"))
        assertTrue(out.contains("const-string v0, \"x\"\n    return-void"))
        assertTrue(out.endsWith(".end method\n"))
    }

    // A permission declared before the application, a settings activity with
    // its own name, and the launcher: only the application and the launcher
    // take the new name. Fix 1 of the 2026-09-24 review.
    private val labelled = """
        <manifest package="com.example.game">
          <permission android:name="com.example.game.C2D" android:label="Game messages"/>
          <application android:label="@string/app_name" android:icon="@mipmap/ic">
            <activity android:name=".Settings" android:label="Settings"/>
            <activity android:name=".Main" android:label="@string/launcher_name">
              <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
              </intent-filter>
            </activity>
            <activity android:name=".Help" android:label="Help">
              <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
              </intent-filter>
            </activity>
          </application>
        </manifest>
    """.trimIndent()

    @Test
    fun renameTouchesTheApplicationAndTheLauncherOnly() {
        val plan = Label.plan(labelled, "New")
        assertEquals(listOf("launcher_name", "app_name"), plan.strings)
        assertEquals(0, plan.literals)
        assertEquals(labelled, plan.manifest)
    }

    @Test
    fun renameReplacesLiteralsWhereTheNameShows() {
        val literal = labelled
            .replace("android:label=\"@string/app_name\"", "android:label=\"Old\"")
            .replace("android:label=\"@string/launcher_name\"", "android:label=\"Old launcher\"")
        val plan = Label.plan(literal, "New")
        assertEquals(emptyList(), plan.strings)
        assertEquals(2, plan.literals)
        assertTrue(plan.manifest.contains("<application android:label=\"New\""))
        assertTrue(plan.manifest.contains("android:name=\".Main\" android:label=\"New\""))
        assertTrue(plan.manifest.contains("android:label=\"Game messages\""))
        assertTrue(plan.manifest.contains("android:label=\"Settings\""))
        assertTrue(plan.manifest.contains("android:label=\"Help\""))
    }

    @Test
    fun renameAddsALabelTheApplicationLacks() {
        val bare = "<manifest>\n  <application android:icon=\"@mipmap/ic\">\n    <activity-alias android:name=\".Alias\" android:label=\"Alias\">\n" +
            "      <intent-filter><action android:name=\"android.intent.action.MAIN\"/>" +
            "<category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter>\n    </activity-alias>\n  </application>\n</manifest>"
        val plan = Label.plan(bare, "New")
        assertTrue(plan.manifest.contains("<application android:label=\"New\" android:icon="))
        assertTrue(plan.manifest.contains("<activity-alias android:name=\".Alias\" android:label=\"New\">"))
        assertEquals(2, plan.literals)
        assertEquals(1, Label.launchers(bare).size)
    }

    // Fix 3 of the 2026-09-24 review: 0, 99 and a minimum above the target
    // were accepted.
    @Test
    fun sdkLevelsStayInstallable() {
        val highest = Sdk.highest(21, 34)
        assertEquals(Sdk.NEWEST, highest)
        assertEquals(40, Sdk.highest(21, 40))
        assertEquals(SdkProblem.OutOfRange(1, highest), Sdk.checkMin(0, 34, highest))
        assertEquals(SdkProblem.OutOfRange(1, highest), Sdk.checkTarget(99, 21, highest))
        assertEquals(SdkProblem.MinAboveTarget(34), Sdk.checkMin(35, 34, highest))
        assertEquals(SdkProblem.TargetBelowMin(21), Sdk.checkTarget(19, 21, highest))
        assertNull(Sdk.checkMin(24, 34, highest))
        assertNull(Sdk.checkTarget(highest, 21, highest))
        assertNull(Sdk.checkMin(24, null, highest))
    }

    // Fix 5 of the 2026-09-24 review: with several resource packages the
    // first one listed was taken. The app's own is id 0x7f, named like the
    // manifest's package when two share the id.
    @Test
    fun picksTheAppsOwnResourcePackage() {
        val dir = Files.createTempDirectory("gutapk-res")
        try {
            fun pkg(folder: String, id: Int, name: String) {
                val p = dir.resolve("resources").resolve(folder)
                Files.createDirectories(p.resolve("res").resolve("values"))
                Files.writeString(p.resolve("package.json"), "{\n  \"arsc_lib_version\": \"1.3.9\",\n  \"package_id\": $id,\n  \"package_name\": \"$name\"\n}")
            }
            Files.writeString(dir.resolve("AndroidManifest.xml"), "<manifest xmlns:android=\"x\" package=\"com.example.game\">\n</manifest>")
            pkg("package_1", 2, "com.example.lib")
            pkg("package_10", 127, "com.example.other")
            pkg("package_2", 127, "com.example.game")
            assertEquals(dir.resolve("resources/package_2/res"), Edit.mainResDir(dir))

            Files.writeString(dir.resolve("resources/package_2/package.json"), "{\"package_id\": 127, \"package_name\": \"renamed\"}")
            assertEquals(dir.resolve("resources/package_2/res"), Edit.mainResDir(dir))

            Storage.deleteTree(dir.resolve("resources"), dir)
            assertNull(Edit.mainResDir(dir))
            Files.createDirectories(dir.resolve("res"))
            assertEquals(dir.resolve("res"), Edit.mainResDir(dir))
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    // The uses-sdk pattern held a backspace byte instead of \b from the first
    // upload, so it never matched and a second uses-sdk was always added.
    // uses-sdk-library must not count as uses-sdk either (fix 6).
    @Test
    fun setsSdkOnTheUsesSdkElement() {
        val quiet = object : JobSink {
            override fun emit(event: JobEvent) {}
        }
        val present = "<manifest package=\"a\">\n  <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"30\"/>\n</manifest>"
        val changed = Edit.setSdk(present, 24, 34, quiet)
        assertEquals(1, Regex("<uses-sdk").findAll(changed).count())
        assertTrue(changed.contains("<uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\"/>"))

        val library = "<manifest package=\"a\">\n  <application>\n    <uses-sdk-library android:name=\"x\" android:versionMajor=\"1\"/>\n  </application>\n</manifest>"
        val added = Edit.setSdk(library, 24, null, quiet)
        assertTrue(added.contains("<manifest package=\"a\">\n  <uses-sdk android:minSdkVersion=\"24\" />"))
        assertTrue(added.contains("<uses-sdk-library android:name=\"x\""))

        val minOnly = "<manifest package=\"a\">\n  <uses-sdk android:minSdkVersion=\"21\"/>\n</manifest>"
        assertTrue(Edit.setSdk(minOnly, null, 34, quiet).contains("<uses-sdk android:targetSdkVersion=\"34\" android:minSdkVersion=\"21\"/>"))
    }

    // Fix 9: both the plain and the sdk-23 form go, every occurrence, and a
    // permission whose name only starts the same stays.
    @Test
    fun removesPermissionsInBothForms() {
        val quiet = object : JobSink {
            override fun emit(event: JobEvent) {}
        }
        val manifest = listOf(
            "<manifest package=\"a\">",
            "  <uses-permission android:name=\"android.permission.CAMERA\"/>",
            "  <uses-permission-sdk-23 android:name=\"android.permission.CAMERA\"/>",
            "  <uses-permission android:name=\"android.permission.CAMERA_EXTRA\"/>",
            "  <uses-permission android:name=\"android.permission.INTERNET\" android:maxSdkVersion=\"30\"/>",
            "  <application/>",
            "</manifest>",
        ).joinToString("\n")
        val out = Edit.removePermissions(manifest, setOf("android.permission.CAMERA", "android.permission.INTERNET", "not.there"), quiet)
        assertEquals(
            listOf(
                "<manifest package=\"a\">",
                "  <uses-permission android:name=\"android.permission.CAMERA_EXTRA\"/>",
                "  <application/>",
                "</manifest>",
            ).joinToString("\n"),
            out,
        )
    }

    // What each engine needs for the name to compile to itself, as read
    // from the bytes of real rebuilds of an APK on 2026-09-25.
    @Test
    fun labelEscapesPerEngine() {
        assertEquals("Bob's &quot;Clock&quot; &amp; @y", Label.escape("Bob's \"Clock\" & @y", aapt = false))
        assertEquals("@Home", Label.escape("@Home", aapt = false))
        assertEquals("\\@Ann\\'s \\&quot;X\\&quot; &amp; Z", Label.escape("@Ann's \"X\" & Z", aapt = true))
        assertEquals("\\?a\\\\b @c ?d", Label.escape("?a\\b @c ?d", aapt = true))
        assertEquals("&lt;b&gt;", Label.escape("<b>", aapt = true))
    }

    // The forms both engines write pass, the ones the patterns would cut
    // at the wrong place are refused before any edit.
    @Test
    fun manifestShapeGuardsThePatterns() {
        val shape = io.gutapk.core.edit.ManifestShape
        assertEquals(null, shape.problem(labelled))
        assertEquals(null, shape.problem("<?xml version='1.0' encoding='utf-8' ?>\n<manifest a=\"x &gt; y\" b='say \"hi\"'/>"))
        assertTrue(shape.problem("<manifest><application android:label=\"a > b\"/></manifest>") != null)
        assertTrue(shape.problem("<manifest><application android:label='a < b'/></manifest>") != null)
        assertTrue(shape.problem("<manifest><!-- note --></manifest>") != null)
    }

    // The advice follows the calls found in the app's own dex files.
    @Test
    fun advisesPerAppFromItsCalls() {
        val r = io.gutapk.core.edit.PermissionRisks
        val calls = setOf(
            "android.os.PowerManager\$WakeLock.acquire",
            "com.a.SyncService.startForeground",
            "java.net.URL.openConnection",
        )
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Keep(listOf("PowerManager.WakeLock.acquire")), r.advice("android.permission.WAKE_LOCK", "com.a", calls, false))
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Keep(listOf("SyncService.startForeground")), r.advice("android.permission.FOREGROUND_SERVICE_DATA_SYNC", "com.a", calls, false))
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Unused, r.advice("android.permission.ACCESS_NETWORK_STATE", "com.a", calls, false))
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Online(listOf("URL.openConnection"), true), r.advice("android.permission.INTERNET", "com.a", calls, true))
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Online(emptyList(), false), r.advice("android.permission.INTERNET", "com.a", emptySet(), false))
        assertEquals(io.gutapk.core.edit.PermissionAdvice.Own, r.advice("com.a.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "com.a", calls, false))
        assertEquals(null, r.advice("com.google.android.gms.permission.AD_ID", "com.a", calls, false))
        assertTrue(r.wanted("android.net.ConnectivityManager", "getActiveNetworkInfo"))
        assertTrue(r.wanted("com.x.AnyService", "startForeground"))
        assertFalse(r.wanted("android.os.PowerManager", "acquire"))
    }

    @Test
    fun readsSmaliEntries() {
        val code = io.gutapk.core.edit.SmaliCode
        val c = code.classOf("classes2/com/x/Main\$1.smali")
        assertEquals("com.x.Main\$1", c?.name)
        assertEquals("classes2", c?.dex)
        assertEquals(null, code.classOf("classes/readme.txt"))
        val all = listOfNotNull(code.classOf("classes/com/x/Main.smali"), code.classOf("classes/com/x/net/Api.smali"), c)
        assertEquals(listOf("com.x.net.Api"), code.search(all, "x API", 10).second.map { it.name })
        assertEquals(3, code.search(all, "com", 1).first)
    }

    // An edit is kept with the package and written at rebuild only over
    // the exact text it was made on.
    @Test
    fun smaliEditsKeepAndApply() {
        val base = java.nio.file.Files.createTempDirectory("gutapk-smali")
        try {
            val code = io.gutapk.core.edit.SmaliCode
            val pkg = code.dir(base.resolve("pkg"))
            java.nio.file.Files.createDirectories(pkg)
            val entry = "classes/com/x/Main.smali"
            val original = ".class public Lcom/x/Main\n.method a()V\n    const-string v0, \"old\"\n.end method\n"
            java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(pkg.resolve("smali.zip"))).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry(entry))
                z.write(original.toByteArray())
                z.closeEntry()
            }
            java.nio.file.Files.writeString(pkg.resolve("code.properties"), "classes=1\ntool=1.4.9\n")
            assertEquals(original, code.current(pkg, entry))

            val edited = original.replace("old", "new")
            code.save(pkg, entry, edited)
            assertEquals(1, code.edits(pkg).size)
            assertEquals(edited, code.current(pkg, entry))
            val (count, hits) = code.grep(pkg, "NEW\"", 10) { true }
            assertEquals(1, count)
            assertEquals(3, hits.single().line)
            assertEquals("com.x.Main", hits.single().cls.name)
            assertEquals(0, code.grep(pkg, "old\"", 10) { true }.first)

            val decoded = base.resolve("decoded")
            java.nio.file.Files.createDirectories(decoded.resolve("classes/com/x"))
            java.nio.file.Files.writeString(decoded.resolve(entry), original)
            code.apply(decoded, pkg, code.edits(pkg)) {}
            assertEquals(edited, java.nio.file.Files.readString(decoded.resolve(entry)))
            assertFailsWith<io.gutapk.tools.CheckFailed> { code.apply(decoded, pkg, code.edits(pkg)) {} }

            val sink = object : io.gutapk.job.JobSink { override fun emit(event: io.gutapk.job.JobEvent) {} }
            val out = base.resolve("export")
            assertEquals(1, code.export(pkg, out, sink) { false })
            assertEquals(edited, java.nio.file.Files.readString(out.resolve(entry)))
            assertFailsWith<io.gutapk.tools.CheckFailed> { code.export(pkg, out, sink) { false } }

            code.save(pkg, entry, original)
            assertEquals(emptyList(), code.edits(pkg))
            assertFailsWith<io.gutapk.tools.CheckFailed> { code.save(pkg, "../../evil.smali", "x") }
        } finally {
            io.gutapk.tools.Storage.deleteTree(base, base.parent)
        }
    }

    @Test
    fun smaliStructureIsChecked() {
        val code = io.gutapk.core.edit.SmaliCode
        val ok = """
            .class public La
            .super Ljava/lang/Object
            .method public b()V
                .locals 1
                .param p1, "x"
                    .annotation runtime Lc
                    .end annotation
                .end param
                .local v0, "y"
                .end local v0
                const-string v0, ".end method"
                return-void
            .end method
        """.trimIndent()
        assertEquals(null, code.problem(ok))
        assertTrue(code.problem(ok.replace(".end method", "")).orEmpty().contains("has no .end method"))
        assertTrue(code.problem("# note\n" + ok.replace(".class public La", "")).orEmpty().contains(".class"))
        assertTrue(code.problem(ok.replace("return-void", ".method public c()V")).orEmpty().contains("inside the method"))
        assertTrue(code.problem(ok.replace(".end annotation", "")).orEmpty().contains(".annotation"))
        // Forms seen in real classes: a subannotation opened mid line, and a
        // list of them closed with a comma.
        val nested = """
            .class public La
            .annotation system Ld
                value = .subannotation Le
                    c = ""
                .end subannotation
            .end annotation
            .annotation runtime Lf
                value = {
                    .subannotation Lg
                    .end subannotation,
                    .subannotation Lg
                    .end subannotation
                }
            .end annotation
        """.trimIndent()
        assertEquals(null, code.problem(nested))
    }
}
