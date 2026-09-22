package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
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
import io.gutapk.ui.Fact
import io.gutapk.ui.keyLabel
import io.gutapk.ui.t
import java.nio.file.Path

const val RENAME_JOB = "rename"

// The rename asks for the new name, then shows a review before touching the
// disk. If APKEditor is missing, the review says it will be downloaded, with
// its facts, so nothing is fetched without the user seeing it first.
@Composable
fun RenameDialog(
    root: Path,
    packageDir: Path,
    original: Path,
    info: ApkInfo,
    choice: KeyChoice?,
    version: String,
    onChangeKey: () -> Unit,
    onStarted: (Job) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(info.label ?: "") }
    var minText by remember { mutableStateOf(info.minSdk?.toString() ?: "") }
    var targetText by remember { mutableStateOf(info.targetSdk?.toString() ?: "") }
    val trimmed = name.trim()
    // Every permission starts kept. Unticking one marks it for removal, so
    // the default action leaves the app exactly as it was.
    val kept = remember { mutableStateMapOf<String, Boolean>().apply { info.permissions.forEach { put(it, true) } } }
    val toRemove = info.permissions.filter { kept[it] == false }.toSet()
    val minSdk = minText.trim().toIntOrNull()
    val targetSdk = targetText.trim().toIntOrNull()
    // A field left as it was is not a change. An SDK field that does not
    // parse to a number blocks, so a typo cannot silently do nothing.
    val minBad = minText.isNotBlank() && minSdk == null
    val targetBad = targetText.isNotBlank() && targetSdk == null
    val nameChanged = trimmed.isNotEmpty() && trimmed != info.label
    val minChanged = minSdk != null && minSdk != info.minSdk
    val targetChanged = targetSdk != null && targetSdk != info.targetSdk
    val anyChange = nameChanged || minChanged || targetChanged || toRemove.isNotEmpty()
    val spec = Tools.byId("apkeditor")
    val toolReady = spec != null && Installer.status(root, spec).let { it is ToolStatus.Installed && Installer.verify(root, spec) }
    val ownMissing = choice == KeyChoice.OWN && !OwnKey.exists()
    val ready = anyChange && !minBad && !targetBad && choice != null && !ownMissing && spec != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("edit_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("rename_field")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it.filter(Char::isDigit) },
                        label = { Text(t("edit_min_sdk")) },
                        singleLine = true,
                        isError = minBad,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it.filter(Char::isDigit) },
                        label = { Text(t("edit_target_sdk")) },
                        singleLine = true,
                        isError = targetBad,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (info.permissions.isNotEmpty()) {
                    Text(t("edit_permissions"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(t("edit_perm_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        info.permissions.forEach { perm ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = kept[perm] != false, onCheckedChange = { kept[perm] = it })
                                Text(perm.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Column {
                    Fact(t("sign_key"), keyLabel(choice))
                    TextButton(onClick = onChangeKey) { Text(t(if (choice == null) "sign_choose" else "sign_change")) }
                }
                if (choice == KeyChoice.TEST) {
                    Text(t("sign_test_warning"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (!toolReady) {
                    Text(t("rename_needs_tool"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(t("sign_install_note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val key = choice ?: return@TextButton
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
                },
            ) { Text(t("rename_go")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
