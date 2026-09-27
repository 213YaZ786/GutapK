package io.gutapk.core.il2cpp

// What a patched method returns, by the register its type comes back in:
// w0 for bool and int, x0 for long, s0 for float, d0 for double.
enum class ReturnKind { BOOL, INT, LONG, FLOAT, DOUBLE }

// A value turned into arm64 instructions that load it and return, so the
// user types 500 instead of looking up an encoding. Words are little endian
// in the library. A write to w0 clears the top of x0, so mov w0 and mov x0
// give the same result for a bool or an int.
object Arm64Return {
    const val RET = 0xD65F03C0.toInt()
    private const val FMOV_S0_W0 = 0x1E270000
    private const val FMOV_D0_X0 = 0x9E670000.toInt()

    class Built(val value: String, val hex: String, val words: List<Int>) {
        val bytes: ByteArray get() = ByteArray(words.size * 4) { i -> (words[i / 4] ushr (8 * (i % 4))).toByte() }
    }

    // null when the text is not a value of that kind.
    fun build(kind: ReturnKind, text: String): Built? {
        val t = text.trim()
        return when (kind) {
            ReturnKind.BOOL -> when (t.lowercase()) {
                "true", "1" -> Built("true", "0x1", load(1, wide = false) + RET)
                "false", "0" -> Built("false", "0x0", load(0, wide = false) + RET)
                else -> null
            }
            ReturnKind.INT -> integer(t, 32)?.let { v -> Built(v.toInt().toString(), hex(v, 32), load(v, wide = false) + RET) }
            ReturnKind.LONG -> integer(t, 64)?.let { v -> Built(v.toString(), hex(v, 64), load(v, wide = true) + RET) }
            ReturnKind.FLOAT -> t.toFloatOrNull()?.takeIf { it.isFinite() }?.let { f ->
                val bits = java.lang.Float.floatToRawIntBits(f).toLong() and 0xffffffffL
                Built(f.toString(), hex(bits, 32), load(bits, wide = false) + FMOV_S0_W0 + RET)
            }
            ReturnKind.DOUBLE -> t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { d ->
                val bits = java.lang.Double.doubleToRawLongBits(d)
                Built(d.toString(), hex(bits, 64), load(bits, wide = true) + FMOV_D0_X0 + RET)
            }
        }
    }

    // Decimal or 0x hex, signed, or unsigned up to the width.
    internal fun integer(t: String, width: Int): Long? {
        val negative = t.startsWith("-")
        val body = t.removePrefix("-").replace("_", "")
        val magnitude = if (body.startsWith("0x") || body.startsWith("0X")) body.drop(2).toULongOrNull(16) else body.toULongOrNull()
        magnitude ?: return null
        val max = if (width == 64) ULong.MAX_VALUE else 0xffffffffUL
        return if (negative) {
            val limit = if (width == 64) 1UL shl 63 else 1UL shl 31
            if (magnitude > limit) null else (-(magnitude.toLong()))
        } else {
            if (magnitude > max) return null
            if (width == 32) magnitude.toLong().toInt().toLong() else magnitude.toLong()
        }
    }

    private fun hex(v: Long, width: Int): String =
        "0x" + (if (width == 32) (v and 0xffffffffL).toString(16) else java.lang.Long.toHexString(v)).uppercase()

    // The shortest movz/movk or movn/movk run that loads v into w0 or x0.
    internal fun load(v: Long, wide: Boolean): List<Int> {
        val parts = if (wide) 4 else 2
        val halves = (0 until parts).map { ((v ushr (16 * it)) and 0xffff).toInt() }
        val fromZero = halves.indices.filter { halves[it] != 0 }
        val fromOnes = halves.indices.filter { halves[it] != 0xffff }
        val sf = if (wide) 1 else 0
        fun wideMove(opc: Int, hw: Int, imm: Int): Int = (sf shl 31) or (opc shl 29) or (0b100101 shl 23) or (hw shl 21) or (imm shl 5)
        return if (fromOnes.size < fromZero.size) {
            if (fromOnes.isEmpty()) {
                listOf(wideMove(0b00, 0, 0))
            } else {
                val first = fromOnes.first()
                listOf(wideMove(0b00, first, halves[first].inv() and 0xffff)) + fromOnes.drop(1).map { wideMove(0b11, it, halves[it]) }
            }
        } else {
            if (fromZero.isEmpty()) {
                listOf(wideMove(0b10, 0, 0))
            } else {
                val first = fromZero.first()
                listOf(wideMove(0b10, first, halves[first])) + fromZero.drop(1).map { wideMove(0b11, it, halves[it]) }
            }
        }
    }
}
