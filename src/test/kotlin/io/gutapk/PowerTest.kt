package io.gutapk

import io.gutapk.device.BatteryStatus
import io.gutapk.device.Checkin
import io.gutapk.device.Power
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The battery commands' output as an API 36 emulator printed it, text only:
// no device and no adb needed. Two checkins 30 s apart, one usage report.
// The protocol is Battery Historian's: checkin lines in, parsed values out,
// as in its parseutils tests.
// https://github.com/google/battery-historian/blob/master/parseutils/parseutils_test.go
class PowerTest {

    private fun res(name: String): String =
        this.javaClass.getResource("/io/gutapk/power/$name")!!.readText()

    private fun checkinA(): Checkin = Power.parseCheckin(res("checkin_a.txt"))

    private fun checkinB(): Checkin = Power.parseCheckin(res("checkin_b.txt"))

    @Test
    fun sysfsCommandIsValidShell() {
        val p = ProcessBuilder("sh", "-n", "-c", Power.SYSFS).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun readsTheBatterySysfs() {
        val r = assertNotNull(Power.parseSysfs(res("sysfs.txt")))
        assertEquals(BatteryStatus.NOT_CHARGING, r.status)
        assertEquals(100, r.level)
        assertEquals(900.0, r.milliAmps!!, 1e-9)
        assertEquals(5.0, r.volts!!, 1e-9)
        assertEquals(4.5, r.watts!!, 1e-9)
        assertEquals(25.0, r.celsius!!, 1e-9)
        assertEquals(10000L, r.chargeCounterUah)
    }

    @Test
    fun currentSignIsDroppedAndAnEmptyFileStaysNull() {
        val out = Power.parseSysfs("status=Discharging\ncapacity=80\ncurrent_now=-1200000\nvoltage_now=4000000\ntemp=\ncharge_counter=")
        assertEquals(BatteryStatus.DISCHARGING, out!!.status)
        assertEquals(1200.0, out.milliAmps!!, 1e-9)
        assertEquals(4.8, out.watts!!, 1e-9)
        assertNull(out.celsius)
        assertNull(out.chargeCounterUah)
        val refused = Power.parseSysfs("status=Charging\ncapacity=50\ncurrent_now=\nvoltage_now=")
        assertNull(refused!!.microAmps)
        assertNull(refused.watts)
        assertEquals(50, refused.level)
    }

    @Test
    fun fallsBackToDumpsysBattery() {
        val b = assertNotNull(Power.parseDumpsysBattery(res("battery.txt")))
        assertEquals(BatteryStatus.NOT_CHARGING, b.reading.status)
        assertEquals(100, b.reading.level)
        assertEquals(5.0, b.reading.volts!!, 1e-9)
        assertEquals(25.0, b.reading.celsius!!, 1e-9)
        assertEquals(10000L, b.reading.chargeCounterUah)
        assertNull(b.reading.microAmps)
        assertEquals(false, b.powered)
        val plugged = Power.parseDumpsysBattery(res("battery.txt").replace("USB powered: false", "USB powered: true"))
        assertEquals(true, plugged!!.powered)
        val wireless = Power.parseDumpsysBattery(res("battery.txt").replace("Wireless powered: false", "Wireless powered: true"))
        assertEquals(true, wireless!!.powered)
    }

    @Test
    fun meanCurrentFromTheChargeCounter() {
        // 3000 microamp hours gone in one hour is 3 mA.
        assertEquals(3.0, Power.averageMilliAmps(0, 100_000, 3_600_000, 97_000)!!, 1e-9)
        // Charging counts up, the size is the same.
        assertEquals(3.0, Power.averageMilliAmps(0, 97_000, 3_600_000, 100_000)!!, 1e-9)
        assertNull(Power.averageMilliAmps(5, 1, 5, 2))
        assertNull(Power.averageMilliAmps(9, 1, 5, 2))
    }

    @Test
    fun readsTheCheckinSections() {
        val c = checkinA()
        assertEquals(3000.0, c.capacityMah!!, 1e-9)
        // pwi: a per app row, and the root uid, while the labelled device rows are not uids.
        val gms = c.uids.getValue(10155)
        assertEquals(0.630, gms.mah, 1e-9)
        assertTrue(gms.system)
        assertEquals(0.0317, c.uids.getValue(0).mah, 1e-9)
        assertEquals(0.881, c.uids.getValue(1000).mah, 1e-9)
        assertEquals(false, c.uids.getValue(10153).system)
        // cpu: user plus system
        assertEquals(23865L + 6894L, gms.cpuMs)
        assertEquals(173193L + 165210L, c.uids.getValue(1000).cpuMs)
        // nt: the four first columns
        assertEquals(636470L + 265966L + 25879396L + 1698684L, gms.netBytes)
        // jb: total ms and count of the nine jobs
        val photos = c.uids.getValue(10170)
        assertEquals(23105L, photos.jobMs)
        assertEquals(36L, photos.jobCount)
        // wl: this phone holds no partial wakelock, wua: its alarms never fired
        assertEquals(0L, gms.wakeMs)
        assertEquals(0L, gms.wakeCount)
        assertEquals(0L, gms.alarms)
        // uid: one package, then eleven packages sharing the system uid
        assertEquals(listOf("com.google.android.gms"), c.packages.getValue(10155))
        assertEquals(11, c.packages.getValue(1000).size)
        assertEquals("com.google.android.gms", Power.title(10155, c.packagesOf(10155)))
        assertEquals("com.android.dynsystem +10", Power.title(1000, c.packagesOf(1000)))
        assertEquals("uid 99999", Power.title(99999, c.packagesOf(99999)))
        // another Android user shares the appId's packages
        assertEquals(c.packagesOf(10155), c.packagesOf(1010155))
    }

    // A uid the phone named no package for takes its AID name, the way ps
    // prints it, in any Android user. Cases follow the header's own table.
    // https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcutils/include/private/android_filesystem_config.h
    @Test
    fun namesSystemUidsWhenNoPackage() {
        val none = emptyList<String>()
        assertEquals("root", Power.title(0, none))
        assertEquals("logd", Power.title(1036, none))
        assertEquals("logd", Power.title(1001036, none))
        assertEquals("nobody", Power.title(9999, none))
        assertEquals("uid 1999", Power.title(1999, none))
        assertEquals("com.example.app", Power.title(1036, listOf("com.example.app")))
        assertTrue(Power.SYSTEM_UIDS.values.none { it.endsWith("_start") || it.endsWith("_end") })
        assertTrue(Power.SYSTEM_UIDS.keys.all { it < 10000 })
    }

    // The captured phone holds no partial wakelock and no fired alarm, so
    // these lines follow the layout of BatteryStats.printWakeLockCheckin
    // (time, type, count, current, max, total per type) with values added.
    @Test
    fun sumsPartialWakelocksJobsAlarmsAndNetwork() {
        val text = listOf(
            "9,0,i,vers,36,216,X,X",
            "9,10155,l,pwi,uid,1.5,0,0,0",
            "9,10155,l,wl,SyncLock,10,f,0,-1,-1,-1,2500,p,3,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0",
            "9,10155,l,wl,Other,0,f,0,-1,-1,-1,700,p,2,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0",
            "9,10155,l,jb,\"a,b/c.Service\",500,2,400,1",
            "9,10155,l,wua,*walarm*:X,4",
            "9,10155,l,wua,*walarm*:Y,2",
            "9,10155,l,nt,100,200,300,400,1,2,3,4,0,0,0,0,0,0,0,0,0,0,0,0",
            "9,10155,l,cpu,10,5,0",
            "9,10155,u,cpu,999,999,0",
        ).joinToString("\n")
        val u = Power.parseCheckin(text).uids.getValue(10155)
        assertEquals(3200L, u.wakeMs)
        assertEquals(5L, u.wakeCount)
        assertEquals(500L, u.jobMs)
        assertEquals(2L, u.jobCount)
        assertEquals(6L, u.alarms)
        assertEquals(1000L, u.netBytes)
        assertEquals(15L, u.cpuMs)
        assertEquals(listOf("9", "10155", "l", "jb", "a,b/c.Service", "500", "2", "400", "1"), Power.splitCheckin("9,10155,l,jb,\"a,b/c.Service\",500,2,400,1"))
    }

    @Test
    fun deltaIsNeverNegativeAndSeesTheCpuMove() {
        val s = Power.since(checkinA(), checkinB())
        assertEquals(false, s.rebased)
        assertTrue(s.rows.isNotEmpty())
        assertTrue(s.rows.all { it.mah >= 0.0 && it.cpuMs >= 0 && it.wakeMs >= 0 && it.jobMs >= 0 && it.netBytes >= 0 })
        assertEquals(173461L + 165422L - 173193L - 165210L, s.rows.first { it.uid == 1000 }.cpuMs)
        assertTrue(s.rows.sumOf { it.cpuMs } > 0)
        // most mAh first, equal mAh by CPU
        s.rows.zipWithNext().forEach { (x, y) ->
            assertTrue(x.mah > y.mah || (x.mah == y.mah && x.cpuMs >= y.cpuMs), "${x.uid} before ${y.uid}")
        }
        // the same checkin twice moved nothing
        val still = Power.since(checkinA(), checkinA())
        assertEquals(false, still.rebased)
        assertEquals(emptyList(), still.rows)
    }

    @Test
    fun aLowerTotalMeansTheCountersWereReset() {
        val reset = Power.since(checkinB(), checkinA())
        assertTrue(reset.rebased)
        assertEquals(emptyList(), reset.rows)
    }

    @Test
    fun readsTheUsageReport() {
        val usage = Power.parseUsage(res("usage.txt"))
        assertEquals(87, usage.size)
        val u = usage.getValue("u0a155")
        assertEquals(0.630, u.mah, 1e-9)
        assertEquals(0.00000200, u.states.getValue("fg"), 1e-12)
        assertEquals(0.270, u.states.getValue("bg"), 1e-9)
        assertEquals(0.359, u.states.getValue("fgs"), 1e-9)
        assertEquals(0.0000710, u.states.getValue("cached"), 1e-12)
        // components: the totals only, never cpu:fg and never the lines in parentheses
        assertEquals(setOf("cpu", "sensors", "gnss"), u.components.keys)
        assertEquals(0.0182, u.components.getValue("cpu"), 1e-9)
        assertEquals(0.612, u.components.getValue("sensors"), 1e-9)
        assertEquals(0.000110, u.components.getValue("gnss"), 1e-12)
        // a state that is absent stays absent
        val system = usage.getValue("1000")
        assertEquals(setOf("bg"), system.states.keys)
        assertEquals(0.680, system.components.getValue("sensors"), 1e-9)
        assertEquals(0.184, usage.getValue("u0a191").components.getValue("screen"), 1e-9)
        // a delta against itself is empty, against nothing it is itself
        val zero = u - u
        assertEquals(0.0, zero.mah, 1e-12)
        assertTrue(zero.states.values.all { it == 0.0 } && zero.components.values.all { it == 0.0 })
        assertEquals(0.630, (u - null).mah, 1e-9)
        assertTrue(Power.usageTotal(usage) > 0.0)
    }

    @Test
    fun usageToleratesADecimalComma() {
        val text = "    UID u0a1: 0,630 fg: 0,100 (1s 0ms) bg: 0,530\n      cpu=0,630 cpu:fg=0,100"
        val u = Power.parseUsage(text).getValue("u0a1")
        assertEquals(0.630, u.mah, 1e-9)
        assertEquals(0.100, u.states.getValue("fg"), 1e-9)
        assertEquals(0.630, u.components.getValue("cpu"), 1e-9)
    }

    @Test
    fun formatsUidsLikeAndroid() {
        assertEquals("0", Power.formatUid(0))
        assertEquals("1000", Power.formatUid(1000))
        assertEquals("9999", Power.formatUid(9999))
        assertEquals("u0a0", Power.formatUid(10000))
        assertEquals("u0a155", Power.formatUid(10155))
        assertEquals("u10a155", Power.formatUid(1010155))
        assertEquals("u10s1000", Power.formatUid(1001000))
        assertEquals("u0i1", Power.formatUid(99001))
        assertEquals("u0ai5000", Power.formatUid(95000))
        assertEquals("u20i1", Power.formatUid(2099001))
    }

    @Test
    fun countsOnlyTheExactPackage() {
        val bt = res("services_bt.txt")
        assertEquals(1, Power.countServices(bt, "com.google.android.bluetooth"))
        assertEquals(0, Power.countServices(bt, "com.android.bluetooth"))
        assertEquals(0, Power.countServices(res("services_none.txt"), "com.android.bluetooth"))
        assertEquals(0, Power.countServices(bt, ""))
        val two = bt + "\n" + bt
        assertEquals(2, Power.countServices(two, "com.google.android.bluetooth"))
    }

    @Test
    fun readsCpuTicksAfterTheLastParenthesis() {
        val line = "4321 (Binder:4321_1 (x) y) S 1 4321 4321 0 -1 4194560 12345 0 0 0 111 222 0 0 20 0 30 0 99"
        assertEquals(111L to 222L, Power.cpuTicks(line))
        assertEquals(5L to 6L, Power.cpuTicks("7 (a) R 1 2 3 4 5 6 7 8 9 10 5 6 0"))
    }

    @Test
    fun writesNumbersForReading() {
        assertEquals("0.630", Power.mahText(0.630))
        assertEquals("12.3", Power.mahText(12.34))
        assertEquals("1230", Power.mahText(1234.5))
        assertEquals("< 0.001", Power.mahText(0.0004))
        assertEquals("0", Power.mahText(0.0))
        assertEquals("0", Power.mahText(-1.0))
        assertEquals("0", Power.mahText(Double.NaN))
        assertEquals("500 ms", Power.durationText(500))
    }

    @Test
    fun emptyAndGarbageGiveEmptyResults() {
        val junk = listOf("", "\n\n", "garbage", "9,,,,", "9,x,l,wl", "a=b=c\n=\n,,,", "\u0001\u0002 (", "9,10,l,jb,\"unterminated,1,2")
        junk.forEach { text ->
            assertNull(Power.parseSysfs(text), text)
            assertNull(Power.parseDumpsysBattery(text), text)
            assertEquals(emptyList(), Power.parseCheckin(text).uids.values.filter { it.active }, text)
            assertEquals(emptyMap(), Power.parseUsage(text), text)
            assertEquals(0, Power.countServices(text, "com.example"), text)
            assertNull(Power.cpuTicks(text), text)
        }
        val none = Power.parseCheckin("")
        assertNull(none.capacityMah)
        assertEquals(emptyMap(), none.uids)
        assertEquals(emptyMap(), none.packages)
        val s = Power.since(none, none)
        assertEquals(false, s.rebased)
        assertEquals(emptyList(), s.rows)
        assertNull(Power.cpuTicks("1 (a) S 1 2"))
    }
}
