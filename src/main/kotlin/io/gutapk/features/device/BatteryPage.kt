package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppAction
import io.gutapk.device.AppActions
import io.gutapk.device.AppTest
import io.gutapk.device.BatteryReading
import io.gutapk.device.BatteryStatus
import io.gutapk.device.Checkin
import io.gutapk.device.Controls
import io.gutapk.device.DumpsysBattery
import io.gutapk.device.Key
import io.gutapk.device.Logcat
import io.gutapk.device.Power
import io.gutapk.device.UidUsage
import io.gutapk.device.UsageUid
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

// Five minutes of one-second readings.
private const val WINDOW = 300
private const val APPS_SHOWN = 30
private const val NOW_MS = 1000L
private const val SLOW_MS = 10_000L
private const val PHASE_S = 60

// Fewer than this many seconds of charge counter say nothing about a mean.
private const val AVERAGE_MIN_MS = 20_000L

private class Sample(val timeMs: Long, val reading: BatteryReading, val live: Boolean)

private enum class Step { START, USE, MIDDLE, IDLE, END }

private sealed interface TestState {
    data object Idle : TestState
    data class Running(val step: Step, val left: Int) : TestState
    data class Done(val result: AppTest) : TestState
    data class Failed(val key: String, val detail: String) : TestState
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

// One reading: the sysfs files when the shell may read the current, else
// dumpsys battery, which has no current but keeps a charge counter.
private fun readNow(adb: Path, serial: String): Sample? {
    val now = System.nanoTime() / 1_000_000
    val s = Power.sysfs(adb, serial)
    if (s != null && s.microAmps != null && s.microVolts != null) return Sample(now, s, true)
    val b = Power.battery(adb, serial)?.reading ?: return s?.let { Sample(now, it, false) }
    return Sample(now, b, false)
}

// The power test of Google's Core App Quality (T-Background_Service_Optimization):
// a reference reading, a minute of use, a minute left alone with the app
// quit, the counters read between. Every phone command that changes
// something is logged. Counters come from Power (BatteryStats checkin).
private suspend fun runTest(adb: Path, serial: String, uid: Int, pkg: String, show: (TestState) -> Unit): TestState {
    suspend fun countdown(step: Step) {
        for (left in PHASE_S downTo 1) {
            show(TestState.Running(step, left))
            delay(1000)
        }
    }
    show(TestState.Running(Step.START, 0))
    val a = io { Power.checkin(adb, serial) } ?: return TestState.Failed("bat_err_read", "")
    val launch = AppActions.command(AppAction.LAUNCH, pkg, uid / Power.PER_USER_RANGE)
    RunLog.line("[battery] adb -s $serial shell $launch")
    val started = io { AppActions.run(adb, serial, launch) }
    val refused = AppActions.failed(started.out)
    if (refused != null) return TestState.Failed("bat_err_launch", refused)

    countdown(Step.USE)
    show(TestState.Running(Step.MIDDLE, 0))
    val b = io { Power.checkin(adb, serial) } ?: return TestState.Failed("bat_err_read", "")
    if (Power.since(a, b).rebased) return TestState.Failed("bat_err_reset", "")
    val home = Controls.key(Key.HOME)
    RunLog.line("[battery] adb -s $serial shell $home")
    io { Adb.shell(adb, serial, home) }
    val pid = io { Logcat.pidof(adb, serial, pkg) }
    val before = if (pid != null) io { Power.ticks(adb, serial, pid) } else null

    countdown(Step.IDLE)
    show(TestState.Running(Step.END, 0))
    val c = io { Power.checkin(adb, serial) } ?: return TestState.Failed("bat_err_read", "")
    if (Power.since(b, c).rebased) return TestState.Failed("bat_err_reset", "")
    val pidAfter = io { Logcat.pidof(adb, serial, pkg) }
    val after = if (pidAfter != null && pidAfter == pid) io { Power.ticks(adb, serial, pidAfter) } else null
    val services = io { Power.services(adb, serial, pkg) }
    val idleTicks = if (before != null && after != null) after.first + after.second - before.first - before.second else null
    val empty = UidUsage(uid)
    return TestState.Done(
        AppTest(
            use = (b.uids[uid] ?: empty) - a.uids[uid],
            idle = (c.uids[uid] ?: empty) - b.uids[uid],
            services = services,
            alive = pidAfter != null,
            idleTicks = idleTicks,
        ),
    )
}

private fun statusKey(s: BatteryStatus): String = when (s) {
    BatteryStatus.CHARGING -> "dev_b_charging"
    BatteryStatus.DISCHARGING -> "dev_b_discharging"
    BatteryStatus.NOT_CHARGING -> "dev_b_not_charging"
    BatteryStatus.FULL -> "dev_b_full"
    BatteryStatus.UNKNOWN -> "dev_unknown"
}

// The phone's battery live, then the apps that used it since the page
// opened. Everything polls while the page is shown and stops with it.
@Composable
fun BatteryPage(adb: Path, d: AdbDevice, onWireless: () -> Unit, onBack: () -> Unit) {
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var cable by remember { mutableStateOf<DumpsysBattery?>(null) }
    var sent by remember { mutableStateOf(false) }
    var unplugging by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<Checkin?>(null) }
    var latest by remember { mutableStateOf<Checkin?>(null) }
    var rows by remember { mutableStateOf<List<UidUsage>>(emptyList()) }
    var rebased by remember { mutableStateOf(false) }
    var appsFailed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var usageBase by remember { mutableStateOf<Map<String, UsageUid>?>(null) }
    var usageNow by remember { mutableStateOf<Map<String, UsageUid>?>(null) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(adb, d.serial) {
        while (isActive) {
            val s = io { readNow(adb, d.serial) }
            if (s != null) samples = (samples + s).takeLast(WINDOW)
            delay(NOW_MS)
        }
    }
    LaunchedEffect(adb, d.serial) {
        while (isActive) {
            val c = io { Power.battery(adb, d.serial) }
            if (c != null) cable = c
            delay(SLOW_MS)
        }
    }
    LaunchedEffect(adb, d.serial) {
        while (isActive) {
            val now = io { Power.checkin(adb, d.serial) }
            if (now == null) {
                appsFailed = true
            } else {
                appsFailed = false
                latest = now
                val first = base
                if (first == null) {
                    base = now
                } else {
                    val since = Power.since(first, now)
                    if (since.rebased) {
                        base = now
                        rows = emptyList()
                        rebased = true
                    } else {
                        rows = since.rows
                    }
                }
            }
            delay(SLOW_MS)
        }
    }
    LaunchedEffect(adb, d.serial) {
        usageBase = io { Power.usage(adb, d.serial) }
    }
    LaunchedEffect(adb, d.serial, selected) {
        while (selected != null && isActive) {
            val u = io { Power.usage(adb, d.serial) }
            if (u != null) {
                val first = usageBase
                if (first == null || Power.usageTotal(u) + 1e-9 < Power.usageTotal(first)) usageBase = u
                usageNow = u
            }
            delay(SLOW_MS)
        }
    }
    // Leaving puts the real charging state back, on a thread of its own
    // since the page is already gone.
    DisposableEffect(adb, d.serial) {
        onDispose {
            if (sent) {
                RunLog.line("[battery] adb " + Power.resetArgs(d.serial).joinToString(" "))
                Thread { Adb.run(adb, Power.resetArgs(d.serial), 30) }.start()
            }
        }
    }

    val packages = latest?.packages.orEmpty()
    val sel = selected
    val powered = cable?.powered == true
    val pkg = if (sel != null) packages[sel % Power.PER_USER_RANGE]?.firstOrNull() else null
    val testLabel = t("bat_test")
    val cancelLabel = t("cancel")
    val running = test is TestState.Running

    val pill: @Composable () -> Unit = {
        if (running) {
            FilledTonalButton(onClick = {
                testJob?.cancel()
                test = TestState.Idle
            }) { Text(cancelLabel) }
        } else {
            FilledTonalButton(
                enabled = !powered,
                onClick = {
                    if (sel != null && pkg != null) {
                        testJob = scope.launch { test = runTest(adb, d.serial, sel, pkg) { test = it } }
                    }
                },
            ) { Text(testLabel) }
        }
    }
    val leaveDetail = {
        testJob?.cancel()
        test = TestState.Idle
        selected = null
    }

    Page(
        title = if (sel != null) appTitle(sel, packages) else t("dev_t_battery"),
        onBack = { if (sel != null) leaveDetail() else onBack() },
        actions = if (sel != null && pkg != null) pill else null,
    ) {
        if (sel == null) {
            NowZone(samples)
        }
        CableZone(
            powered = powered,
            sent = sent,
            onWireless = onWireless,
            onCount = { unplugging = true },
            onReal = {
                RunLog.line("[battery] adb " + Power.resetArgs(d.serial).joinToString(" "))
                Thread { Adb.run(adb, Power.resetArgs(d.serial), 30) }.start()
                sent = false
            },
        )
        if (sel == null) {
            AppsZone(rows, packages, rebased, appsFailed, latest == null) { selected = it }
        } else {
            val row = rows.firstOrNull { it.uid == sel } ?: UidUsage(sel)
            val label = Power.formatUid(sel)
            val usage = usageNow?.get(label)?.minus(usageBase?.get(label))
            AppDetail(row, usage, usageNow != null, pkg, powered, test)
        }
    }

    if (unplugging) {
        UnplugDialog(
            serial = d.serial,
            onRun = {
                RunLog.line("[battery] adb " + Power.unplugArgs(d.serial).joinToString(" "))
                Thread { Adb.run(adb, Power.unplugArgs(d.serial), 30) }.start()
                sent = true
                unplugging = false
            },
            onDismiss = { unplugging = false },
        )
    }
}

// Power now, its last five minutes, and the mean over them. A phone that
// keeps its current away from the shell gets the mean current of the charge
// counter instead, said to be a mean. The chart is Vico's basic line chart,
// with the line colour and fill taken from the theme.
// https://github.com/patrykandpatrick/vico/blob/v3.3.1/sample/shared/src/commonMain/kotlin/com/patrykandpatrick/vico/sample/charts/BasicLineChart.kt
@Composable
private fun NowZone(samples: List<Sample>) {
    val last = samples.lastOrNull()
    val r = last?.reading
    val live = last?.live == true
    val watts = samples.filter { it.live }.mapNotNull { it.reading.watts }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(watts) {
        if (watts.size >= 2) producer.runTransaction { lineModel { series(watts) } }
    }
    val counted = samples.filter { it.reading.chargeCounterUah != null }
    val mean = if (!live && counted.size >= 2) {
        val first = counted.first()
        val end = counted.last()
        if (end.timeMs - first.timeMs >= AVERAGE_MIN_MS) {
            Power.averageMilliAmps(first.timeMs, first.reading.chargeCounterUah ?: 0L, end.timeMs, end.reading.chargeCounterUah ?: 0L)
        } else {
            null
        }
    } else {
        null
    }
    Zone(t("bat_z_now")) {
        if (r == null) {
            BodyText(t("ov_reading"))
            return@Zone
        }
        val big = when {
            live -> r.watts?.let { "%.2f W".format(it) } ?: "?"
            mean != null -> "%.0f mA".format(mean)
            else -> "..."
        }
        Text(
            big,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (!live) BodyText(t("bat_avg_note"))
        if (live && watts.size >= 2) {
            BodyText(t("bat_chart_cap"))
            val line = LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
                areaFill = LineCartesianLayer.AreaFill.single(Fill(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))),
            )
            ProvideVicoTheme(rememberM3VicoTheme()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(lineProvider = LineCartesianLayer.LineProvider.series(line)),
                        startAxis = VerticalAxis.rememberStart(),
                    ),
                    modelProducer = producer,
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp),
                    animationSpec = null,
                )
            }
            ZoneRow(t("bat_avg_window"), "%.2f W".format(watts.average()))
        }
        r.milliAmps?.let { ZoneRow(t("bat_current"), "%.0f mA".format(it)) }
        if (!live && mean != null) ZoneRow(t("bat_avg_current"), "%.0f mA".format(mean))
        r.volts?.let { ZoneRow(t("bat_voltage"), "%.2f V".format(it)) }
        r.level?.let { ZoneRow(t("dev_level"), "$it %") }
        r.celsius?.let { ZoneRow(t("dev_temp"), "%.1f °C".format(it)) }
        ZoneRow(t("dev_state"), t(statusKey(r.status)))
    }
}

