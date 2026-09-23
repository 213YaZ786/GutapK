package io.gutapk.features.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.IconKind
import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.Tweaks
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.TestKey
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.tools.Installer
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.KeyChooser
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.keyLabel
import io.gutapk.ui.t
import java.nio.file.Path

const val RENAME_JOB = "rename"

// A page rather than a dialog, so each tweak gets its own zone and the list
// can grow. Ask first, do after: every row only records an answer, the disk
// is touched once the pill action runs, and Back leaves the app untouched.
@Composable
fun EditScreen(
    root: Path,
    packageDir: Path,
    original: Path,
    info: ApkInfo,
    choice: KeyChoice?,
    version: String,
    onSignKey: (KeyChoice) -> Unit,
    onStarted: (Job) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(info.label ?: "") }
    var minSdk by remember { mutableStateOf(info.minSdk) }
    var targetSdk by remember { mutableStateOf(info.targetSdk) }
    var themed by remember { mutableStateOf(false) }
    // Every permission starts kept. Switching one off marks it for removal,
    // so the default action leaves the app exactly as it was.
    val kept = remember { mutableStateMapOf<String, Boolean>().apply { info.permissions.forEach { put(it, true) } } }
    var dialog by remember { mutableStateOf<String?>(null) }

    val trimmed = name.trim()
    val toRemove = info.permissions.filter { kept[it] == false }.toSet()
    val nameChanged = trimmed.isNotEmpty() && trimmed != info.label
    val minChanged = minSdk != null && minSdk != info.minSdk
    val targetChanged = targetSdk != null && targetSdk != info.targetSdk
    val anyChange = nameChanged || minChanged || targetChanged || toRemove.isNotEmpty() || themed
    val spec = Tools.byId("apkeditor")
    // Verify hashes the jar, so it runs once per visit, not on every switch.
    val toolReady = remember { spec != null && Installer.status(root, spec).let { it is ToolStatus.Installed && Installer.verify(root, spec) } }
    val ownMissing = choice == KeyChoice.OWN && !OwnKey.exists()
    val keyReady = choice != null && !ownMissing
    val ready = anyChange && keyReady && spec != null

    val changed: @Composable () -> Unit = {
        Text(t("edit_changed"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }

    // The pill says why it cannot act yet instead of showing a greyed button.
    val action: @Composable () -> Unit = {
        if (ready) {
            FilledTonalButton(onClick = {
                val key = choice ?: return@FilledTonalButton
                val job = JobQueue.start(RENAME_JOB) { job ->
                    val signing = if (key == KeyChoice.OWN) OwnKey.load() else TestKey.load()
                    val result = Edit.run(
                        root = root,
                        packageDir = packageDir,
                        input = original,
                        tweaks = Tweaks(
                            label = if (nameChanged) trimmed else null,
                            minSdk = if (minChanged) minSdk else null,
                            targetSdk = if (targetChanged) targetSdk else null,
                            removePermissions = toRemove,
                            themedIcon = themed,
                        ),
                        key = signing,
                        keyName = key.name,
                        packageName = info.packageName,
                        version = info.versionName,
                        minSdk = info.minSdk,
                        appVersion = version,
                        sink = job,
                        cancelled = { job.cancelRequested },
                    )
                    job.result = result.output.toString()
                }
                if (job != null) onStarted(job)
            }) { Text(t("rename_go")) }
        } else {
            Text(
                t(if (!anyChange) "edit_nothing_yet" else "edit_needs_key"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    Page(
        title = t("edit_title"),
        onBack = onBack,
        actions = action,
    ) {
        Text(
            info.label ?: info.packageName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Zone(t("ov_identity")) {
            ZoneRow(
                t("rename_field"),
                trimmed.ifEmpty { info.label ?: "?" },
                onClick = { dialog = "name" },
                trailing = if (nameChanged) changed else null,
            )
        }

        Zone(t("edit_zone_sdk")) {
            ZoneRow(
                t("ov_min_sdk"),
                apiName(minSdk),
                onClick = { dialog = "min" },
                trailing = if (minChanged) changed else null,
            )
            ZoneRow(
                t("ov_target_sdk"),
                apiName(targetSdk),
                onClick = { dialog = "target" },
                trailing = if (targetChanged) changed else null,
            )
        }

        // Shown for every icon kind, so an app that cannot get a themed icon
        // says why instead of hiding the option.
        Zone(t("edit_zone_icon")) {
            val adaptive = info.iconKind == IconKind.ADAPTIVE
            val toggle: () -> Unit = { themed = !themed }
            val switch: @Composable () -> Unit = { Switch(checked = themed, onCheckedChange = { themed = it }) }
            ZoneRow(
                t("edit_themed_icon"),
                t(
                    when (info.iconKind) {
                        IconKind.ADAPTIVE -> "edit_themed_note"
                        IconKind.THEMED -> "edit_themed_done"
                        IconKind.LEGACY -> "edit_themed_legacy"
                        IconKind.NONE -> "edit_themed_none"
                    },
                ),
                onClick = if (adaptive) toggle else null,
                trailing = if (adaptive) switch else null,
            )
        }

        if (info.permissions.isNotEmpty()) {
            Zone(t("ov_permissions", info.permissions.size)) {
                BodyText(t("edit_perm_note"))
                info.permissions.forEach { perm ->
                    val on = kept[perm] != false
                    ZoneRow(
                        perm.substringAfterLast('.'),
                        perm,
                        onClick = { kept[perm] = !on },
                        trailing = { Switch(checked = on, onCheckedChange = { kept[perm] = it }) },
                    )
                }
            }
        }

        Zone(t("edit_zone_signing")) {
            ZoneRow(t("sign_key"), keyLabel(choice), onClick = { dialog = "key" })
            if (choice == KeyChoice.TEST) {
                Text(
                    t("sign_test_warning"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (!toolReady) BodyText(t("rename_needs_tool"))
            BodyText(t("sign_install_note"))
        }
    }

    when (dialog) {
        "name" -> ValueDialog(
            title = t("rename_field"),
            initial = name,
            digits = false,
            onDone = {
                name = it
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "min" -> ValueDialog(
            title = t("ov_min_sdk"),
            initial = minSdk?.toString() ?: "",
            digits = true,
            onDone = {
                minSdk = it.toIntOrNull() ?: info.minSdk
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "target" -> ValueDialog(
            title = t("ov_target_sdk"),
            initial = targetSdk?.toString() ?: "",
            digits = true,
            onDone = {
                targetSdk = it.toIntOrNull() ?: info.targetSdk
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "key" -> KeyChooser(
            current = choice,
            onChosen = {
                onSignKey(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

// One field, OK and Cancel. An empty answer keeps the value the app has,
// so clearing a field cannot silently apply a blank.
@Composable
private fun ValueDialog(
    title: String,
    initial: String,
    digits: Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = if (digits) it.filter(Char::isDigit) else it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value.trim()) }) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
