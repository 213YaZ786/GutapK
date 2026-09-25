package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.time.LocalDate

const val KEYGEN_JOB = "keygen"
const val KEY_BACKUP_JOB = "keybackup"
const val KEY_RESTORE_JOB = "keyrestore"

// Long enough to resist guessing if the file leaks, short enough to type.
private const val MIN_PASSWORD = 10

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

// Calls onEnd once the given job has finished, whatever the outcome.
@Composable
private fun WatchJob(started: Job?, onEnd: (JobState, String) -> Unit) {
    val view = currentJobView()
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(view) {
        if (started != null && view != null && JobQueue.current.value === started) {
            when (view.state) {
                JobState.DONE, JobState.FAILED, JobState.CANCELLED -> end(view.state, view.message)
                else -> {}
            }
        }
    }
}

@Composable
private fun WorkingDialog(title: String) {
    AlertDialog(
        // Closing it would not stop the job, so it is not closable.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(t("key_working"), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PasswordField(value: String, label: String, error: String?, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { e -> { Text(e) } },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// The own key copied into a file the user keeps, under a password of their
// own. What, where and the risk are said before anything is written.
@Composable
fun KeyBackupDialog(onDismiss: () -> Unit) {
    var folder by remember { mutableStateOf<Path?>(null) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    WatchJob(started) { state, message ->
        started = null
        if (state != JobState.CANCELLED) outcome = (state == JobState.DONE) to message
    }
    val chooseTitle = t("key_backup_choose")
    val name = "gutapk-signing-key-" + LocalDate.now() + ".p12"
    val done = outcome
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t(if (done.first) "key_backup_title" else "key_failed")) },
            text = { Text(if (done.first) t("key_backup_done", done.second) else done.second, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                if (done.first) TextButton(onClick = { showInFolder(Path.of(done.second)) }) { Text(t("key_show")) }
            },
        )
        started != null -> WorkingDialog(t("key_backup_title"))
        else -> {
            val short = password.isNotEmpty() && password.length < MIN_PASSWORD
            val differ = again.isNotEmpty() && again != password
            val target = folder?.resolve(name)
            val ready = target != null && password.length >= MIN_PASSWORD && password == again
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(t("key_backup_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("dl_what"), t("key_backup_what"))
                        SelectionContainer { Fact(t("key_backup_file"), target?.toString() ?: t("key_backup_none")) }
                        TextButton(onClick = {
                            Chooser.folder(chooseTitle, System.getProperty("user.home")) { p -> if (p != null) folder = p }
                        }) { Text(chooseTitle) }
                        PasswordField(password, t("create_password"), if (short) t("key_password_short") else null) { password = it }
                        PasswordField(again, t("key_password_again"), if (differ) t("key_password_differ") else null) { again = it }
                        Text(t("key_backup_warn"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = ready,
                        onClick = {
                            val pw = password.toCharArray()
                            if (target != null) {
                                started = JobQueue.start(KEY_BACKUP_JOB) { job ->
                                    try {
                                        OwnKey.backup(target, pw, job)
                                        job.result = target.toString()
                                    } finally {
                                        pw.fill('\u0000')
                                    }
                                }
                            }
                        },
                    ) { Text(t("key_backup_go")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
            )
        }
    }
}

// A backup made GutapK's own key, only while it has none. onRestored gets
// the fingerprint so the caller can select the key.
@Composable
fun KeyRestoreDialog(onRestored: () -> Unit, onDismiss: () -> Unit) {
    var file by remember { mutableStateOf<Path?>(null) }
    var password by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    WatchJob(started) { state, message ->
        started = null
        if (state != JobState.CANCELLED) outcome = (state == JobState.DONE) to message
        if (state == JobState.DONE) onRestored()
    }
    val chooseTitle = t("key_restore_choose")
    val filter = t("key_restore_filter")
    val done = outcome
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t(if (done.first) "key_restore_title" else "key_failed")) },
            text = { Text(if (done.first) t("key_restore_done", done.second) else done.second, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        started != null -> WorkingDialog(t("key_restore_title"))
        else -> {
            val source = file
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(t("key_restore_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("dl_what"), t("key_restore_what"))
                        SelectionContainer { Fact(t("key_restore_file"), source?.toString() ?: t("key_backup_none")) }
                        TextButton(onClick = {
                            Chooser.fileOf(chooseTitle, filter, listOf("p12", "pfx", "jks", "keystore")) { p -> if (p != null) file = p }
                        }) { Text(chooseTitle) }
                        PasswordField(password, t("create_password"), null) { password = it }
                        SelectionContainer { Fact(t("set_key_file"), OwnKey.keystore.toString()) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = source != null && password.isNotEmpty(),
                        onClick = {
                            val pw = password.toCharArray()
                            if (source != null) {
                                started = JobQueue.start(KEY_RESTORE_JOB) { job ->
                                    try {
                                        job.result = fingerprint(OwnKey.restore(source, pw, job))
                                    } finally {
                                        pw.fill('\u0000')
                                    }
                                }
                            }
                        },
                    ) { Text(t("key_restore_go")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
            )
        }
    }
}