@Composable
private fun CableZone(powered: Boolean, sent: Boolean, onWireless: () -> Unit, onCount: () -> Unit, onReal: () -> Unit) {
    if (!powered && !sent) return
    Zone(t("bat_z_cable")) {
        if (powered) {
            BodyText(t("bat_paused"))
            ZoneRow(t("bat_wifi"), t("bat_wifi_d"), onClick = onWireless)
            ZoneRow(t("bat_count"), t("bat_count_d"), onClick = onCount)
        }
        if (sent) ZoneRow(t("bat_real"), t("bat_real_d"), onClick = onReal)
    }
}

// Every adb shell command runs as AID_SHELL (Power.SHELL_APP_ID), GutapK's own
// readings included, so that row says so instead of a package name.
// https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcutils/include/private/android_filesystem_config.h
@Composable
private fun appTitle(uid: Int, packages: Map<Int, List<String>>): String =
    if (uid % Power.PER_USER_RANGE == Power.SHELL_APP_ID) t("bat_shell")
    else Power.title(uid, packages[uid % Power.PER_USER_RANGE].orEmpty())

@Composable
private fun usageLine(u: UidUsage): String {
    val parts = ArrayList<String>()
    parts.add(Power.mahText(u.mah) + " mAh")
    if (u.cpuMs > 0) parts.add("CPU " + Power.durationText(u.cpuMs))
    if (u.wakeMs > 0 || u.wakeCount > 0) parts.add(t("bat_wake", Power.durationText(u.wakeMs), u.wakeCount))
    if (u.jobCount > 0) parts.add(t("bat_jobs", u.jobCount))
    if (u.alarms > 0) parts.add(t("bat_alarms", u.alarms))
    if (u.netBytes > 0) parts.add(t("bat_net", humanSize(u.netBytes)))
    return parts.joinToString("  ·  ")
}

