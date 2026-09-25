package io.gutapk

import io.gutapk.device.Adb
import io.gutapk.device.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// adb's output as it prints it, text only: no device and no adb needed.
class AdbTest {

    // adb prints semicolons in two of these lines. Written as a char code to
    // keep the source free of that character.
    private val semi = Char(0x3b).toString()

    // Fix 2 of the 2026-09-24 review: the version mismatch line and the
    // daemon's start messages came before the header and read as devices.
    @Test
    fun readsOnlyTheLinesAfterTheHeader() {
        val out = listOf(
            "adb server version (40) doesn't match this client (41), killing...",
            "* daemon started successfully",
            "List of devices attached",
            "R58M123ABC             device usb:1-4 product:a52qnsxx model:SM_A525F device:a52q transport_id:3",
            "emulator-5554          unauthorized transport_id:1",
            "0123456789ABCDEF       no permissions (missing udev rules? user is in the plugdev group)" + semi +
                " see [http://developer.android.com/tools/device.html] usb:1-2 transport_id:4",
            "ZY22ABCD               offline transport_id:5",
            "",
        ).joinToString("\n")
        val devices = Adb.parseDevices(out)
        assertEquals(listOf("R58M123ABC", "emulator-5554", "0123456789ABCDEF", "ZY22ABCD"), devices.map { it.serial })
        assertEquals(
            listOf(DeviceState.READY, DeviceState.UNAUTHORIZED, DeviceState.NO_PERMISSION, DeviceState.OFFLINE),
            devices.map { it.state },
        )
        assertEquals("SM A525F", devices[0].model)
        assertNull(devices[1].model)
        assertEquals(emptyList(), Adb.parseDevices("* daemon not running" + semi + " starting now at tcp:5037\n"))
    }

    // The reply of the running Debian server on 2026-09-25, and adb version.
    @Test
    fun readsVersions() {
        assertEquals(41, Adb.parseVersionReply("OKAY00040029".toByteArray()))
        assertNull(Adb.parseVersionReply("FAIL0004nope".toByteArray()))
        assertNull(Adb.parseVersionReply("OKAY".toByteArray()))
        val version = "Android Debug Bridge version 1.0.41\nVersion 37.0.1-15733141\nInstalled as /usr/bin/adb\n"
        assertEquals(41 to "37.0.1-15733141", Adb.parseClientVersion(version))
        assertNull(Adb.parseClientVersion("nothing"))
    }

    @Test
    fun readsProps() {
        val props = Adb.parseProps("[ro.product.model]: [Pixel 8]\n[ro.build.version.release]: [16]\n[empty]: []\n")
        assertEquals("Pixel 8", props["ro.product.model"])
        assertEquals("16", props["ro.build.version.release"])
        assertEquals("", props["empty"])
    }
}
