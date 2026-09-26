package io.gutapk.core

// A QR code for short texts, byte mode, error correction level M,
// versions 1 to 10 (up to 213 bytes). Enough for the Wireless debugging
// pairing code the phone scans, without a library for one picture.
// Checked with the zxing-cpp decoder on 2026-09-26.
class QrCode private constructor(val size: Int, private val dark: Array<BooleanArray>) {
    fun isDark(x: Int, y: Int): Boolean = dark[y][x]

    companion object {
        // Per version at level M: error correction codewords per block,
        // blocks of the first group and their data codewords, then the
        // second group. The tables of ISO/IEC 18004.
        private val EC = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val G1 = arrayOf(1 to 16, 1 to 28, 1 to 44, 2 to 32, 2 to 43, 4 to 27, 4 to 31, 2 to 38, 3 to 36, 4 to 43)
        private val G2 = arrayOf(0 to 0, 0 to 0, 0 to 0, 0 to 0, 0 to 0, 0 to 0, 0 to 0, 2 to 39, 2 to 37, 1 to 44)
        private val ALIGN = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )
        private const val MAX_VERSION = 10

        private fun dataCodewords(v: Int): Int = G1[v - 1].first * G1[v - 1].second + G2[v - 1].first * G2[v - 1].second

        fun encode(text: String): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + 8 * bytes.size <= 8 * dataCodewords(v) }
                ?: throw IllegalArgumentException("too long for a QR code of version 10")
            val data = dataBits(bytes, version)
            val all = interleave(data, version)
            return best(version, all)
        }

        private fun dataBits(bytes: ByteArray, version: Int): IntArray {
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>()
            fun put(value: Int, count: Int) {
                for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 == 1)
            }
            put(4, 4)
            put(bytes.size, if (version < 10) 8 else 16)
            bytes.forEach { put(it.toInt() and 0xff, 8) }
            put(0, minOf(4, capacity - bits.size))
            while (bits.size % 8 != 0) bits.add(false)
            var pad = 0xec
            while (bits.size < capacity) {
                put(pad, 8)
                pad = pad xor (0xec xor 0x11)
            }
            return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or (if (bits[i * 8 + k]) 1 else 0) } }
        }

        // Each block gets its Reed-Solomon codewords, then the blocks are
        // read column by column, data first, error correction after.
        private fun interleave(data: IntArray, version: Int): IntArray {
            val ec = EC[version - 1]
            val blocks = ArrayList<IntArray>()
            var at = 0
            listOf(G1[version - 1], G2[version - 1]).forEach { (count, len) ->
                repeat(count) {
                    blocks.add(data.copyOfRange(at, at + len))
                    at += len
                }
            }
            val gen = generator(ec)
            val ecBlocks = blocks.map { remainder(it, gen) }
            val out = ArrayList<Int>()
            for (i in 0 until blocks.maxOf { it.size }) blocks.forEach { if (i < it.size) out.add(it[i]) }
            for (i in 0 until ec) ecBlocks.forEach { out.add(it[i]) }
            return out.toIntArray()
        }

        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                EXP[i] = x
                LOG[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor 0x11d
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

        // The product of (x - a^i) for i below degree, highest term first,
        // the leading 1 left out.
        private fun generator(degree: Int): IntArray {
            var poly = intArrayOf(1)
            for (i in 0 until degree) {
                val next = IntArray(poly.size + 1)
                for (j in poly.indices) {
                    next[j] = next[j] xor poly[j]
                    next[j + 1] = next[j + 1] xor mul(poly[j], EXP[i])
                }
                poly = next
            }
            return poly.copyOfRange(1, poly.size)
        }

        private fun remainder(data: IntArray, gen: IntArray): IntArray {
            val r = IntArray(gen.size)
            data.forEach { b ->
                val factor = b xor r[0]
                for (i in 0 until r.size - 1) r[i] = r[i + 1]
                r[r.size - 1] = 0
                for (i in gen.indices) r[i] = r[i] xor mul(gen[i], factor)
            }
            return r
        }

        // The eight masks are all drawn and scored, the lowest penalty wins.
        private fun best(version: Int, codewords: IntArray): QrCode {
            val size = version * 4 + 17
            var chosen: Array<BooleanArray>? = null
            var lowest = Int.MAX_VALUE
            for (mask in 0 until 8) {
                val grid = Array(size) { BooleanArray(size) }
                val function = Array(size) { BooleanArray(size) }
                drawFunction(version, size, grid, function)
                drawData(size, grid, function, codewords)
                applyMask(size, grid, function, mask)
                drawFormat(size, grid, function, mask)
                val p = penalty(size, grid)
                if (p < lowest) {
                    lowest = p
                    chosen = grid
                }
            }
            return QrCode(size, chosen ?: throw IllegalStateException("no mask"))
        }

        private fun set(grid: Array<BooleanArray>, function: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
            grid[y][x] = dark
            function[y][x] = true
        }

        private fun drawFunction(version: Int, size: Int, grid: Array<BooleanArray>, function: Array<BooleanArray>) {
            for (i in 0 until size) {
                set(grid, function, 6, i, i % 2 == 0)
                set(grid, function, i, 6, i % 2 == 0)
            }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        set(grid, function, x, y, d != 2 && d != 4)
                    }
                }
            }
            val align = ALIGN[version - 1]
            for (i in align.indices) for (j in align.indices) {
                val corner = (i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)
                if (corner) continue
                for (dy in -2..2) for (dx in -2..2) {
                    set(grid, function, align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
            // Reserved now, written once the mask is known.
            drawFormat(size, grid, function, 0)
            if (version >= 7) {
                var rem = version
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25)
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 == 1
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(grid, function, a, b, dark)
                    set(grid, function, b, a, dark)
                }
            }
        }

        // Level M is 00 in the two format bits.
        private fun drawFormat(size: Int, grid: Array<BooleanArray>, function: Array<BooleanArray>, mask: Int) {
            val data = mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(grid, function, 8, i, bit(i))
            set(grid, function, 8, 7, bit(6))
            set(grid, function, 8, 8, bit(7))
            set(grid, function, 7, 8, bit(8))
            for (i in 9 until 15) set(grid, function, 14 - i, 8, bit(i))
            for (i in 0 until 8) set(grid, function, size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(grid, function, 8, size - 15 + i, bit(i))
            set(grid, function, 8, size - 8, true)
        }

        // Two columns at a time from the right, up then down, skipping the
        // timing column.
        private fun drawData(size: Int, grid: Array<BooleanArray>, function: Array<BooleanArray>, codewords: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y][x] && i < codewords.size * 8) {
                            grid[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(size: Int, grid: Array<BooleanArray>, function: Array<BooleanArray>, mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) grid[y][x] = !grid[y][x]
            }
        }

        // The standard's four penalty rules: runs, 2x2 boxes, finder-like
        // patterns and the balance of dark modules.
        private fun penalty(size: Int, g: Array<BooleanArray>): Int {
            var total = 0
            for (pass in 0..1) {
                for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        val cur = if (pass == 0) g[a][b] else g[b][a]
                        val prev = if (pass == 0) g[a][b - 1] else g[b - 1][a]
                        if (cur == prev) {
                            run++
                        } else {
                            if (run >= 5) total += run - 2
                            run = 1
                        }
                    }
                    if (run >= 5) total += run - 2
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = g[y][x]
                if (c == g[y][x + 1] && c == g[y + 1][x] && c == g[y + 1][x + 1]) total += 3
            }
            val pattern = booleanArrayOf(true, false, true, true, true, false, true)
            for (pass in 0..1) for (a in 0 until size) for (b in 0..size - 7) {
                fun at(k: Int) = if (pass == 0) g[a][b + k] else g[b + k][a]
                if ((0 until 7).all { at(it) == pattern[it] }) {
                    val before = (1..4).all { b - it < 0 || !(if (pass == 0) g[a][b - it] else g[b - it][a]) }
                    val after = (7..10).all { b + it >= size || !at(it) }
                    if (before || after) total += 40
                }
            }
            val darkCount = g.sumOf { row -> row.count { it } }
            val cells = size * size
            val k = (kotlin.math.abs(darkCount * 20 - cells * 10) + cells - 1) / cells - 1
            total += maxOf(0, k) * 10
            return total
        }
    }
}