@Composable
private fun AppsZone(
    rows: List<UidUsage>,
    packages: Map<Int, List<String>>,
    rebased: Boolean,
    failed: Boolean,
    reading: Boolean,
    onOpen: (Int) -> Unit,
) {
    Zone(t("bat_z_apps")) {
        when {
            reading && failed -> BodyText(t("bat_err_read"))
            reading -> BodyText(t("ov_reading"))
            rows.isEmpty() -> BodyText(t("bat_apps_empty"))
            else -> rows.take(APPS_SHOWN).forEach { u ->
                ZoneRow(
                    appTitle(u.uid, packages),
                    usageLine(u),
                    onClick = { onOpen(u.uid) },
                )
            }
        }
        if (rebased) BodyText(t("bat_rebased"))
    }
}

@Composable
private fun AppDetail(row: UidUsage, usage: UsageUid?, usageReady: Boolean, pkg: String?, powered: Boolean, test: TestState) {
    Zone(t("bat_z_app")) {
        ZoneRow(t("bat_activity"), usageLine(row))
        if (usage != null) ZoneRow(t("bat_energy"), Power.mahText(usage.mah) + " mAh")
    }
    Zone(t("bat_z_state")) {
        if (usage == null) {
            BodyText(t(if (usageReady) "bat_none" else "ov_reading"))
        } else {
            listOf("fg" to "bat_fg", "bg" to "bat_bg", "fgs" to "bat_fgs", "cached" to "bat_cached").forEach { (k, key) ->
                ZoneRow(t(key), Power.mahText(usage.states[k] ?: 0.0) + " mAh")
            }
        }
    }
    Zone(t("bat_z_comp")) {
        val parts = usage?.components?.filterValues { it > 0.0 }?.entries?.sortedByDescending { it.value }.orEmpty()
        if (parts.isEmpty()) {
            BodyText(t(if (usage == null && !usageReady) "ov_reading" else "bat_none"))
        } else {
            parts.forEach { ZoneRow(it.key, Power.mahText(it.value) + " mAh") }
        }
    }
    if (pkg != null && powered && test !is TestState.Running) BodyText(t("bat_test_needs"))
    when (test) {
        TestState.Idle -> {}
        is TestState.Running -> Zone(t("bat_z_test")) {
            val key = when (test.step) {
                Step.START -> "bat_step_start"
                Step.USE -> "bat_step_use"
                Step.MIDDLE -> "bat_step_middle"
                Step.IDLE -> "bat_step_idle"
                Step.END -> "bat_step_end"
            }
            ZoneRow(t(key), if (test.left > 0) t("bat_left", test.left) else "")
        }
        is TestState.Failed -> Zone(t("ov_error")) {
            BodyText(t(test.key) + if (test.detail.isEmpty()) "" else "  " + test.detail)
        }
        is TestState.Done -> ResultZones(test.result)
    }
}

