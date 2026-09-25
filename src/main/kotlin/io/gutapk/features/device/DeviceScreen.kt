package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.DeviceState
import io.gutapk.job.JobState
import io.gutapk.registry.Feature
import io.gutapk.registry.Source
import io.gutapk.tools.Installer
import io.gutapk.tools.RunLog
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.GIcons
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Page
import io.gutapk.ui.Tile
import io.gutapk.ui.TileGrid
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

object DeviceFeature : Feature {
    override val id = "device"
    override val label = "Device"
    override val sources = setOf(Source.DEVICE)
}

private const val TOOL = "platform-tools"
private const val POLL_MS = 2000L

private sealed interface Link {
    data object Checking : Link
    data object NoTool : Link
    data class Mismatch(val adb: Path, val server: Int, val client: Int, val release: String?) : Link
    data class Devices(val adb: Path, val list: List<AdbDevice>) : Link
    data class Failed(val message: String) : Link
}

// The adb GutapK installed, verified once per visit: hashing it every two
// seconds would cost more than the poll itself.
private fun ownAdb(root: Path): Path? {
    val spec = Tools.byId(TOOL) ?: return null
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: return null
    if (!Installer.verify(root, spec)) return null
    return Installer.entry(root, spec, status.version)
}

// A server of another version would be restarted by any adb command, the
// user's own adb cut off without a word. So the version is read over the
// socket first, and a mismatch stops the poll until the user decides.
private fun probe(adb: Path, client: Pair<Int, String?>): Link {
    val server = Adb.serverVersion()
    if (server != null && server != client.first) return Link.Mismatch(adb, server, client.first, client.second)
    return Link.Devices(adb, Adb.devices(adb))
}

