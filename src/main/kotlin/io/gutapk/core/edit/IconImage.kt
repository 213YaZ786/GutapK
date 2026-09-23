package io.gutapk.core.edit

import io.gutapk.tools.CheckFailed
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

enum class IconRefusal { UNREADABLE, NOT_PNG, NOT_SQUARE, TOO_SMALL }

class IconCheck(val size: Int?, val refusal: IconRefusal?)

// The user's picture, checked, then laid out as an adaptive icon foreground.
// PNG only: ImageIO reads it with its alpha and no library, and the alpha is
// what gives a clean shape to the themed icon.
object IconImage {
    // 108 dp at xxxhdpi. Below it, the densest screens would show a blur.
    const val MIN_SIZE = 432
    private const val CANVAS = 432

    // 66 dp of the 108 dp layer, the safe zone no launcher mask cuts into.
    private const val SAFE = 264

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun check(file: Path): IconCheck {
        val head = runCatching { Files.newInputStream(file).use { it.readNBytes(8) } }.getOrNull()
            ?: return IconCheck(null, IconRefusal.UNREADABLE)
        if (!head.contentEquals(PNG_MAGIC)) return IconCheck(null, IconRefusal.NOT_PNG)
        val image = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
            ?: return IconCheck(null, IconRefusal.UNREADABLE)
        if (image.width != image.height) return IconCheck(image.width, IconRefusal.NOT_SQUARE)
        if (image.width < MIN_SIZE) return IconCheck(image.width, IconRefusal.TOO_SMALL)
        return IconCheck(image.width, null)
    }

    fun foreground(file: Path): BufferedImage {
        val source = ImageIO.read(file.toFile()) ?: throw CheckFailed("the icon image cannot be read: $file")
        val scaled = shrink(source, SAFE)
        val out = BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB)
        draw(out, scaled, (CANVAS - SAFE) / 2, SAFE)
        return out
    }

    // Halving step by step, bilinear each time, stays sharp where one big
    // bilinear jump would skip pixels and alias.
    private fun shrink(source: BufferedImage, target: Int): BufferedImage {
        var current = source
        var size = source.width
        while (size > target) {
            size = maxOf(size / 2, target)
            val next = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            draw(next, current, 0, size)
            current = next
        }
        return current
    }

    private fun draw(into: BufferedImage, image: BufferedImage, at: Int, size: Int) {
        val g = into.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, at, at, size, size, null)
        } finally {
            g.dispose()
        }
    }
}
