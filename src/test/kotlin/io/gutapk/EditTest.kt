package io.gutapk

import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.IconImage
import io.gutapk.core.edit.IconRefusal
import io.gutapk.tools.Storage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
