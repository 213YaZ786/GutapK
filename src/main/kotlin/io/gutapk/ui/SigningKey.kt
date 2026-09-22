package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.TestKey
import io.gutapk.core.sign.fingerprint
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.RunLog
import java.nio.file.Files
import java.nio.file.Path

const val KEYGEN_JOB = "keygen"

@Composable
fun keyLabel(choice: KeyChoice?): String = when (choice) {
    KeyChoice.TEST -> t("key_test")
    KeyChoice.OWN -> t("key_own")
    null -> t("sign_key_none")
}

// The certificate fingerprint of a choice, when it can be known without
// asking the keyring. Null for an own key not created yet.
fun keyFingerprint(choice: KeyChoice?): String? = when (choice) {
    KeyChoice.TEST -> TestKey.CERT_SHA256
    KeyChoice.OWN -> OwnKey.readCertificate()?.let { fingerprint(it) }
    null -> null
}

// One entry point for choosing a key, from Settings and from the sign
// dialog alike. Choosing the own key the first time creates it, after a
// dialog that says what is created, where, and what losing it means.
@Composable
fun KeyChooser(current: KeyChoice?, onChosen: (KeyChoice) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = started
        if (job != null && view != null && JobQueue.current.value === job) {
            when (view.state) {
                JobState.DONE -> {
                    started = null
                    creating = false
                    onChosen(KeyChoice.OWN)
                }
                JobState.FAILED -> {
                    started = null
                    error = view.message
                }
                JobState.CANCELLED -> {
                    started = null
                }
                else -> {}
            }
        }
    }

    val failure = error
    when {
        failure != null -> AlertDialog(
            onDismissRequest = {
                error = null
                creating = false
            },
            title = { Text(t("create_failed")) },
            text = { Text(failure, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    error = null
                    creating = false
                }) { Text(t("close")) }
            },
        )
        started != null -> AlertDialog(
            // Closing it would not stop the job, so it is not closable.
            onDismissRequest = {},
            title = { Text(t("creating")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(t("creating_note"), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
        creating -> CreateKeyDialog(
            onCreate = {
                val job = JobQueue.start(KEYGEN_JOB) { job ->
                    val cert = OwnKey.create(job)
                    job.result = fingerprint(cert)
                }
                if (job != null) started = job
            },
            onDismiss = { creating = false },
        )
        else -> KeyDialog(
            current = current,
            onPick = { picked ->
                if (picked == KeyChoice.OWN && !OwnKey.exists()) {
                    creating = true
                } else {
                    onChosen(picked)
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun KeyDialog(current: KeyChoice?, onPick: (KeyChoice) -> Unit, onDismiss: () -> Unit) {
    val own = OwnKey.readCertificate()?.let { t("key_own_ready", fingerprint(it).take(23)) } ?: t("key_own_new")
    ChoiceDialog<KeyChoice?>(
        title = t("key_title"),
        options = listOf(
            KeyChoice.TEST to t("key_test") + "\n" + t("key_test_d"),
            KeyChoice.OWN to t("key_own") + "\n" + own,
        ),
        current = current,
        onPick = { picked -> if (picked != null) onPick(picked) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun CreateKeyDialog(onCreate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("create_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact(t("dl_what"), t("create_what_v"))
                SelectionContainer { Fact(t("set_key_file"), OwnKey.keystore.toString()) }
                Fact(t("create_password"), t("create_password_v"))
                Text(t("create_backup"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text(t("create_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

// The file manager of the desktop, through xdg-open, on the folder holding
// the file. A desktop without it only loses the shortcut.
fun showInFolder(file: Path) {
    val dir = (if (Files.isDirectory(file)) file else file.parent) ?: return
    runCatching {
        ProcessBuilder("xdg-open", dir.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.onFailure { RunLog.line("xdg-open failed: ${it.message}") }
}
