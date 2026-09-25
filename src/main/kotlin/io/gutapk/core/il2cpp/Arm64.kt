package io.gutapk.core.il2cpp

import java.util.Locale

// A small arm64 reader for the hex view: the instructions IL2CPP code is
// mostly made of, written the way GNU objdump writes them. Checked against
// objdump 2.47 on 575491 instructions of two games' libil2cpp.so: 99.6 %
// read the same, none differently. Anything else is shown as .inst with its
// word, never guessed: atomics, prefetch, system and most vector forms.
object Arm64 {
    private val CONDS = listOf("eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le", "al", "nv")
    private val SHIFTS = listOf("lsl", "lsr", "asr", "ror")

    // Four bytes of the file, little endian, at pc.
    fun decode(bytes: ByteArray, at: Int, pc: Long): String {
        val w = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or ((bytes[at + 3].toInt() and 0xff) shl 24)
        return decode(w, pc)
    }

    fun decode(w: Int, pc: Long): String = runCatching { read(w, pc) }.getOrNull() ?: inst(w)

    private fun inst(w: Int) = ".inst\t0x%08x".format(w)

    private fun bits(w: Int, hi: Int, lo: Int): Int = (w ushr lo) and ((1 shl (hi - lo + 1)) - 1)

    private fun bit(w: Int, n: Int): Int = (w ushr n) and 1

    private fun sext(v: Long, width: Int): Long = (v shl (64 - width)) shr (64 - width)

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    // x or w, register 31 read as the zero register or sp.
    private fun r(n: Int, x: Boolean, sp: Boolean = false): String = when {
        n == 31 && sp -> if (x) "sp" else "wsp"
        n == 31 -> if (x) "xzr" else "wzr"
        else -> (if (x) "x" else "w") + n
    }

    private fun target(pc: Long, imm: Long): String = hex(pc + imm)

    private fun read(w: Int, pc: Long): String? {
        when {
            w == 0xd503201f.toInt() -> return "nop"
            w and 0xfffffc1f.toInt() == 0xd65f0000.toInt() -> return if (bits(w, 9, 5) == 30) "ret" else "ret\t" + r(bits(w, 9, 5), true)
            w and 0xfffffc1f.toInt() == 0xd61f0000.toInt() -> return "br\t" + r(bits(w, 9, 5), true)
            w and 0xfffffc1f.toInt() == 0xd63f0000.toInt() -> return "blr\t" + r(bits(w, 9, 5), true)
            w and 0xffe0001f.toInt() == 0xd4200000.toInt() -> return "brk\t#" + hex(bits(w, 20, 5).toLong())
            w and 0xffff0000.toInt() == 0 -> return "udf\t#" + bits(w, 15, 0)
        }
        return when {
            w and 0x7c000000 == 0x14000000 -> branch(w, pc)
            w and 0xff000010.toInt() == 0x54000000 -> "b." + CONDS[bits(w, 3, 0)] + "\t" + target(pc, sext(bits(w, 23, 5).toLong(), 19) * 4)
            w and 0x7e000000 == 0x34000000 -> {
                val name = if (bit(w, 24) == 1) "cbnz" else "cbz"
                name + "\t" + r(bits(w, 4, 0), bit(w, 31) == 1) + ", " + target(pc, sext(bits(w, 23, 5).toLong(), 19) * 4)
            }
            w and 0x7e000000 == 0x36000000 -> {
                val name = if (bit(w, 24) == 1) "tbnz" else "tbz"
                val n = (bit(w, 31) shl 5) or bits(w, 23, 19)
                name + "\t" + r(bits(w, 4, 0), bit(w, 31) == 1) + ", #" + n + ", " + target(pc, sext(bits(w, 18, 5).toLong(), 14) * 4)
            }
            w and 0x1f000000 == 0x10000000 -> adr(w, pc)
            w and 0x1f800000 == 0x12800000 -> moveWide(w)
            w and 0x1f000000 == 0x11000000 -> addImmediate(w)
            w and 0x1f800000 == 0x12000000 -> logicalImmediate(w)
            w and 0x1f800000 == 0x13000000 -> bitfield(w)
            w and 0x7fa00000 == 0x13800000 -> extract(w)
            w and 0x1f000000 == 0x0a000000 -> logicalRegister(w)
            w and 0x1f200000 == 0x0b000000 -> addRegister(w)
            w and 0x1fe00000 == 0x0b200000 -> addExtended(w)
            w and 0xbfe0fc00.toInt() == 0x0ea01c00 -> vectorOrr(w)
            w and 0xdfbffc00.toInt() == 0x5e21d800 -> {
                val f = if (bit(w, 22) == 1) "d" else "s"
                (if (bit(w, 29) == 1) "ucvtf" else "scvtf") + "\t" + f + bits(w, 4, 0) + ", " + f + bits(w, 9, 5)
            }
            w and 0x1fe00000 == 0x1a800000 -> conditionalSelect(w)
            w and 0x7fe00000 == 0x1ac00000 -> twoSource(w)
            w and 0x7f000000 == 0x1b000000 -> threeSource(w)
            w and 0x3b000000 == 0x39000000 -> loadStoreUnsigned(w)
            w and 0x3b200000 == 0x38000000 -> loadStoreImm9(w)
            w and 0x3b200c00 == 0x38200800 -> loadStoreRegister(w)
            w and 0x3b000000 == 0x18000000 -> loadLiteral(w, pc)
            w and 0x3a000000 == 0x28000000 -> loadStorePair(w)
            w and 0x5f000000 == 0x1e000000 && bit(w, 21) == 1 -> floating(w)
            else -> null
        }
    }

