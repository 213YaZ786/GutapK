package io.gutapk.core.apk

import java.util.zip.ZipFile

// A launcher icon drawn from its vector and colour resources, for the apps
// that ship no bitmap at all. Plain data, the UI turns it into a picture.

sealed interface IconColour {
    data class Argb(val argb: Int) : IconColour

    // One of Android's Material You colours, @android:color/system_*. The
    // palette is 0 neutral1, 1 neutral2, 2 accent1, 3 accent2, 4 accent3.
    // The UI resolves it against its own seed, as the phone would against
    // the wallpaper.
    data class System(val palette: Int, val tone: Int) : IconColour
}

sealed interface IconNode

class IconPath(
    val data: String,
    val fill: IconColour?,
    val fillAlpha: Float,
    val stroke: IconColour?,
    val strokeWidth: Float,
    val strokeAlpha: Float,
    val evenOdd: Boolean,
) : IconNode

class IconGroup(
    val rotation: Float,
    val pivotX: Float,
    val pivotY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
    val children: List<IconNode>,
) : IconNode

class VectorArt(val viewportWidth: Float, val viewportHeight: Float, val root: IconGroup)

sealed interface IconLayer {
    data class Colour(val colour: IconColour) : IconLayer

    class Vector(val art: VectorArt) : IconLayer
}

// An adaptive icon has two layers of 108 dp, a plain vector icon only one.
class IconArt(val background: IconLayer?, val foreground: IconLayer?, val adaptive: Boolean)

object IconArtReader {
    private const val FLOAT = 0x04
    private const val COLOR_FIRST = 0x1c
    private const val COLOR_LAST = 0x1f
    private const val RGB8 = 0x1d
    private const val RGB4 = 0x1f

