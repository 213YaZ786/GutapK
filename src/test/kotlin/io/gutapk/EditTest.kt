package io.gutapk

import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.IconImage
import io.gutapk.core.edit.IconRefusal
import io.gutapk.core.edit.PackageId
import io.gutapk.core.edit.PackageIdRefusal
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
}