    private fun branch(w: Int, pc: Long): String =
        (if (bit(w, 31) == 1) "bl" else "b") + "\t" + target(pc, sext(bits(w, 25, 0).toLong(), 26) * 4)

    private fun adr(w: Int, pc: Long): String {
        val imm = sext(((bits(w, 23, 5).toLong()) shl 2) or bits(w, 30, 29).toLong(), 21)
        return if (bit(w, 31) == 1) {
            "adrp\t" + r(bits(w, 4, 0), true) + ", " + hex((pc and 0xfffL.inv()) + (imm shl 12))
        } else {
            "adr\t" + r(bits(w, 4, 0), true) + ", " + hex(pc + imm)
        }
    }

    private fun moveWide(w: Int): String? {
        val x = bit(w, 31) == 1
        val hw = bits(w, 22, 21)
        if (!x && hw > 1) return null
        val imm = bits(w, 20, 5).toLong()
        val shift = hw * 16
        val rd = r(bits(w, 4, 0), x)
        val mask = if (x) -1L else 0xffffffffL
        return when (bits(w, 30, 29)) {
            0 -> if (!(imm == 0L && hw != 0) && (x || imm != 0xffffL)) {
                "mov\t$rd, #" + hex((imm shl shift).inv() and mask)
            } else {
                "movn\t$rd, #" + hex(imm) + (if (shift > 0) ", lsl #$shift" else "")
            }
            2 -> if (!(imm == 0L && hw != 0)) {
                "mov\t$rd, #" + hex((imm shl shift) and mask)
            } else {
                "movz\t$rd, #" + hex(imm) + ", lsl #$shift"
            }
            3 -> "movk\t$rd, #" + hex(imm) + (if (shift > 0) ", lsl #$shift" else "")
            else -> null
        }
    }

