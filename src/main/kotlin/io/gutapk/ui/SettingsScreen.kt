package io.gutapk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gutapk.settings.Settings
import io.gutapk.settings.SettingsStore

@Composable
fun SettingsScreen(
    settings: Settings,
    lang: Lang,
    theme: ThemeChoice,
    detected: SystemMode,
    version: String,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit,
    onRoot: () -> Unit,
    onLicence: () -> Unit,
    onLegal: () -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) }

    Page(title = t("settings"), onBack = onBack) {
        Zone(t("set_general")) {
            ZoneRow(t("set_language"), lang.native, onClick = { dialog = "lang" })
            ZoneRow(t("set_theme"), themeLabel(theme, detected), onClick = { dialog = "theme" })
        }
        Zone(t("set_storage")) {
            ZoneRow(t("set_root"), settings.root ?: "", onClick = onRoot)
        }
        Zone(t("set_legal")) {
            ZoneRow(t("lic_title"), t("set_licence_d"), onClick = onLicence)
            ZoneRow(t("legal_title"), t("legal_accepted", settings.legalRev), onClick = onLegal)
        }
        Zone(t("set_about")) {
            ZoneRow(t("about_version"), version)
            ZoneRow(t("about_config"), SettingsStore.dir.toString())
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
