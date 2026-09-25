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
import java.nio.file.Path

// Where the user is in the first run, or null when a screen is reopened
// from Settings.
data class StepProgress(val labels: List<String>, val current: Int)

// First-run screens are a narrow card centred in the window. Settings reuses
// the same screens at reading width, with a back button instead.
private val SetupWidth = 640.dp

private fun progressHeader(p: StepProgress?): (@Composable () -> Unit)? =
    if (p == null) null else { { Stepper(p.labels, p.current) } }

@Composable
fun LanguageStep(progress: StepProgress?, onPick: (Lang) -> Unit) {
    Page(title = t("lang_title"), width = SetupWidth, centered = true, header = progressHeader(progress)) {
        Zone(t("lang_title")) {
            Lang.entries.forEach { lang ->
                ZoneRow(title = lang.native, detail = lang.english, onClick = { onPick(lang) })
            }
        }
    }
}

@Composable
fun LicenceScreen(progress: StepProgress?, onBack: (() -> Unit)?, onContinue: (() -> Unit)?) {
    val actions: (@Composable () -> Unit)? = if (onContinue != null) {
        { TextButton(onClick = onContinue) { Text(t("continue")) } }
    } else {
        null
    }
    Page(
        title = t("lic_title"),
        width = if (progress != null) SetupWidth else ContentMaxWidth,
        centered = progress != null,
        header = progressHeader(progress),
        onBack = onBack,
        actions = actions,
    ) {
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
    progress: StepProgress?,
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
    Page(
        title = t("legal_title"),
        width = if (progress != null) 720.dp else ContentMaxWidth,
        centered = progress != null,
        header = progressHeader(progress),
        onBack = onBack,
        actions = actions,
    ) {
        Zone(t("legal_title")) { BodyText(t("legal_body")) }
    }
}

@Composable
fun RootScreen(
    progress: StepProgress?,
    initial: String,
    note: String?,
    onBack: (() -> Unit)?,
    onDone: (Path) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<RootProblem?>(null) }
    val chooseTitle = t("root_title")

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
        width = if (progress != null) SetupWidth else ContentMaxWidth,
        centered = progress != null,
        header = progressHeader(progress),
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
                            Chooser.folder(chooseTitle, Storage.expand(text)) { picked ->
                                picked?.let {
                                    text = it.toString()
                                    problem = null
                                }
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
                                RootProblem.TOO_WIDE -> "root_bad_wide"
                                RootProblem.NOT_EMPTY -> "root_bad_not_empty"
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