    // Framework ids of the Material You colours, checked in framework-res:
    // 13 shades per palette from 0x0106001d, in the order neutral1,
    // neutral2, accent1, accent2, accent3.
    private const val SYSTEM_FIRST = 0x0106001d
    private val SHADE_TONES = listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)

    private const val VIEWPORT_WIDTH = 0x01010402
    private const val VIEWPORT_HEIGHT = 0x01010403
    private const val PATH_DATA = 0x01010405
    private const val FILL_COLOR = 0x01010404
    private const val FILL_ALPHA = 0x010104cc
    private const val STROKE_COLOR = 0x01010406
    private const val STROKE_WIDTH = 0x01010407
    private const val STROKE_ALPHA = 0x010104cb
    private const val FILL_TYPE = 0x0101051e
    private const val TRANSLATE_X = 0x0101045a
    private const val TRANSLATE_Y = 0x0101045b
    private const val SCALE_X = 0x01010324
    private const val SCALE_Y = 0x01010325
    private const val ROTATION = 0x01010326
    private const val PIVOT_X = 0x010101b5
    private const val PIVOT_Y = 0x010101b6

    // The icon resource followed to what can be drawn. Null when it is a
    // bitmap, which the reader already shows, or a drawable kind not drawn
    // here, like a layer list or an inset.
    fun read(id: Int, table: ResourceTable, zip: ZipFile): IconArt? {
        xmlVariants(id, table, zip).forEach { xml ->
            val root = xml.firstOrNull() ?: return@forEach
            when (root.name) {
                "adaptive-icon" -> {
                    val bg = xml.firstOrNull { it.name == "background" }?.attr(Attr.DRAWABLE)
                    val fg = xml.firstOrNull { it.name == "foreground" }?.attr(Attr.DRAWABLE)
                    return IconArt(bg?.let { layer(it, table, zip, 0) }, fg?.let { layer(it, table, zip, 0) }, true)
                }
                "vector" -> return IconArt(null, IconLayer.Vector(vector(xml, table)), false)
            }
        }
        return null
    }

    private fun xmlVariants(id: Int, table: ResourceTable, zip: ZipFile): List<List<XmlElement>> =
        table.strings(id).filter { it.second.endsWith(".xml") }
            .sortedByDescending { if (it.first.density < 0xfffe) it.first.density else 0 }
            .mapNotNull { (_, path) ->
                val entry = zip.getEntry(path) ?: return@mapNotNull null
                runCatching { BinaryXml.parse(zip.getInputStream(entry).use { it.readBytes() }) }.getOrNull()
            }

    private fun layer(a: XmlAttr, table: ResourceTable, zip: ZipFile, hops: Int): IconLayer? {
        colour(a, table)?.let { return IconLayer.Colour(it) }
        if (a.type != ValueType.REFERENCE || hops > 4) return null
        xmlVariants(a.data, table, zip).forEach { xml ->
            if (xml.firstOrNull()?.name == "vector") return IconLayer.Vector(vector(xml, table))
        }
        return null
    }

    private fun colour(a: XmlAttr, table: ResourceTable?): IconColour? = when {
        a.type in COLOR_FIRST..COLOR_LAST -> IconColour.Argb(opaque(a.type, a.data))
        a.type == ValueType.REFERENCE -> colourOf(a.data, table, 0)
        else -> null
    }

    private fun colourOf(id: Int, table: ResourceTable?, hops: Int): IconColour? {
        system(id)?.let { return it }
        if (hops > 8 || table == null) return null
        val v = table.values(id).firstOrNull { it.config.language.isEmpty() } ?: table.values(id).firstOrNull() ?: return null
        return when {
            v.type in COLOR_FIRST..COLOR_LAST -> IconColour.Argb(opaque(v.type, v.data))
            v.type == ValueType.REFERENCE -> colourOf(v.data, table, hops + 1)
            else -> null
        }
    }

    // RGB formats carry no alpha of their own, they are opaque.
    private fun opaque(type: Int, data: Int): Int = if (type == RGB8 || type == RGB4) data or (0xff shl 24) else data

    private fun system(id: Int): IconColour.System? {
        val i = id - SYSTEM_FIRST
        if (i < 0 || i >= 5 * SHADE_TONES.size) return null
        return IconColour.System(i / SHADE_TONES.size, SHADE_TONES[i % SHADE_TONES.size])
    }

    // The flat element list rebuilt into groups by depth: an element deeper
    // than the open group belongs to it, the others close it. Without a
    // table, only literal and system colours resolve.
    internal fun vector(xml: List<XmlElement>, table: ResourceTable?): VectorArt {
        val root = xml.first()
        class Open(val depth: Int, val element: XmlElement?, val children: MutableList<IconNode>)
        val stack = ArrayDeque<Open>()
        stack.addLast(Open(root.depth, null, mutableListOf()))
        fun close() {
            val done = stack.removeLast()
            stack.last().children.add(group(done.element, done.children))
        }
        xml.drop(1).forEach { e ->
            while (stack.size > 1 && e.depth <= stack.last().depth) close()
            when (e.name) {
                "group" -> stack.addLast(Open(e.depth, e, mutableListOf()))
                "path" -> path(e, table)?.let { stack.last().children.add(it) }
            }
        }
        while (stack.size > 1) close()
        return VectorArt(
            viewportWidth = float(root, VIEWPORT_WIDTH) ?: 24f,
            viewportHeight = float(root, VIEWPORT_HEIGHT) ?: 24f,
            root = group(null, stack.last().children),
        )
    }

    private fun group(e: XmlElement?, children: List<IconNode>) = IconGroup(
        rotation = e?.let { float(it, ROTATION) } ?: 0f,
        pivotX = e?.let { float(it, PIVOT_X) } ?: 0f,
        pivotY = e?.let { float(it, PIVOT_Y) } ?: 0f,
        scaleX = e?.let { float(it, SCALE_X) } ?: 1f,
        scaleY = e?.let { float(it, SCALE_Y) } ?: 1f,
        translateX = e?.let { float(it, TRANSLATE_X) } ?: 0f,
        translateY = e?.let { float(it, TRANSLATE_Y) } ?: 0f,
        children = children,
    )

    private fun path(e: XmlElement, table: ResourceTable?): IconPath? {
        val data = e.attr(PATH_DATA)?.raw ?: return null
        return IconPath(
            data = data,
            fill = e.attr(FILL_COLOR)?.let { colour(it, table) },
            fillAlpha = float(e, FILL_ALPHA) ?: 1f,
            stroke = e.attr(STROKE_COLOR)?.let { colour(it, table) },
            strokeWidth = float(e, STROKE_WIDTH) ?: 0f,
            strokeAlpha = float(e, STROKE_ALPHA) ?: 1f,
            evenOdd = e.attr(FILL_TYPE)?.data == 1,
        )
    }

    private fun float(e: XmlElement, id: Int): Float? {
        val a = e.attr(id) ?: return null
        return when (a.type) {
            FLOAT -> Float.fromBits(a.data)
            ValueType.INT_DEC, ValueType.INT_HEX -> a.data.toFloat()
            else -> a.raw?.toFloatOrNull()
        }
    }
}
