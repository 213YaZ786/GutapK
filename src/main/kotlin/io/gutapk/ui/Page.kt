package io.gutapk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Width of reading content. Grids and two-column screens pass their own.
val ContentMaxWidth = 840.dp

// Every screen goes through here, so the corner buttons, the title and the
// pill are the same everywhere and a new screen cannot drift from the style.
// Corner buttons sit on the window corners, not on the column, so they stay
// put whatever the width of the content.
@Composable
fun Page(
    title: String,
    width: Dp = ContentMaxWidth,
    centered: Boolean = false,
    large: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    topEnd: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(start = 24.dp, end = 24.dp, top = 80.dp, bottom = if (actions != null) 112.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
        ) {
            Column(
                modifier = Modifier.widthIn(max = width).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                header?.invoke()
                Text(
                    title,
                    style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                content()
                footer?.invoke()
            }
        }
        if (onBack != null) {
            Box(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                CornerButton(GIcons.Back, t("back"), onBack)
            }
        }
        if (topEnd != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(20.dp)) { topEnd() }
        }
        if (actions != null) {
            Pill(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { actions() }
        }
    }
}

@Composable
fun CornerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

// Names every step from the first screen, so the user sees the whole setup
// before starting it, not one screen at a time.
@Composable
fun Stepper(labels: List<String>, current: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { i, label ->
            val reached = i <= current
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (reached) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (reached) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (i == current) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

// Radio buttons are allowed here and only here: a choice on a page shows its
// value and opens this dialog. An option not available yet stays listed,
// disabled, so the user sees it exists and why it cannot be picked.
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    disabled: Set<T> = emptySet(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    val enabled = value !in disabled
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = enabled) { onPick(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == current, onClick = { onPick(value) }, enabled = enabled)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
}