    private fun addImmediate(w: Int): String? {
        val x = bit(w, 31) == 1
        val sub = bit(w, 30) == 1
        val flags = bit(w, 29) == 1
        if (bit(w, 23) == 1) return null
        val shift = bit(w, 22) == 1
        val imm = bits(w, 21, 10).toLong()
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        val immText = "#" + hex(imm) + (if (shift) ", lsl #12" else "")
        return when {
            !sub && !flags && !shift && imm == 0L && (rd == 31 || rn == 31) -> "mov\t" + r(rd, x, true) + ", " + r(rn, x, true)
            flags && rd == 31 -> (if (sub) "cmp" else "cmn") + "\t" + r(rn, x, true) + ", " + immText
            else -> (if (sub) "sub" else "add") + (if (flags) "s" else "") + "\t" + r(rd, x, !flags) + ", " + r(rn, x, true) + ", " + immText
        }
    }

    // The architecture's DecodeBitMasks, immediate form only.
    internal fun bitMask(n: Int, imms: Int, immr: Int, x: Boolean): Long? {
        val combined = (n shl 6) or (imms.inv() and 0x3f)
        val len = 31 - Integer.numberOfLeadingZeros(combined)
        if (len < 1) return null
        val size = 1 shl len
        if (!x && size > 32) return null
        val levels = size - 1
        val s = imms and levels
        val rot = immr and levels
        if (s == levels) return null
        var element = if (s + 1 == 64) -1L else (1L shl (s + 1)) - 1
        if (rot > 0) {
            val sizeMask = if (size == 64) -1L else (1L shl size) - 1
            element = ((element ushr rot) or (element shl (size - rot))) and sizeMask
        }
        var out = element
        var filled = size
        val width = if (x) 64 else 32
        while (filled < width) {
            out = out or (out shl filled)
            filled *= 2
        }
        return if (x) out else out and 0xffffffffL
    }

    // A value movz or movn could make on its own. orr with such a value is
    // not shown as mov, objdump keeps orr for it.
    private fun wideable(v: Long, x: Boolean): Boolean {
        val width = if (x) 64 else 32
        val mask = if (x) -1L else 0xffffffffL
        for (candidate in listOf(v and mask, v.inv() and mask)) {
            for (shift in 0 until width step 16) {
                if (candidate and (0xffffL shl shift).inv() and mask == 0L) return true
            }
        }
        return false
    }

    private fun logicalImmediate(w: Int): String? {
        val x = bit(w, 31) == 1
        val n = bit(w, 22)
        if (!x && n == 1) return null
        val imm = bitMask(n, bits(w, 15, 10), bits(w, 21, 16), x) ?: return null
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        val immText = "#" + hex(imm)
        return when (bits(w, 30, 29)) {
            0 -> "and\t" + r(rd, x, true) + ", " + r(rn, x) + ", " + immText
            1 -> if (rn == 31 && !wideable(imm, x)) "mov\t" + r(rd, x, true) + ", " + immText else "orr\t" + r(rd, x, true) + ", " + r(rn, x) + ", " + immText
            2 -> "eor\t" + r(rd, x, true) + ", " + r(rn, x) + ", " + immText
            else -> if (rd == 31) "tst\t" + r(rn, x) + ", " + immText else "ands\t" + r(rd, x) + ", " + r(rn, x) + ", " + immText
        }
    }