// Use against idle, one line per counter, then what the app left behind.
@Composable
private fun ResultZones(r: AppTest) {
    Zone(t("bat_z_result")) {
        CompareRow("", t("bat_col_use"), t("bat_col_idle"), header = true)
        CompareRow(t("bat_energy"), Power.mahText(r.use.mah) + " mAh", Power.mahText(r.idle.mah) + " mAh")
        CompareRow("CPU", Power.durationText(r.use.cpuMs), Power.durationText(r.idle.cpuMs))
        CompareRow(
            t("bat_wakelock"),
            Power.durationText(r.use.wakeMs) + " (" + r.use.wakeCount + ")",
            Power.durationText(r.idle.wakeMs) + " (" + r.idle.wakeCount + ")",
        )
        CompareRow(t("bat_jobs_label"), r.use.jobCount.toString(), r.idle.jobCount.toString())
        CompareRow(t("bat_alarms_label"), r.use.alarms.toString(), r.idle.alarms.toString())
        CompareRow(t("bat_net_label"), humanSize(r.use.netBytes), humanSize(r.idle.netBytes))
    }
    Zone(t("bat_z_after")) {
        ZoneRow(t("bat_services"), r.services?.toString() ?: t("dev_unknown"))
        ZoneRow(t("bat_process"), t(if (r.alive) "bat_alive" else "bat_dead"))
        if (r.alive) {
            ZoneRow(
                t("bat_idle_cpu"),
                r.idleTicks?.let { Power.durationText(it.coerceAtLeast(0L) * Power.TICK_MS) } ?: t("dev_unknown"),
            )
        }
    }
}

@Composable
private fun CompareRow(label: String, use: String, idle: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    val colour = if (header) MaterialTheme.colorScheme.primary else Color.Unspecified
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(use, style = style, color = colour, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(idle, style = style, color = colour, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// The command is shown before it is sent, like the restart.
@Composable
private fun UnplugDialog(serial: String, onRun: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("bat_count")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("bat_count_text"))
                Text(t("dev_command"))
                SelectionContainer {
                    Text("adb " + Power.unplugArgs(serial).joinToString(" "), fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onRun) { Text(t("bat_count_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
