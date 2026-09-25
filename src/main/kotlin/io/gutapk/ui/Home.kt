package io.gutapk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.OpenedPackage
import io.gutapk.core.apk.Packages
import io.gutapk.registry.Registry
import io.gutapk.registry.Source
import java.nio.file.Path

// Two tiles side by side at this width. Rows would leave most of a wide
// window empty.
private val HomeWidth = 960.dp

// The shell's rule: manage what exists only once something exists. The
// zone appears with the first opened package.
private const val RECENT_SHOWN = 6

@Composable
fun HomeScreen(
    version: String,
    root: Path?,
    onSettings: () -> Unit,
    onLicence: () -> Unit,
    onSource: (Source) -> Unit,
    onPackage: (Path) -> Unit,
    // An APK is being dragged over the window. The tile it will land in says
    // so, the user sees the drop is understood before letting go.
    dropping: Boolean = false,
) {
    val view = currentJobView()
    // Read again when a job ends, an import adds a package.
    val recent = remember(root, view?.state) { if (root == null) emptyList() else Packages.recent(root, RECENT_SHOWN) }
    Page(
        title = "GutapK",
        width = HomeWidth,
        large = true,
        topEnd = { CornerButton(GIcons.Settings, t("settings"), onSettings) },
        // An update accepted from the launch popup runs while Home is shown.
        actions = jobPill(view),
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
            val apkTile: @Composable (Modifier) -> Unit = { m -> SourceTile("src_apk", Source.APK, m, onSource, dropping) }
            val deviceTile: @Composable (Modifier) -> Unit = { m -> SourceTile("src_device", Source.DEVICE, m, onSource, false) }
            TileGrid(columns = 2, tiles = listOf(apkTile, deviceTile))
        }
        if (recent.isNotEmpty()) {
            Zone(t("home_recent")) {
                recent.forEach { p -> RecentRow(p, onPackage) }
            }
        }
    }
}

@Composable
private fun RecentRow(p: OpenedPackage, onPackage: (Path) -> Unit) {
    ZoneRow(
        title = p.label,
        detail = listOf(p.packageName, p.version).filter { it.isNotEmpty() }.joinToString("  ·  "),
        onClick = { onPackage(p.dir) },
    )
}

@Composable
private fun SourceTile(key: String, source: Source, modifier: Modifier, onSource: (Source) -> Unit, highlight: Boolean) {
    val count = Registry.forSource(source).size
    Tile(
        title = t(key),
        detail = t(key + "_d"),
        status = t(if (highlight) "drop_here" else if (count > 0) "available" else "not_yet"),
        available = count > 0,
        onClick = { onSource(source) },
        modifier = modifier,
        highlight = highlight,
    )
}