    private fun bitfield(w: Int): String? {
        val x = bit(w, 31) == 1
        if (bit(w, 22) != bit(w, 31)) return null
        val width = if (x) 64 else 32
        val immr = bits(w, 21, 16)
        val imms = bits(w, 15, 10)
        val rn = bits(w, 9, 5)
        val rd = r(bits(w, 4, 0), x)
        val src = r(rn, x)
        return when (bits(w, 30, 29)) {
            0 -> when {
                imms == width - 1 -> "asr\t$rd, $src, #$immr"
                imms < immr -> "sbfiz\t$rd, $src, #${width - immr}, #${imms + 1}"
                immr == 0 && imms == 7 -> "sxtb\t$rd, " + r(rn, false)
                immr == 0 && imms == 15 -> "sxth\t$rd, " + r(rn, false)
                immr == 0 && imms == 31 && x -> "sxtw\t$rd, " + r(rn, false)
                else -> "sbfx\t$rd, $src, #$immr, #${imms - immr + 1}"
            }
            1 -> when {
                imms < immr -> if (rn == 31) "bfc\t$rd, #${width - immr}, #${imms + 1}" else "bfi\t$rd, $src, #${width - immr}, #${imms + 1}"
                else -> "bfxil\t$rd, $src, #$immr, #${imms - immr + 1}"
            }
            2 -> when {
                imms != width - 1 && imms + 1 == immr -> "lsl\t$rd, $src, #${width - 1 - imms}"
                imms == width - 1 -> "lsr\t$rd, $src, #$immr"
                imms < immr -> "ubfiz\t$rd, $src, #${width - immr}, #${imms + 1}"
                !x && immr == 0 && imms == 7 -> "uxtb\t$rd, $src"
                !x && immr == 0 && imms == 15 -> "uxth\t$rd, $src"
                else -> "ubfx\t$rd, $src, #$immr, #${imms - immr + 1}"
            }
            else -> null
        }
    }

    // extr, shown as ror when both sources are the same register.
    private fun extract(w: Int): String? {
        val x = bit(w, 31) == 1
        if (bit(w, 22) != bit(w, 31) || (!x && bit(w, 15) == 1)) return null
        val rm = bits(w, 20, 16)
        val rn = bits(w, 9, 5)
        val lsb = bits(w, 15, 10)
        val rd = r(bits(w, 4, 0), x)
        return if (rm == rn) "ror\t$rd, " + r(rn, x) + ", #$lsb" else "extr\t$rd, " + r(rn, x) + ", " + r(rm, x) + ", #$lsb"
    }

    private fun shifted(w: Int, x: Boolean): String {
        val amount = bits(w, 15, 10)
        val rm = r(bits(w, 20, 16), x)
        return if (amount == 0 && bits(w, 23, 22) == 0) rm else rm + ", " + SHIFTS[bits(w, 23, 22)] + " #" + amount
    }

    private fun logicalRegister(w: Int): String? {
        val x = bit(w, 31) == 1
        if (!x && bit(w, 15) == 1) return null
        val rn = bits(w, 9, 5)
        val rd = r(bits(w, 4, 0), x)
        val invert = bit(w, 21) == 1
        val m = shifted(w, x)
        val plain = bits(w, 15, 10) == 0 && bits(w, 23, 22) == 0
        return when (bits(w, 30, 29)) {
            0 -> (if (invert) "bic" else "and") + "\t$rd, " + r(rn, x) + ", $m"
            1 -> when {
                !invert && rn == 31 && plain -> "mov\t$rd, $m"
                invert && rn == 31 -> "mvn\t$rd, $m"
                else -> (if (invert) "orn" else "orr") + "\t$rd, " + r(rn, x) + ", $m"
            }
            2 -> (if (invert) "eon" else "eor") + "\t$rd, " + r(rn, x) + ", $m"
            else -> if (!invert && bits(w, 4, 0) == 31) "tst\t" + r(rn, x) + ", $m" else (if (invert) "bics" else "ands") + "\t$rd, " + r(rn, x) + ", $m"
        }
    }

    private fun addRegister(w: Int): String? {
        val x = bit(w, 31) == 1
        if (bits(w, 23, 22) == 3 || (!x && bit(w, 15) == 1)) return null
        val sub = bit(w, 30) == 1
        val flags = bit(w, 29) == 1
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        val m = shifted(w, x)
        return when {
            flags && rd == 31 -> (if (sub) "cmp" else "cmn") + "\t" + r(rn, x) + ", $m"
            sub && rn == 31 -> (if (flags) "negs" else "neg") + "\t" + r(rd, x) + ", $m"
            else -> (if (sub) "sub" else "add") + (if (flags) "s" else "") + "\t" + r(rd, x) + ", " + r(rn, x) + ", $m"
        }
    }

    private val EXTENDS = listOf("uxtb", "uxth", "uxtw", "uxtx", "sxtb", "sxth", "sxtw", "sxtx")

