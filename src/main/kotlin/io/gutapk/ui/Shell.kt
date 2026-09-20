package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.gutapk.registry.Registry
import io.gutapk.registry.Source

@Composable
fun Shell() {
    var choice by remember { mutableStateOf(ThemeChoice.SYSTEM) }

    GutapkTheme(choice) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("GutapK", style = MaterialTheme.typography.headlineMedium)

                    Zone("Source") {
                        SourceRow("Repository", "Clone and build from a git repository", Source.REPO)
                        SourceRow("APK file", "Open an APK from disk", Source.APK)
                        SourceRow("Device", "Connect a phone over ADB", Source.DEVICE)
                    }

                    Zone("Appearance") {
                        Text(
                            "Theme: " + choice.name.lowercase(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Detected: " + readSystemMode().name.lowercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Pill(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                ) {
                    TextButton(onClick = { choice = ThemeChoice.SYSTEM }) { Text("System") }
                    TextButton(onClick = { choice = ThemeChoice.LIGHT }) { Text("Light") }
                    TextButton(onClick = { choice = ThemeChoice.DARK }) { Text("Dark") }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, detail: String, source: Source) {
    val count = Registry.forSource(source).size
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = {}, enabled = count > 0) {
            Text(if (count > 0) "$count features" else "No feature yet")
        }
    }
}
