package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.Signatures
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.TestKey
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.ui.Fact
import io.gutapk.ui.keyLabel
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val ready = choice == KeyChoice.TEST && output != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("sign_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Fact(t("sign_key"), keyLabel(choice))
                    TextButton(onClick = onChangeKey) { Text(t(if (choice == null) "sign_choose" else "sign_change")) }
                }
                if (choice == KeyChoice.TEST) {
                    Text(t("sign_test_warning"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
                    if (out != null) {
                        val job = JobQueue.start(SIGN_JOB) { job ->
                            ApkSigning.sign(original, out, TestKey.load(), info.minSdk, version, job)
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
fun SignReportDialog(report: SignReport, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(if (report is SignReport.Done) "signed_title" else "sign_failed")) },
        text = {
            when (report) {
                is SignReport.Failed -> Text(report.message, style = MaterialTheme.typography.bodyMedium)
                is SignReport.Done -> {
                    val check by produceState<SignatureInfo?>(null, report.file) {
                        value = withContext(Dispatchers.IO) { Signatures.verify(report.file) }
                    }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Fact(t("signed_file"), report.file.toString())
                            val c = check
                            if (c != null) {
                                Fact(t("signed_schemes"), c.schemes.joinToString(", "))
                                c.signers.firstOrNull()?.let { Fact(t("signed_signer"), it.sha256) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
        dismissButton = {
            if (report is SignReport.Done) {
                TextButton(onClick = { showInFolder(report.file) }) { Text(t("show_folder")) }
            }
        },
    )
}