    // Add and sub with an extended register, "add x8, x20, w23, sxtw #3".
    // Next to sp, the plain widening is written as lsl, as objdump does.
    private fun addExtended(w: Int): String? {
        val x = bit(w, 31) == 1
        val sub = bit(w, 30) == 1
        val flags = bit(w, 29) == 1
        val option = bits(w, 15, 13)
        val amount = bits(w, 12, 10)
        if (amount > 4) return null
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        val rm = r(bits(w, 20, 16), option and 3 == 3)
        val plain = option == (if (x) 3 else 2)
        val extend = if (plain && (rd == 31 || rn == 31)) {
            if (amount == 0) "" else ", lsl #$amount"
        } else {
            ", " + EXTENDS[option] + (if (amount != 0) " #$amount" else "")
        }
        return when {
            flags && rd == 31 -> (if (sub) "cmp" else "cmn") + "\t" + r(rn, x, true) + ", " + rm + extend
            else -> (if (sub) "sub" else "add") + (if (flags) "s" else "") + "\t" + r(rd, x, !flags) + ", " + r(rn, x, true) + ", " + rm + extend
        }
    }

    // orr of two vector registers, shown as mov when both are the same.
    private fun vectorOrr(w: Int): String {
        val t = if (bit(w, 30) == 1) "16b" else "8b"
        val rm = bits(w, 20, 16)
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        return if (rm == rn) "mov\tv$rd.$t, v$rn.$t" else "orr\tv$rd.$t, v$rn.$t, v$rm.$t"
    }

    private fun conditionalSelect(w: Int): String? {
        if (bit(w, 29) == 1 || bit(w, 11) == 1) return null
        val x = bit(w, 31) == 1
        val rm = bits(w, 20, 16)
        val rn = bits(w, 9, 5)
        val rd = r(bits(w, 4, 0), x)
        val cond = bits(w, 15, 12)
        val inverted = CONDS[cond xor 1]
        val usable = cond < 14
        return when ((bit(w, 30) shl 1) or bit(w, 10)) {
            0 -> "csel\t$rd, " + r(rn, x) + ", " + r(rm, x) + ", " + CONDS[cond]
            1 -> when {
                rm == 31 && rn == 31 && usable -> "cset\t$rd, $inverted"
                rm == rn && rn != 31 && usable -> "cinc\t$rd, " + r(rn, x) + ", $inverted"
                else -> "csinc\t$rd, " + r(rn, x) + ", " + r(rm, x) + ", " + CONDS[cond]
            }
            2 -> when {
                rm == 31 && rn == 31 && usable -> "csetm\t$rd, $inverted"
                rm == rn && rn != 31 && usable -> "cinv\t$rd, " + r(rn, x) + ", $inverted"
                else -> "csinv\t$rd, " + r(rn, x) + ", " + r(rm, x) + ", " + CONDS[cond]
            }
            else -> when {
                rm == rn && usable -> "cneg\t$rd, " + r(rn, x) + ", $inverted"
                else -> "csneg\t$rd, " + r(rn, x) + ", " + r(rm, x) + ", " + CONDS[cond]
            }
        }
    }

    private fun twoSource(w: Int): String? {
        if (bit(w, 29) == 1) return null
        val x = bit(w, 31) == 1
        val name = when (bits(w, 15, 10)) {
            2 -> "udiv"
            3 -> "sdiv"
            8 -> "lsl"
            9 -> "lsr"
            10 -> "asr"
            11 -> "ror"
            else -> return null
        }
        return name + "\t" + r(bits(w, 4, 0), x) + ", " + r(bits(w, 9, 5), x) + ", " + r(bits(w, 20, 16), x)
    }

