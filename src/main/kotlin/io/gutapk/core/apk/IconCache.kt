package io.gutapk.core.apk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// Names and icons read from phones, kept under the root so a list opens
// at once the next time, on this phone or another one. The key is the
// package and the size of its base APK: a new version of an app nearly
// always changes that size, so an updated icon is read again instead of
// the old one shown. The same app at the same version on two phones has
// the same base APK, so it shares one entry.
object IconCache {
    private const val VERSION = 1
    private const val MAX_BYTES = 8 shl 20
    private const val MAX_DEPTH = 32
    private const val MAX_CHILDREN = 20_000
    private val SAFE = Regex("""[A-Za-z0-9_.]+""")

    fun file(dir: Path, pkg: String, size: Long): Path? =
        if (SAFE.matches(pkg)) dir.resolve("$pkg-$size.icon") else null

    fun read(file: Path): RemoteIcon? = runCatching {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null
        DataInputStream(Files.newInputStream(file).buffered()).use { i ->
            if (i.readInt() != VERSION) return null
            val label = if (i.readBoolean()) i.readUTF() else null
            val declared = i.readBoolean()
            val bitmap = when (i.readByte().toInt()) {
                1 -> ByteArray(bounded(i.readInt(), MAX_BYTES)).also { i.readFully(it) }
                else -> null
            }
            val art = if (i.readBoolean()) readArt(i) else null
            RemoteIcon(label, bitmap, art, declared)
        }
    }.getOrNull()

    // Written aside then moved, a list open on two phones never reads half
    // an entry.
    fun write(file: Path, icon: RemoteIcon) {
        Files.createDirectories(file.parent)
        val part = file.resolveSibling(file.fileName.toString() + ".part")
        DataOutputStream(Files.newOutputStream(part).buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeBoolean(icon.label != null)
            icon.label?.let { o.writeUTF(it.take(1000)) }
            o.writeBoolean(icon.declared)
            val b = icon.bitmap
            if (b != null) {
                o.writeByte(1)
                o.writeInt(b.size)
                o.write(b)
            } else {
                o.writeByte(0)
            }
            val art = icon.art
            o.writeBoolean(art != null)
            if (art != null) writeArt(o, art)
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun bounded(n: Int, max: Int): Int {
        if (n < 0 || n > max) throw IOException("cache entry out of bounds")
        return n
    }

    private fun writeArt(o: DataOutputStream, a: IconArt) {
        o.writeBoolean(a.adaptive)
        writeLayer(o, a.background)
        writeLayer(o, a.foreground)
    }

    private fun readArt(i: DataInputStream): IconArt {
        val adaptive = i.readBoolean()
        return IconArt(readLayer(i), readLayer(i), adaptive)
    }

    private fun writeLayer(o: DataOutputStream, l: IconLayer?) {
        when (l) {
            null -> o.writeByte(0)
            is IconLayer.Colour -> {
                o.writeByte(1)
                writeColour(o, l.colour)
            }
            is IconLayer.Vector -> {
                o.writeByte(2)
                o.writeFloat(l.art.viewportWidth)
                o.writeFloat(l.art.viewportHeight)
                writeGroup(o, l.art.root)
            }
        }
    }

    private fun readLayer(i: DataInputStream): IconLayer? = when (i.readByte().toInt()) {
        0 -> null
        1 -> IconLayer.Colour(readColour(i) ?: throw IOException("layer without colour"))
        2 -> {
            val w = i.readFloat()
            val h = i.readFloat()
            IconLayer.Vector(VectorArt(w, h, readGroup(i, 0)))
        }
        else -> throw IOException("unknown layer")
    }

    private fun writeColour(o: DataOutputStream, c: IconColour?) {
        when (c) {
            null -> o.writeByte(0)
            is IconColour.Argb -> {
                o.writeByte(1)
                o.writeInt(c.argb)
            }
            is IconColour.System -> {
                o.writeByte(2)
                o.writeInt(c.palette)
                o.writeInt(c.tone)
            }
        }
    }

    private fun readColour(i: DataInputStream): IconColour? = when (i.readByte().toInt()) {
        0 -> null
        1 -> IconColour.Argb(i.readInt())
        2 -> IconColour.System(i.readInt(), i.readInt())
        else -> throw IOException("unknown colour")
    }

    private fun writeGroup(o: DataOutputStream, g: IconGroup) {
        listOf(g.rotation, g.pivotX, g.pivotY, g.scaleX, g.scaleY, g.translateX, g.translateY).forEach { o.writeFloat(it) }
        o.writeInt(g.children.size)
        g.children.forEach { n ->
            when (n) {
                is IconGroup -> {
                    o.writeByte(2)
                    writeGroup(o, n)
                }
                is IconPath -> {
                    o.writeByte(1)
                    val data = n.data.toByteArray(Charsets.UTF_8)
                    o.writeInt(data.size)
                    o.write(data)
                    writeColour(o, n.fill)
                    o.writeFloat(n.fillAlpha)
                    writeColour(o, n.stroke)
                    o.writeFloat(n.strokeWidth)
                    o.writeFloat(n.strokeAlpha)
                    o.writeBoolean(n.evenOdd)
                }
            }
        }
    }

    private fun readGroup(i: DataInputStream, depth: Int): IconGroup {
        if (depth > MAX_DEPTH) throw IOException("groups nested too deep")
        val f = FloatArray(7) { i.readFloat() }
        val count = bounded(i.readInt(), MAX_CHILDREN)
        val children = List(count) {
            when (i.readByte().toInt()) {
                2 -> readGroup(i, depth + 1)
                1 -> {
                    val data = ByteArray(bounded(i.readInt(), MAX_BYTES)).also { i.readFully(it) }
                    IconPath(
                        data = String(data, Charsets.UTF_8),
                        fill = readColour(i),
                        fillAlpha = i.readFloat(),
                        stroke = readColour(i),
                        strokeWidth = i.readFloat(),
                        strokeAlpha = i.readFloat(),
                        evenOdd = i.readBoolean(),
                    )
                }
                else -> throw IOException("unknown node")
            }
        }
        return IconGroup(f[0], f[1], f[2], f[3], f[4], f[5], f[6], children)
    }
}
