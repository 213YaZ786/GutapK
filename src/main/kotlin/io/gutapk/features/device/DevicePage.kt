package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.BatteryStatus
import io.gutapk.device.DeviceInfo
import io.gutapk.device.DeviceReader
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private sealed interface InfoState {
    data object Reading : InfoState
    data class Ready(val info: DeviceInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

// Where reboot can take the phone. Null is Android itself.
private val TARGETS = listOf(null to "dev_r_system", "recovery" to "dev_r_recovery", "bootloader" to "dev_r_bootloader")

// One device, read once on entry: identity, system, battery, storage and
// users. The pill restarts it, after showing the exact command.
@Composable
fun DevicePage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val state by produceState<InfoState>(InfoState.Reading, adb, d.serial) {
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceReader.read(adb, d.serial) }
            val info = read.getOrNull()
            if (info != null) InfoState.Ready(info) else InfoState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    var rebooting by remember { mutableStateOf(false) }
    val restart: @Composable () -> Unit = {
        FilledTonalButton(onClick = { rebooting = true }) { Text(t("dev_restart")) }
    }

    Page(title = d.model ?: d.serial, onBack = onBack, actions = restart) {
        Text(
            t("dev_t_device"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state) {
            InfoState.Reading -> BodyText(t("ov_reading"))
            is InfoState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is InfoState.Ready -> Details(d, s.info)
        }
    }

    if (rebooting) {
        RebootDialog(
            serial = d.serial,
            onRun = { target ->
                val args = DeviceReader.rebootArgs(d.serial, target)
                RunLog.line("[device] adb " + args.joinToString(" "))
                Thread { Adb.run(adb, args, 30) }.start()
                rebooting = false
            },
            onDismiss = { rebooting = false },
        )
    }
}

@Composable
private fun Details(d: AdbDevice, info: DeviceInfo) {
    val p = info.props
    fun prop(key: String): String = p[key]?.takeIf { it.isNotBlank() } ?: "?"
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Zone(t("dev_z_identity")) {
                ZoneRow(t("dev_maker"), prop("ro.product.manufacturer"))
                ZoneRow(t("dev_model"), prop("ro.product.model"))
                ZoneRow(t("dev_codename"), prop("ro.product.device"))
                ZoneRow(t("dev_serial"), d.serial)
            }
            Zone(t("dev_z_system")) {
                ZoneRow("Android", prop("ro.build.version.release") + "  ·  API " + prop("ro.build.version.sdk"))
                ZoneRow(t("dev_patch"), prop("ro.build.version.security_patch"))
                ZoneRow(t("dev_build"), prop("ro.build.display.id"))
                ZoneRow(t("ov_abis"), prop("ro.product.cpu.abilist").replace(",", ", "))
            }
            val b = info.battery
            Zone(t("dev_z_battery")) {
                if (b == null) {
                    BodyText(t("dev_unknown"))
                } else {
                    ZoneRow(t("dev_level"), (b.level?.toString() ?: "?") + " %")
                    ZoneRow(
                        t("dev_state"),
                        t(
                            when (b.status) {
                                BatteryStatus.CHARGING -> "dev_b_charging"
                                BatteryStatus.DISCHARGING -> "dev_b_discharging"
                                BatteryStatus.NOT_CHARGING -> "dev_b_not_charging"
                                BatteryStatus.FULL -> "dev_b_full"
                                BatteryStatus.UNKNOWN -> "dev_unknown"
                            },
                        ) + (b.plugged?.let { "  ·  $it" } ?: ""),
                    )
                    val c = b.celsius
                    if (c != null) ZoneRow(t("dev_temp"), "%.1f °C".format(c))
                }
            }
            val s = info.storage
            Zone(t("dev_z_storage")) {
                if (s == null) {
                    BodyText(t("dev_unknown"))
                } else {
                    ZoneRow(t("dev_free"), humanSize(s.freeKb * 1024) + "  ·  " + t("dev_of", humanSize(s.totalKb * 1024)))
                    ZoneRow(t("dev_used"), humanSize(s.usedKb * 1024))
                }
            }
            Zone(t("dev_z_users")) {
                if (info.users.isEmpty()) {
                    BodyText(t("dev_unknown"))
                } else {
                    info.users.forEach { u ->
                        ZoneRow(u.name.ifEmpty { "?" }, listOf(t("dev_user_id", u.id), if (u.running) t("dev_running") else t("dev_stopped")).joinToString("  ·  "))
                    }
                }
            }
        }
    }
}

// The choice, then the command it runs, before anything is sent.
@Composable
private fun RebootDialog(serial: String, onRun: (String?) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("dev_restart")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TARGETS.forEach { (value, key) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { target = value }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == value, onClick = { target = value })
                        Column {
                            Text(t(key))
                            Text(t(key + "_d"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(t("dev_command"), modifier = Modifier.padding(top = 8.dp))
                SelectionContainer {
                    Text("adb " + DeviceReader.rebootArgs(serial, target).joinToString(" "), fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(target) }) { Text(t("dev_restart_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