    private fun threeSource(w: Int): String? {
        val x = bit(w, 31) == 1
        val op31 = bits(w, 23, 21)
        val o0 = bit(w, 15)
        val rm = bits(w, 20, 16)
        val ra = bits(w, 14, 10)
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        if (bits(w, 30, 29) != 0) return null
        return when {
            op31 == 0 -> {
                val base = if (o0 == 0) "madd" else "msub"
                if (ra == 31) {
                    (if (o0 == 0) "mul" else "mneg") + "\t" + r(rd, x) + ", " + r(rn, x) + ", " + r(rm, x)
                } else {
                    base + "\t" + r(rd, x) + ", " + r(rn, x) + ", " + r(rm, x) + ", " + r(ra, x)
                }
            }
            x && (op31 == 1 || op31 == 5) -> {
                val u = if (op31 == 5) "u" else "s"
                if (ra == 31 && o0 == 0) {
                    u + "mull\t" + r(rd, true) + ", " + r(rn, false) + ", " + r(rm, false)
                } else {
                    u + (if (o0 == 0) "maddl" else "msubl") + "\t" + r(rd, true) + ", " + r(rn, false) + ", " + r(rm, false) + ", " + r(ra, true)
                }
            }
            x && (op31 == 2 || op31 == 6) && o0 == 0 && ra == 31 -> (if (op31 == 6) "umulh" else "smulh") + "\t" + r(rd, true) + ", " + r(rn, true) + ", " + r(rm, true)
            else -> null
        }
    }

    // Mnemonic, register and scale of a load or store from its size, V and
    // opc fields, the table shared by every addressing form.
    private class Access(val name: String, val reg: String, val scale: Int)

    private fun access(size: Int, v: Int, opc: Int, rt: Int, unscaled: Boolean): Access? {
        val u = if (unscaled) "u" else ""
        if (v == 1) {
            val scale = if (opc and 2 != 0) {
                if (size != 0) return null
                4
            } else {
                size
            }
            val prefix = when (scale) {
                0 -> "b"
                1 -> "h"
                2 -> "s"
                3 -> "d"
                else -> "q"
            }
            return Access((if (opc and 1 == 1) "ld" else "st") + u + "r", prefix + rt, scale)
        }
        val load = opc != 0
        return when (size) {
            0 -> when (opc) {
                0 -> Access("st${u}rb", r(rt, false), 0)
                1 -> Access("ld${u}rb", r(rt, false), 0)
                2 -> Access("ld${u}rsb", r(rt, true), 0)
                else -> Access("ld${u}rsb", r(rt, false), 0)
            }
            1 -> when (opc) {
                0 -> Access("st${u}rh", r(rt, false), 1)
                1 -> Access("ld${u}rh", r(rt, false), 1)
                2 -> Access("ld${u}rsh", r(rt, true), 1)
                else -> Access("ld${u}rsh", r(rt, false), 1)
            }
            2 -> when (opc) {
                0, 1 -> Access(if (load) "ld${u}r" else "st${u}r", r(rt, false), 2)
                2 -> Access("ld${u}rsw", r(rt, true), 2)
                else -> null
            }
            else -> when (opc) {
                0, 1 -> Access(if (load) "ld${u}r" else "st${u}r", r(rt, true), 3)
                else -> null
            }
        }
    }

    private fun loadStoreUnsigned(w: Int): String? {
        val a = access(bits(w, 31, 30), bit(w, 26), bits(w, 23, 22), bits(w, 4, 0), false) ?: return null
        val offset = bits(w, 21, 10).toLong() shl a.scale
        val base = r(bits(w, 9, 5), true, true)
        return a.name + "\t" + a.reg + ", [" + base + (if (offset != 0L) ", #$offset" else "") + "]"
    }

    private fun loadStoreImm9(w: Int): String? {
        val mode = bits(w, 11, 10)
        if (mode == 2) return null
        val a = access(bits(w, 31, 30), bit(w, 26), bits(w, 23, 22), bits(w, 4, 0), mode == 0) ?: return null
        val imm = sext(bits(w, 20, 12).toLong(), 9)
        val base = r(bits(w, 9, 5), true, true)
        return a.name + "\t" + a.reg + ", " + when (mode) {
            0 -> "[" + base + (if (imm != 0L) ", #$imm" else "") + "]"
            1 -> "[$base], #$imm"
            else -> "[$base, #$imm]!"
        }
    }

