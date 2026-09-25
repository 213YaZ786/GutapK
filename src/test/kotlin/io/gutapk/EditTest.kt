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
import io.gutapk.tools.Storage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
