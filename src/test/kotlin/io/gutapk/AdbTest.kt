package io.gutapk

import io.gutapk.device.Adb
import io.gutapk.device.BatteryStatus
import io.gutapk.device.DeviceApps
import io.gutapk.device.DeviceReader
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

    @Test
    fun readsBattery() {
        val out = listOf(
            "Current Battery Service state:",
            "  AC powered: false",
            "  USB powered: true",
            "  Wireless powered: false",
            "  status: 2",
            "  health: 2",
            "  present: true",
            "  level: 85",
            "  scale: 100",
            "  voltage: 4214",
            "  temperature: 285",
            "  technology: Li-ion",
        ).joinToString("\n")
        val b = DeviceReader.parseBattery(out)!!
        assertEquals(85, b.level)
        assertEquals(BatteryStatus.CHARGING, b.status)
        assertEquals(28.5, b.celsius)
        assertEquals("USB", b.plugged)
        assertNull(DeviceReader.parseBattery("Can't find service: battery"))
    }

    // A long block device name wraps the numbers onto the next line.
    @Test
    fun readsDiskSpace() {
        val one = "Filesystem       1K-blocks     Used Available Use% Mounted on\n/dev/block/dm-45 115609844 30000000  85609844  26% /data\n"
        val s = DeviceReader.parseDf(one)!!
        assertEquals(115609844L, s.totalKb)
        assertEquals(30000000L, s.usedKb)
        assertEquals(85609844L, s.freeKb)
        val wrapped = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/bootdevice/by-name/userdata\n 52000000 12000000 40000000 24% /data\n"
        assertEquals(40000000L, DeviceReader.parseDf(wrapped)!!.freeKb)
        assertNull(DeviceReader.parseDf("df: /data: Permission denied\n"))
    }

    @Test
    fun readsUsersAndRebootCommand() {
        val users = DeviceReader.parseUsers("Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work profile:1030}\n")
        assertEquals(listOf(0, 10), users.map { it.id })
        assertEquals(listOf("Owner", "Work profile"), users.map { it.name })
        assertEquals(listOf(true, false), users.map { it.running })
        assertEquals(listOf("-s", "X1", "reboot"), DeviceReader.rebootArgs("X1", null))
        assertEquals(listOf("-s", "X1", "reboot", "recovery"), DeviceReader.rebootArgs("X1", "recovery"))
    }

    // Fix 10 of the 2026-09-24 review: whatever reaches the device's shell
    // from outside GutapK is one quoted word.
    @Test
    fun quotesShellWords() {
        assertEquals("'com.example.app'", Adb.quote("com.example.app"))
        assertEquals("'a'\\''b'", Adb.quote("a'b"))
        assertEquals("'" + "$" + "(reboot)'", Adb.quote("$" + "(reboot)"))
    }

    @Test
    fun readsInstalledApps() {
        val out = listOf(
            "package:/data/app/~~Q2F0==/com.example.game-bG9n==/base.apk=com.example.game",
            "package:/product/app/Maps/Maps.apk=com.google.android.apps.maps",
            "package:/data/app/bad=path/base.apk=not a package",
            "WARNING: linker: something",
        ).joinToString("\n")
        val apps = DeviceApps.parseList(out, system = false)
        assertEquals(listOf("com.example.game", "com.google.android.apps.maps"), apps.map { it.packageName })
        assertEquals("/data/app/~~Q2F0==/com.example.game-bG9n==/base.apk", apps[0].path)
    }

    @Test
    fun readsAppDetails() {
        val dump = listOf(
            "Packages:",
            "  Package [com.example.game] (4f3a2b1):",
            "    versionCode=10109 minSdk=24 targetSdk=34",
            "    versionName=1.1.1.9",
            "    installerPackageName=com.android.vending",
            "    firstInstallTime=2026-07-18 19:34:22",
            "    lastUpdateTime=2026-09-01 08:00:00",
        ).joinToString("\n")
        val paths = DeviceApps.parsePaths("package:/data/app/x/base.apk\npackage:/data/app/x/split_config.arm64_v8a.apk\n")
        val d = DeviceApps.parseDetails(dump, paths)
        assertEquals("1.1.1.9", d.versionName)
        assertEquals("10109", d.versionCode)
        assertEquals("com.android.vending", d.installer)
        assertEquals("2026-07-18 19:34:22", d.firstInstall)
        assertEquals("2026-09-01 08:00:00", d.lastUpdate)
        assertEquals(listOf("/data/app/x/base.apk", "/data/app/x/split_config.arm64_v8a.apk"), d.apks)
        assertNull(DeviceApps.parseDetails("installerPackageName=null", emptyList()).installer)
    }

    // Whole 4096 byte blocks around the range, and where the range starts
    // in what dd returns.
    @Test
    fun readsRangesInWholeBlocks() {
        assertEquals("dd if='/a b.apk' bs=4096 skip=0 count=1 2>/dev/null" to 10, DeviceApps.ddCommand("'/a b.apk'", 10, 30))
        assertEquals("dd if='/x' bs=4096 skip=1 count=2 2>/dev/null" to 4000, DeviceApps.ddCommand("'/x'", 8096, 200))
        assertEquals("dd if='/x' bs=4096 skip=2 count=1 2>/dev/null" to 0, DeviceApps.ddCommand("'/x'", 8192, 4096))
    }
}