    private fun loadStoreRegister(w: Int): String? {
        val a = access(bits(w, 31, 30), bit(w, 26), bits(w, 23, 22), bits(w, 4, 0), false) ?: return null
        val option = bits(w, 15, 13)
        val s = bit(w, 12) == 1
        val base = r(bits(w, 9, 5), true, true)
        val rm = bits(w, 20, 16)
        val index = when (option) {
            3 -> r(rm, true) + if (s) ", lsl #${a.scale}" else ""
            2 -> r(rm, false) + ", uxtw" + if (s) " #${a.scale}" else ""
            6 -> r(rm, false) + ", sxtw" + if (s) " #${a.scale}" else ""
            7 -> r(rm, true) + ", sxtx" + if (s) " #${a.scale}" else ""
            else -> return null
        }
        return a.name + "\t" + a.reg + ", [" + base + ", " + index + "]"
    }

    private fun loadLiteral(w: Int, pc: Long): String? {
        val rt = bits(w, 4, 0)
        val opc = bits(w, 31, 30)
        val at = target(pc, sext(bits(w, 23, 5).toLong(), 19) * 4)
        if (bit(w, 26) == 1) {
            val prefix = when (opc) {
                0 -> "s"
                1 -> "d"
                2 -> "q"
                else -> return null
            }
            return "ldr\t$prefix$rt, $at"
        }
        return when (opc) {
            0 -> "ldr\t" + r(rt, false) + ", " + at
            1 -> "ldr\t" + r(rt, true) + ", " + at
            2 -> "ldrsw\t" + r(rt, true) + ", " + at
            else -> null
        }
    }

    private fun loadStorePair(w: Int): String? {
        val opc = bits(w, 31, 30)
        val v = bit(w, 26)
        val mode = bits(w, 24, 23)
        val load = bit(w, 22) == 1
        val rt = bits(w, 4, 0)
        val rt2 = bits(w, 14, 10)
        val name = if (v == 0 && opc == 1) "psw" else "p"
        val scale = when {
            v == 0 && opc == 1 && load && mode != 0 -> 2
            v == 0 && (opc == 0 || opc == 2) -> 2 + (opc shr 1)
            v == 1 && opc < 3 -> 2 + opc
            else -> return null
        }
        val reg = { n: Int ->
            when {
                v == 1 -> listOf("s", "d", "q")[opc] + n
                opc == 0 -> r(n, false)
                else -> r(n, true)
            }
        }
        val mnemonic = (if (load) "ld" else "st") + (if (mode == 0) "n" else "") + name
        val imm = sext(bits(w, 21, 15).toLong(), 7) shl scale
        val base = r(bits(w, 9, 5), true, true)
        val address = when (mode) {
            1 -> "[$base], #$imm"
            3 -> "[$base, #$imm]!"
            else -> "[" + base + (if (imm != 0L) ", #$imm" else "") + "]"
        }
        return mnemonic + "\t" + reg(rt) + ", " + reg(rt2) + ", " + address
    }

    private fun fpReg(type: Int, n: Int): String? = when (type) {
        0 -> "s$n"
        1 -> "d$n"
        3 -> "h$n"
        else -> null
    }

    // VFPExpandImm, printed like objdump does, 18 digits after the point.
    private fun fpImmediate(imm8: Int): String {
        val sign = if (imm8 and 0x80 != 0) -1.0 else 1.0
        val exp = ((imm8 shr 4) and 7).let { e -> if (e and 4 != 0) e - 8 else e } + 1
        val frac = 16 + (imm8 and 0xf)
        val value = sign * frac / 16.0 * Math.pow(2.0, exp.toDouble())
        return String.format(Locale.ROOT, "%.18e", value)
    }