@Composable
fun DeviceScreen(root: Path?, onPulled: (List<Path>) -> Unit, onBack: () -> Unit) {
    // Bumped to start over: after the download, after a restart.
    var attempt by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf<Link>(Link.Checking) }
    var asking by remember { mutableStateOf(false) }
    var afterTool by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    // The guide the user closed, so it does not reopen until the state
    // changes.
    var closedGuide by remember { mutableStateOf<String?>(null) }
    // The tile opened on the device's home, null for the home itself.
    var page by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(root, attempt) {
        if (root == null) return@LaunchedEffect
        link = Link.Checking
        val adb = withContext(Dispatchers.IO) { ownAdb(root) }
        if (adb == null) {
            link = Link.NoTool
            return@LaunchedEffect
        }
        val client = withContext(Dispatchers.IO) { runCatching { Adb.clientVersion(adb) } }.getOrElse {
            link = Link.Failed(it.message ?: it.javaClass.simpleName)
            return@LaunchedEffect
        }
        while (isActive) {
            val next = withContext(Dispatchers.IO) { runCatching { probe(adb, client) }.getOrElse { Link.Failed(it.message ?: "adb") } }
            link = next
            if (next !is Link.Devices) break
            delay(POLL_MS)
        }
    }

    val view = currentJobView()
    LaunchedEffect(view) {
        if (afterTool && view != null && view.title == TOOL) {
            when (view.state) {
                JobState.DONE -> {
                    afterTool = false
                    attempt++
                }
                JobState.FAILED, JobState.CANCELLED -> {
                    afterTool = false
                }
                else -> {}
            }
        }
    }

    val l = link
    val devices = (l as? Link.Devices)?.list.orEmpty()
    val ready = devices.filter { it.state == DeviceState.READY }
    val current = ready.firstOrNull { it.serial == chosen } ?: ready.singleOrNull()

    val adb = (l as? Link.Devices)?.adb
    if (current != null && adb != null && page == "dev_t_device") {
        DevicePage(adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_logcat") {
        LogcatPage(adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_wireless") {
        WirelessPage(adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_controls") {
        ControlsPage(adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_mirror") {
        MirrorPage(root, adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_files") {
        FilesPage(adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_install") {
        InstallPage(root, adb, current, onBack = { page = null })
    } else if (current != null && adb != null && page == "dev_t_apps") {
        AppsPage(root, adb, current, onPulled = onPulled, onBack = { page = null })
    } else if (current != null) {
        DeviceHome(current, onBack, onTile = { page = it })
    } else {
        Page(title = t("dev_title"), onBack = onBack) {
            when (l) {
                Link.Checking -> BodyText(t("ov_reading"))
                Link.NoTool -> Zone(t("dev_tool")) {
                    BodyText(t("dev_tool_d"))
                    ZoneRow(t("dev_tool_get"), "platform-tools", onClick = { asking = true })
                }
                is Link.Failed -> Zone(t("ov_error")) {
                    BodyText(l.message)
                    ZoneRow(t("retry"), "", onClick = { attempt++ })
                }
                is Link.Mismatch -> BodyText(t("dev_waiting"))
                is Link.Devices -> {
                    Zone(t("dev_status")) {
                        ZoneRow(t("dev_waiting"), t(guideKey(devices, ready.size)), onClick = { closedGuide = null })
                    }
                    // A phone can also come over Wi-Fi, without a cable.
                    WirelessConnect(l.adb)
                }
            }
        }
    }

    val spec = Tools.byId(TOOL)
    if (asking && root != null && spec != null) {
        LookupDialog(
            root = root,
            spec = spec,
            onDismiss = { asking = false },
            why = t("dev_tool_d"),
            onStarted = { afterTool = true },
        )
    }
    if (l is Link.Mismatch) {
        MismatchDialog(
            l,
            onRestart = {
                RunLog.line("[device] ${l.adb} kill-server, server version ${l.server}, client ${l.client}")
                Thread {
                    Adb.killServer(l.adb)
                    javax.swing.SwingUtilities.invokeLater { attempt++ }
                }.start()
                link = Link.Checking
            },
            onCancel = onBack,
        )
    }
    if (l is Link.Devices && current == null) {
        val key = guideKey(devices, ready.size)
        if (ready.size > 1) {
            ChooseDialog(ready, onPick = { chosen = it }, onDismiss = onBack)
        } else if (closedGuide != key) {
            GuideDialog(key, onClose = { closedGuide = key })
        }
    }
}

// Which guide fits what adb sees. Several ready devices ask for a choice
// instead.
private fun guideKey(devices: List<AdbDevice>, ready: Int): String = when {
    ready > 1 -> "dev_g_several"
    devices.any { it.state == DeviceState.NO_PERMISSION } -> "dev_g_permission"
    devices.any { it.state == DeviceState.UNAUTHORIZED } -> "dev_g_unauthorized"
    devices.any { it.state == DeviceState.OFFLINE } -> "dev_g_offline"
    devices.isNotEmpty() -> "dev_g_other"
    else -> "dev_g_none"
}

// Closes by itself when the device reaches the next state, since the key
// changes with it.
@Composable
private fun GuideDialog(key: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t(key + "_d"))
                if (key == "dev_g_permission") {
                    SelectionContainer {
                        Text("sudo apt install android-sdk-platform-tools-common", fontFamily = FontFamily.Monospace)
                    }
                    Text(t("dev_g_permission_after"))
                }
                Text(t("dev_g_auto"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
    )
}

@Composable
private fun MismatchDialog(m: Link.Mismatch, onRestart: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(t("dev_mismatch")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("dev_mismatch_d", m.server, m.client, m.release ?: "?"))
                SelectionContainer {
                    Text(m.adb.toString() + " kill-server", fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { TextButton(onClick = onRestart) { Text(t("dev_mismatch_go")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(t("cancel")) } },
    )
}

@Composable
private fun ChooseDialog(ready: List<AdbDevice>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var pick by remember { mutableStateOf(ready.first().serial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("dev_g_several")) },
        text = {
            Column {
                Text(t("dev_g_several_d"))
                ready.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { pick = d.serial }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pick == d.serial, onClick = { pick = d.serial })
                        Text(listOfNotNull(d.model, d.serial).distinct().joinToString("  ·  "))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(pick) }) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

// The device's own page, its model as the title. Each tile is one kind of
// work, opened in the versions that follow.
@Composable
private fun DeviceHome(d: AdbDevice, onBack: () -> Unit, onTile: (String) -> Unit) {
    Page(title = d.model ?: d.serial, width = 960.dp, onBack = onBack) {
        Text(
            d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val tiles = listOf(
            Triple("dev_t_device", GIcons.Device, true),
            Triple("dev_t_apps", GIcons.Apps, true),
            Triple("dev_t_install", GIcons.Install, true),
            Triple("dev_t_files", GIcons.Folder, true),
            Triple("dev_t_debloat", GIcons.Debloat, false),
            Triple("dev_t_mirror", GIcons.Cast, true),
            Triple("dev_t_controls", GIcons.Tune, true),
            Triple("dev_t_wireless", GIcons.Wifi, true),
            Triple("dev_t_logcat", GIcons.Log, true),
            Triple("dev_t_console", GIcons.Terminal, false),
        ).map { (key, icon, available) ->
            val tile: @Composable (Modifier) -> Unit = { m ->
                Tile(
                    title = t(key),
                    detail = t(key + "_d"),
                    status = t(if (available) "available" else "not_yet"),
                    available = available,
                    onClick = { onTile(key) },
                    modifier = m,
                    icon = icon,
                )
            }
            tile
        }
        TileGrid(columns = 3, tiles = tiles)
    }
}
