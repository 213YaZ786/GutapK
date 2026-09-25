package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.MdnsService
import io.gutapk.device.PortRule
import io.gutapk.device.Wireless
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

// adb itself run with args, logged, its output handed back whatever it says.
private fun host(scope: CoroutineScope, adb: Path, args: List<String>, onOut: (String) -> Unit) {
    RunLog.line("[device] adb " + args.joinToString(" "))
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { Adb.run(adb, args, 60) } }
        onOut(r.getOrNull()?.out?.trim()?.ifEmpty { "ok" } ?: (r.exceptionOrNull()?.message ?: "adb"))
    }
}

// Pairing and connecting before any cable: shown while GutapK waits for a
// phone. Phones with Wireless debugging on are found on the network and
// listed, the user can also type an address.
@Composable
fun WirelessConnect(adb: Path) {
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<MdnsService>>(emptyList()) }
    LaunchedEffect(adb) {
        while (true) {
            found = withContext(Dispatchers.IO) {
                runCatching { Wireless.parseMdns(Adb.run(adb, Wireless.mdnsArgs(), 15).out) }.getOrDefault(emptyList())
            }
            delay(4000)
        }
    }
    Zone(t("wl_title")) {
        found.forEach { s ->
            if (s.pairing) {
                ZoneRow(t("wl_pair_found", s.name), s.address, onClick = { pairing = s.address })
            } else {
                ZoneRow(t("wl_connect_found", s.name), s.address, onClick = { host(scope, adb, Wireless.connect(s.address)) { answer = it } })
            }
        }
        ZoneRow(t("wl_pair"), t("wl_pair_d"), onClick = { pairing = "" })
        ZoneRow(t("wl_connect"), t("wl_connect_d"), onClick = { connecting = true })
        BodyText(t("wl_note"))
    }
    val p = pairing
    if (p != null) {
        PairDialog(p, onPair = { address, code ->
            pairing = null
            host(scope, adb, Wireless.pair(address, code)) { answer = it }
        }, onDismiss = { pairing = null })
    }
    if (connecting) {
        AddressDialog(t("wl_connect"), "", onDone = { address ->
            connecting = false
            host(scope, adb, Wireless.connect(address)) { answer = it }
        }, onDismiss = { connecting = false })
    }
    ResultDialog(answer) { answer = null }
}

// One phone: its address on Wi-Fi, switching it from USB to Wi-Fi, and
// the port rules between computer and phone.
@Composable
fun WirelessPage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var answer by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }
    val ip by produceState<String?>(null, d.serial) {
        value = withContext(Dispatchers.IO) { runCatching { Wireless.parseIp(Adb.shell(adb, d.serial, Wireless.IP_COMMAND).out) }.getOrNull() }
    }
    val rules by produceState(emptyList<PortRule>() to emptyList<PortRule>(), d.serial, revision) {
        value = withContext(Dispatchers.IO) {
            fun read(kind: String) = runCatching { Wireless.parseRules(Adb.run(adb, Wireless.listRules(kind, d.serial), 15).out) }.getOrDefault(emptyList())
            read("forward") to read("reverse")
        }
    }
    val network = Wireless.isNetwork(d.serial)

    Page(title = t("dev_t_wireless"), width = 960.dp, onBack = onBack) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("wl_link")) {
            ZoneRow(t("wl_now"), t(if (network) "wl_now_network" else "wl_now_usb"))
            ZoneRow(t("wl_ip"), ip ?: t("wl_ip_none"))
            val address = ip
            if (!network && address != null) {
                ZoneRow(t("wl_switch"), t("wl_switch_d", address), onClick = { switching = true })
            }
            if (network) {
                ZoneRow(t("wl_disconnect"), d.serial, onClick = { host(scope, adb, Wireless.disconnect(d.serial)) { answer = it } })
            }
        }
        listOf("forward" to rules.first, "reverse" to rules.second).forEach { (kind, list) ->
            Zone(t("wl_rules_$kind")) {
                if (list.isEmpty()) BodyText(t("wl_rules_none"))
                list.forEach { r ->
                    ZoneRow("${r.local}  →  ${r.remote}", t("wl_rule_remove"), onClick = {
                        host(scope, adb, Wireless.removeRule(kind, r.local, d.serial)) {
                            answer = it
                            revision++
                        }
                    })
                }
                ZoneRow(t("wl_rule_add"), t("wl_rules_${kind}_d"), onClick = { adding = kind })
            }
        }
    }

    val address = ip
    if (switching && address != null) {
        val steps = listOf(Wireless.tcpip(d.serial), Wireless.connect("$address:${Wireless.TCP_PORT}"))
        AlertDialog(
            onDismissRequest = { switching = false },
            title = { Text(t("wl_switch")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("wl_switch_note"))
                    Text(t("dev_command"))
                    SelectionContainer {
                        Text(steps.joinToString("\n") { "adb " + it.joinToString(" ") }, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    switching = false
                    host(scope, adb, steps[0]) { first ->
                        // The phone restarts adbd on TCP, give it a moment.
                        scope.launch {
                            delay(2000)
                            host(scope, adb, steps[1]) { answer = first + "\n" + it }
                        }
                    }
                }) { Text(t("wl_switch")) }
            },
            dismissButton = { TextButton(onClick = { switching = false }) { Text(t("cancel")) } },
        )
    }
    val kind = adding
    if (kind != null) {
        RuleDialog(onAdd = { local, remote ->
            adding = null
            host(scope, adb, Wireless.rule(kind, local, remote, d.serial)) {
                answer = it
                revision++
            }
        }, onDismiss = { adding = null })
    }
    ResultDialog(answer) { answer = null }
}

@Composable
private fun ResultDialog(text: String?, onClose: () -> Unit) {
    if (text != null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(t("ct_result")) },
            text = { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
        )
    }
}

@Composable
private fun AddressDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(initial) }
    val ok = Wireless.validAddress(address.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(t("wl_address")) },
                supportingText = { Text(t("wl_address_d")) },
                isError = address.isNotBlank() && !ok,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(address.trim()) }, enabled = ok) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

// The address and the six digits the phone shows under Pair device with
// pairing code.
@Composable
private fun PairDialog(initial: String, onPair: (String, String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(initial) }
    var code by remember { mutableStateOf("") }
    val ok = Wireless.validAddress(address.trim()) && Wireless.validCode(code.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("wl_pair")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("wl_pair_how"))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(t("wl_address")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text(t("wl_code")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPair(address.trim(), code.trim()) }, enabled = ok) { Text(t("wl_pair_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

@Composable
private fun RuleDialog(onAdd: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var local by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    val l = Wireless.port(local)
    val r = Wireless.port(remote)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("wl_rule_add")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = local, onValueChange = { v -> local = v.filter { it.isDigit() }.take(5) }, label = { Text(t("wl_local")) }, singleLine = true)
                OutlinedTextField(value = remote, onValueChange = { v -> remote = v.filter { it.isDigit() }.take(5) }, label = { Text(t("wl_remote")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (l != null && r != null) onAdd(l, r) }, enabled = l != null && r != null) { Text(t("ok")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