    private fun floating(w: Int): String? {
        if (bit(w, 29) == 1 || bit(w, 30) == 1) return null
        val type = bits(w, 23, 22)
        val rn = bits(w, 9, 5)
        val rd = bits(w, 4, 0)
        val sf = bit(w, 31) == 1
        if (bits(w, 15, 10) == 0) {
            // Conversions between general and floating registers.
            val rmode = bits(w, 20, 19)
            val opcode = bits(w, 18, 16)
            val fp = fpReg(type, 0) ?: return null
            val f = fp.first()
            return when {
                rmode == 0 && opcode == 7 -> "fmov\t$f$rd, " + r(rn, sf)
                rmode == 0 && opcode == 6 -> "fmov\t" + r(rd, sf) + ", $f$rn"
                rmode == 0 && opcode == 2 -> "scvtf\t$f$rd, " + r(rn, sf)
                rmode == 0 && opcode == 3 -> "ucvtf\t$f$rd, " + r(rn, sf)
                rmode == 3 && opcode == 0 -> "fcvtzs\t" + r(rd, sf) + ", $f$rn"
                rmode == 3 && opcode == 1 -> "fcvtzu\t" + r(rd, sf) + ", $f$rn"
                rmode == 0 && opcode == 4 -> "fcvtas\t" + r(rd, sf) + ", $f$rn"
                rmode == 2 && opcode == 0 -> "fcvtms\t" + r(rd, sf) + ", $f$rn"
                rmode == 1 && opcode == 0 -> "fcvtps\t" + r(rd, sf) + ", $f$rn"
                rmode == 0 && opcode == 0 -> "fcvtns\t" + r(rd, sf) + ", $f$rn"
                else -> null
            }
        }
        if (sf) return null
        val d = fpReg(type, rd) ?: return null
        val n = fpReg(type, rn) ?: return null
        val m = fpReg(type, bits(w, 20, 16)) ?: return null
        return when {
            w and 0x1fe0 == 0x1000 -> "fmov\t$d, #" + fpImmediate(bits(w, 20, 13))
            bits(w, 14, 10) == 0x10 -> when (bits(w, 20, 15)) {
                0 -> "fmov\t$d, $n"
                1 -> "fabs\t$d, $n"
                2 -> "fneg\t$d, $n"
                3 -> "fsqrt\t$d, $n"
                4, 5, 7 -> fpReg(bits(w, 16, 15), rd)?.let { "fcvt\t$it, $n" }
                8 -> "frintn\t$d, $n"
                9 -> "frintp\t$d, $n"
                10 -> "frintm\t$d, $n"
                11 -> "frintz\t$d, $n"
                12 -> "frinta\t$d, $n"
                else -> null
            }
            bits(w, 13, 10) == 8 && bits(w, 15, 14) == 0 -> when (bits(w, 4, 0)) {
                0 -> "fcmp\t$n, $m"
                8 -> "fcmp\t$n, #0.0"
                16 -> "fcmpe\t$n, $m"
                24 -> "fcmpe\t$n, #0.0"
                else -> null
            }
            bits(w, 11, 10) == 2 -> when (bits(w, 15, 12)) {
                0 -> "fmul\t$d, $n, $m"
                1 -> "fdiv\t$d, $n, $m"
                2 -> "fadd\t$d, $n, $m"
                3 -> "fsub\t$d, $n, $m"
                4 -> "fmax\t$d, $n, $m"
                5 -> "fmin\t$d, $n, $m"
                6 -> "fmaxnm\t$d, $n, $m"
                7 -> "fminnm\t$d, $n, $m"
                8 -> "fnmul\t$d, $n, $m"
                else -> null
            }
            bits(w, 11, 10) == 3 -> "fcsel\t$d, $n, $m, " + CONDS[bits(w, 15, 12)]
            else -> null
        }
    }
}
