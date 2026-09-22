package io.gutapk.ui

import androidx.compose.runtime.Composable
import io.gutapk.core.sign.KeyChoice
import io.gutapk.tools.RunLog
import java.nio.file.Path

@Composable
fun keyLabel(choice: KeyChoice?): String = when (choice) {
    KeyChoice.TEST -> t("key_test")
    KeyChoice.OWN -> t("key_own")
    null -> t("sign_key_none")
}

// Shown from Settings and from the sign dialog, the same list in both. The
// own key stays listed while it does not exist yet, with the reason.
@Composable
fun KeyDialog(current: KeyChoice?, onPick: (KeyChoice) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog<KeyChoice?>(
        title = t("key_title"),
        options = listOf(
            KeyChoice.TEST to t("key_test") + "\n" + t("key_test_d"),
            KeyChoice.OWN to t("key_own") + "\n" + t("key_own_later"),
        ),
        current = current,
        onPick = { picked -> if (picked != null) onPick(picked) },
        onDismiss = onDismiss,
        disabled = setOf(KeyChoice.OWN),
    )
}

// The file manager of the desktop, through xdg-open, on the folder holding
// the file. A desktop without it only loses the shortcut.
fun showInFolder(file: Path) {
    val dir = (if (java.nio.file.Files.isDirectory(file)) file else file.parent) ?: return
    runCatching {
        ProcessBuilder("xdg-open", dir.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.onFailure { RunLog.line("xdg-open failed: ${it.message}") }
}
