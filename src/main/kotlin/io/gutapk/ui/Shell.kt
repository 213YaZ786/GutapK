package io.gutapk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.registry.Registry
import io.gutapk.registry.Source

// Past this width, rows become hard to read on a 2560 px screen.
private val ContentMaxWidth = 840.dp

@Composable
fun Shell() {
    var choice by remember { mutableStateOf(ThemeChoice.SYSTEM) }
    var picking by remember { mutableStateOf(false) }
    // gsettings is a process spawn. Once per run, never once per recomposition.
    val detected = remember { readSystemMode() }

    GutapkTheme(choice, detected) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(
                        "GutapK",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Zone("Source") {
                        SourceRow("Repository", "Clone and build from a git repository", Source.REPO)
                        SourceRow("APK file", "Open an APK from disk", Source.APK)
                        SourceRow("Device", "Connect a phone over ADB", Source.DEVICE)
                    }

                    Zone("Appearance") {
                        ZoneRow(
                            title = "Theme",
                            detail = themeLabel(choice, detected),
                            onClick = { picking = true },
                        )
                    }
                }
            }

            if (picking) {
                ThemeDialog(
                    current = choice,
                    detected = detected,
                    onPick = {
                        choice = it
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
            }
        }
    }
}

private fun themeLabel(choice: ThemeChoice, detected: SystemMode): String = when (choice) {
    ThemeChoice.SYSTEM -> when (detected) {
        SystemMode.UNKNOWN -> "Same as the system"
        else -> "Same as the system, " + detected.name.lowercase() + " detected"
    }
    ThemeChoice.LIGHT -> "Light"
    ThemeChoice.DARK -> "Dark"
}

@Composable
private fun ThemeDialog(
    current: ThemeChoice,
    detected: SystemMode,
    onPick: (ThemeChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme") },
        text = {
            Column {
                ThemeChoice.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onPick(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onPick(option) })
                        Text(themeLabel(option, detected), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SourceRow(title: String, detail: String, source: Source) {
    val count = Registry.forSource(source).size
    ZoneRow(
        title = title,
        detail = detail,
        onClick = if (count > 0) ({}) else null,
    ) {
        Text(
            if (count > 0) "$count features" else "No feature yet",
            style = MaterialTheme.typography.labelLarge,
            color = if (count > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
