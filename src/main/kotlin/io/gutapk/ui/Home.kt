package io.gutapk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.registry.Registry
import io.gutapk.registry.Source

// Four sources fill a 2 x 2 grid at this width. Rows would leave most of a
// wide window empty.
private val HomeWidth = 960.dp

@Composable
fun HomeScreen(version: String, onSettings: () -> Unit, onLicence: () -> Unit) {
    Page(
        title = "GutapK",
        width = HomeWidth,
        large = true,
        topEnd = { CornerButton(GIcons.Settings, t("settings"), onSettings) },
        // An update accepted from the launch popup runs while Home is shown.
        actions = jobPill(currentJobView()),
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                t("home_source"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp),
            )
            TileRow(
                { SourceTile("src_repo", Source.REPO, it) },
                { SourceTile("src_build", Source.BUILD, it) },
            )
            Spacer(Modifier.height(8.dp))
            TileRow(
                { SourceTile("src_apk", Source.APK, it) },
                { SourceTile("src_device", Source.DEVICE, it) },
            )
        }
    }
}

// Intrinsic height makes both tiles of a row as tall as the taller one, so a
// longer description in one language does not leave a ragged grid.
@Composable
private fun TileRow(start: @Composable (Modifier) -> Unit, end: @Composable (Modifier) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        start(Modifier.weight(1f).fillMaxHeight())
        end(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SourceTile(key: String, source: Source, modifier: Modifier) {
    val count = Registry.forSource(source).size
    val base = modifier.clip(MaterialTheme.shapes.large)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = zoneFill(),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (count > 0) base.clickable {} else base,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t(key), style = MaterialTheme.typography.titleLarge)
            Text(
                t(key + "_d"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                t(if (count > 0) "available" else "not_yet"),
                style = MaterialTheme.typography.labelLarge,
                color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
