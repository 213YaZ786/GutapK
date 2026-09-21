package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.tools.RootCheck
import io.gutapk.tools.RootProblem
import io.gutapk.tools.Storage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
fun LanguageStep(subtitle: String?, onPick: (Lang) -> Unit) {
    Page(title = t("lang_title"), subtitle = subtitle) {
        Zone(t("lang_title")) {
            Lang.entries.forEach { lang ->
                ZoneRow(title = lang.native, detail = lang.english, onClick = { onPick(lang) })
            }
        }
    }
}

@Composable
fun LicenceScreen(subtitle: String?, onBack: (() -> Unit)?, onContinue: (() -> Unit)?) {
    val actions: (@Composable () -> Unit)? = if (onContinue != null) {
        { TextButton(onClick = onContinue) { Text(t("continue")) } }
    } else {
        null
    }
    Page(title = t("lic_title"), subtitle = subtitle, onBack = onBack, actions = actions) {
        Zone(t("lic_title")) {
            Text(
                LICENCE_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun LegalScreen(
    subtitle: String?,
    onBack: (() -> Unit)?,
    onAccept: (() -> Unit)?,
    onDecline: (() -> Unit)?,
) {
    val actions: (@Composable () -> Unit)? = if (onAccept != null) {
        {
            if (onDecline != null) TextButton(onClick = onDecline) { Text(t("decline")) }
            TextButton(onClick = onAccept) { Text(t("accept")) }
        }
    } else {
        null
    }
    Page(title = t("legal_title"), subtitle = subtitle, onBack = onBack, actions = actions) {
        Zone(t("legal_title")) { BodyText(t("legal_body")) }
    }
}

@Composable
fun RootScreen(
    subtitle: String?,
    initial: String,
    note: String?,
    onBack: (() -> Unit)?,
    onDone: (Path) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<RootProblem?>(null) }

    fun submit() {
        when (val c = Storage.check(text)) {
            is RootCheck.Bad -> problem = c.problem
            is RootCheck.Ok -> {
                val p = Storage.prepare(c.path)
                if (p != null) problem = p else onDone(c.path)
            }
        }
    }

    Page(
        title = t("root_title"),
        subtitle = subtitle,
        onBack = onBack,
        actions = { TextButton(onClick = { submit() }) { Text(t("continue")) } },
    ) {
        Zone(t("set_root")) {
            BodyText(t("root_body"))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            problem = null
                        },
                        label = { Text(t("root_field")) },
                        singleLine = true,
                        isError = problem != null,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            pickFolder(text)?.let {
                                text = it
                                problem = null
                            }
                        },
                    ) {
                        Text(t("browse"))
                    }
                }
                problem?.let {
                    Text(
                        t(
                            when (it) {
                                RootProblem.NOT_ABSOLUTE -> "root_bad_absolute"
                                RootProblem.FORBIDDEN -> "root_bad_forbidden"
                                RootProblem.NOT_WRITABLE -> "root_bad_write"
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Swing's chooser is the one AWT offers that selects folders on Linux.
// FileDialog only picks files there.
private fun pickFolder(current: String): String? {
    val start = File(Storage.expand(current)).let { f ->
        generateSequence(f) { it.parentFile }.firstOrNull { Files.isDirectory(it.toPath()) }
    }
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
