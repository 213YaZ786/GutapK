package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.keyChoiceOf
import io.gutapk.settings.Settings
import io.gutapk.settings.SettingsStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Two columns from this width, one below it.
private val TwoColumnsFrom = 900.dp

@Composable
fun SettingsScreen(
    settings: Settings,
    lang: Lang,
    theme: ThemeChoice,
    detected: SystemMode,
    accent: AccentChoice,
    systemAccent: String?,
    version: String,
    root: java.nio.file.Path?,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit,
    onRoot: () -> Unit,
    onDisk: () -> Unit,
    onLicence: () -> Unit,
    onLegal: () -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) }

    val general: @Composable () -> Unit = {
        Zone(t("set_general")) {
            ZoneRow(t("set_language"), lang.native, onClick = { dialog = "lang" })
            ZoneRow(t("set_theme"), themeLabel(theme, detected), onClick = { dialog = "theme" })
            ZoneRow(t("set_accent"), accentLabel(accent, systemAccent), onClick = { dialog = "accent" })
        }
    }
    val storage: @Composable () -> Unit = {
        Zone(t("set_storage")) {
            ZoneRow(t("set_root"), settings.root ?: "", onClick = onRoot)
            ZoneRow(t("disk_title"), t("disk_row"), onClick = onDisk)
        }
        ToolsZone(
            root = root,
            checkUpdates = settings.checkUpdates,
            onCheckUpdates = { onChange(settings.copy(checkUpdates = it)) },
        )
    }
    val signing: @Composable () -> Unit = {
        val choice = keyChoiceOf(settings.signKey)
        Zone(t("set_signing")) {
            ZoneRow(t("set_sign_key"), keyLabel(choice), onClick = { dialog = "key" })
            keyFingerprint(choice)?.let { ZoneRow(t("signed_signer"), it) }
            if (choice == KeyChoice.OWN) ZoneRow(t("set_key_file"), OwnKey.keystore.toString())
        }
    }
    val legal: @Composable () -> Unit = {
        Zone(t("set_legal")) {
            ZoneRow(t("lic_title"), t("set_licence_d"), onClick = onLicence)
            ZoneRow(t("legal_title"), t("legal_accepted", settings.legalRev), onClick = onLegal)
        }
    }
    val about: @Composable () -> Unit = {
        Zone(t("set_about")) {
            ZoneRow(t("about_version"), version)
            ZoneRow(t("about_config"), SettingsStore.dir.toString())
        }
    }

    Page(title = t("settings"), width = 1120.dp, onBack = onBack, actions = jobPill(currentJobView())) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= TwoColumnsFrom) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        general()
                        storage()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        signing()
                        legal()
                        about()
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    general()
                    storage()
                    signing()
                    legal()
                    about()
                }
            }
        }
    }

    when (dialog) {
        "lang" -> ChoiceDialog(
            title = t("set_language"),
            options = Lang.entries.map { it to it.native },
            current = lang,
            onPick = {
                onChange(settings.copy(lang = it.code))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "key" -> KeyChooser(
            current = keyChoiceOf(settings.signKey),
            onChosen = {
                onChange(settings.copy(signKey = it.name))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "accent" -> ChoiceDialog(
            title = t("set_accent"),
            options = AccentChoice.entries.map { it to accentLabel(it, systemAccent) },
            current = accent,
            onPick = {
                onChange(settings.copy(accent = it.name))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "theme" -> ChoiceDialog(
            title = t("set_theme"),
            options = ThemeChoice.entries.map { it to themeLabel(it, detected) },
            current = theme,
            onPick = {
                onChange(settings.copy(theme = it.name))
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

// SYSTEM names the colour GNOME has set, so the user sees what follows.
@Composable
fun accentLabel(choice: AccentChoice, systemAccent: String?): String = when (choice) {
    AccentChoice.SYSTEM -> accentOf(systemAccent)?.let { t("accent_system_named", accentName(it)) } ?: t("accent_system")
    else -> accentName(choice)
}

@Composable
private fun accentName(choice: AccentChoice): String = t("accent_" + choice.name.lowercase())

@Composable
fun themeLabel(choice: ThemeChoice, detected: SystemMode): String = when (choice) {
    ThemeChoice.SYSTEM -> when (detected) {
        SystemMode.LIGHT -> t("theme_system_light")
        SystemMode.DARK -> t("theme_system_dark")
        SystemMode.UNKNOWN -> t("theme_system")
    }
    ThemeChoice.LIGHT -> t("theme_light")
    ThemeChoice.DARK -> t("theme_dark")
}
