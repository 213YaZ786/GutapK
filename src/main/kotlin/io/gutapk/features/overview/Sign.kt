package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.ApkReader
import io.gutapk.core.apk.Change
import io.gutapk.core.apk.Compare
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.Signatures
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.TestKey
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.ui.Fact
import io.gutapk.ui.humanSize
import io.gutapk.ui.keyFingerprint
import io.gutapk.ui.keyLabel
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

const val SIGN_JOB = "sign"

// Everything the signing will do, before it does it: which key, which
// schemes, where the file lands, and what it means for installing.
@Composable
fun SignDialog(
    dir: Path,
    original: Path,
    info: ApkInfo,
    choice: KeyChoice?,
    version: String,
    onChangeKey: () -> Unit,
    onStarted: (Job) -> Unit,
    onDismiss: () -> Unit,
) {
    val output = if (choice != null) ApkSigning.output(dir, info.packageName, info.versionName, choice) else null
    // An own key chosen once, then its file removed by hand, cannot sign.
    val ownMissing = choice == KeyChoice.OWN && !OwnKey.exists()
    val ready = choice != null && output != null && !ownMissing
    val print = keyFingerprint(choice)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("sign_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Fact(t("sign_key"), keyLabel(choice))
                    TextButton(onClick = onChangeKey) { Text(t(if (choice == null) "sign_choose" else "sign_change")) }
                }
                if (print != null) {
                    SelectionContainer { Fact(t("signed_signer"), print) }
                }
                if (choice == KeyChoice.TEST) {
                    Text(t("sign_test_warning"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (ownMissing) {
                    Text(t("own_missing", OwnKey.keystore.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Fact(t("sign_schemes"), ApkSigning.schemesFor(info.minSdk).joinToString(", "))
                if (output != null) {
                    SelectionContainer { Fact(t("sign_output"), output.toString()) }
                }
                Text(t("sign_install_note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    val out = output
                    val key = choice
                    if (out != null && key != null) {
                        val job = JobQueue.start(SIGN_JOB) { job ->
                            // Loaded inside the job: the own key asks the
                            // keyring, which may show its unlock dialog.
                            val signing = if (key == KeyChoice.OWN) OwnKey.load() else TestKey.load()
                            ApkSigning.sign(original, out, signing, info.minSdk, version, job)
                            job.result = out.toString()
                        }
                        if (job != null) onStarted(job)
                    }
                },
            ) { Text(t("sign_go")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

sealed interface SignReport {
    data class Done(val file: Path) : SignReport
    data class Failed(val message: String) : SignReport
}

// The result is read back from the file itself, not from what the job
// believed it wrote.
@Composable
fun SignReportDialog(
    report: SignReport,
    onClose: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onTry: ((Path) -> Unit)? = null,
    original: Path? = null,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(if (report is SignReport.Done) "signed_title" else "sign_failed")) },
        text = {
            when (report) {
                is SignReport.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(report.message, style = MaterialTheme.typography.bodyMedium)
                    // The second engine is offered, never switched to alone.
                    if (onRetry != null) {
                        Text(
                            t("edit_retry_note"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SignReport.Done -> {
                    val check by produceState<SignatureInfo?>(null, report.file) {
                        value = withContext(Dispatchers.IO) { Signatures.verify(report.file) }
                    }
                    val changes by produceState<List<Change>?>(null, report.file, original) {
                        if (original == null) return@produceState
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                Compare.changes(
                                    ApkReader.read(original),
                                    ApkReader.read(report.file),
                                    Files.size(original),
                                    Files.size(report.file),
                                    Signatures.verify(original).signers.firstOrNull()?.sha256,
                                    Signatures.verify(report.file).signers.firstOrNull()?.sha256,
                                )
                            }.getOrNull()
                        }
                    }
                    SelectionContainer {
                        Column(
                            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Fact(t("signed_file"), report.file.toString())
                            val c = check
                            if (c != null) {
                                Fact(t("signed_schemes"), c.schemes.joinToString(", "))
                                c.signers.firstOrNull()?.let { Fact(t("signed_signer"), it.sha256) }
                            }
                            changes?.let { list ->
                                Text(t("cmp_title"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                list.forEach { ChangeRow(it) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
        dismissButton = {
            if (report is SignReport.Done) {
                Row {
                    TextButton(onClick = { showInFolder(report.file) }) { Text(t("show_folder")) }
                    if (onTry != null) TextButton(onClick = { onTry(report.file) }) { Text(t("try_button")) }
                }
            } else if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(t("edit_retry")) }
            }
        },
    )
}

@Composable
private fun changeValue(v: Any?): String = when (v) {
    null -> t("cmp_none")
    is Boolean -> t(if (v) "cmp_on" else "cmp_off")
    is List<*> -> if (v.isEmpty()) t("cmp_none") else v.joinToString(", ")
    else -> v.toString()
}

// One field, before then after. A permission list reads better as one
// name per line, without the android.permission. prefix.
@Composable
private fun ChangeRow(c: Change) {
    val text = when (c.key) {
        "size" -> {
            val a = c.before as Long
            val b = c.after as Long
            val delta = b - a
            humanSize(a) + "  →  " + humanSize(b) + "  (" + (if (delta >= 0) "+" else "-") + humanSize(kotlin.math.abs(delta)) + ")"
        }
        "signer" -> changeValue((c.before as? String)?.take(23)) + "  →  " + changeValue((c.after as? String)?.take(23))
        "permissions_removed" -> (c.before as List<*>).joinToString("\n") { it.toString().removePrefix("android.permission.") }
        "permissions_added" -> (c.after as List<*>).joinToString("\n") { it.toString().removePrefix("android.permission.") }
        else -> changeValue(c.before) + "  →  " + changeValue(c.after)
    }
    Fact(t("cmp_" + c.key), text)
}
