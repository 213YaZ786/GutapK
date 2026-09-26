package io.gutapk

import io.gutapk.core.QrCode
import io.gutapk.device.Wireless
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The two matrices below were read back by the zxing-cpp decoder on
// 2026-09-26, with 48 others covering versions 1 to 10. Their prints keep
// the encoder from drifting.
class QrTest {
    private fun print(q: QrCode): String {
        val rows = (0 until q.size).joinToString("\n") { y -> (0 until q.size).joinToString("") { x -> if (q.isDark(x, y)) "1" else "0" } }
        return MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun matchesDecodedMatrices() {
        val hello = QrCode.encode("hello")
        assertEquals(21, hello.size)
        assertEquals("683921f56b2988dd454e5f08cc85d949682bd846d510fe13aa028a95d1427918", print(hello))
        val s = Char(0x3B)
        val pairing = QrCode.encode("WIFI:T:ADB" + s + "S:gutapk-7F3A" + s + "P:k2P9xQ4mT7" + s + s)
        assertEquals(29, pairing.size)
        assertEquals("70734b3aee5c906983d6bd32a3a4eb598280b62bd5f9324be1833288b912de65", print(pairing))
        assertFailsWith<IllegalArgumentException> { QrCode.encode("x".repeat(300)) }
    }

    @Test
    fun pairingTextAndCommand() {
        val p = Wireless.newQrPairing()
        val s = Char(0x3B)
        assertTrue(Regex("WIFI:T:ADB" + s + "S:gutapk-[A-Za-z0-9]{6}" + s + "P:[A-Za-z0-9]{12}" + s + s).matches(p.text))
        assertEquals(listOf("pair", "192.168.1.23:37123", p.password), Wireless.pairQr("192.168.1.23:37123", p.password))
        assertFailsWith<IllegalArgumentException> { Wireless.pairQr("192.168.1.23:37123", "a b rm") }
    }
}
