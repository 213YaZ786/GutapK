package io.gutapk.features.overview

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val trimmed = name.trim()
    val spec = Tools.byId("apkeditor")
    val toolReady = spec != null && Installer.status(root, spec).let { it is ToolStatus.Installed && Installer.verify(root, spec) }
    val ownMissing = choice == KeyChoice.OWN && !OwnKey.exists()
    val ready = trimmed.isNotEmpty() && trimmed != info.label && choice != null && !ownMissing && spec != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("rename_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("rename_field")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                            tweaks = Tweaks(label = trimmed),
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
