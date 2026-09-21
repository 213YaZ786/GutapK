package io.gutapk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.registry.Registry
import io.gutapk.registry.Source

@Composable
fun HomeScreen(version: String, onSettings: () -> Unit, onLicence: () -> Unit) {
    Page(
        title = "GutapK",
        topEnd = { TextButton(onClick = onSettings) { Text(t("settings")) } },
        footer = {
            // The GPL asks an interactive program to show this notice. A line
            // that is always there does it without blocking every launch.
            Text(
                t("footer", version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onLicence)
                    .padding(8.dp),
            )
        },
    ) {
        Zone(t("home_source")) {
            SourceRow("src_repo", Source.REPO)
            SourceRow("src_build", Source.BUILD)
            SourceRow("src_apk", Source.APK)
            SourceRow("src_device", Source.DEVICE)
        }
    }
}

@Composable
private fun SourceRow(key: String, source: Source) {
    val count = Registry.forSource(source).size
    ZoneRow(
        title = t(key),
        detail = t(key + "_d"),
        onClick = if (count > 0) ({}) else null,
    ) {
        Text(
            t(if (count > 0) "available" else "not_yet"),
            style = MaterialTheme.typography.labelLarge,
            color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
