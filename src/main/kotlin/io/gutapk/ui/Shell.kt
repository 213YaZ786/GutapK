package io.gutapk.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.gutapk.settings.Settings
import io.gutapk.settings.SettingsStore
import io.gutapk.tools.RunSession
import io.gutapk.tools.Storage
import io.gutapk.tools.Update
import io.gutapk.tools.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen { HOME, SETTINGS, ROOT, DISK, LICENCE, LEGAL }

private enum class FirstStep { LANGUAGE, LICENCE, LEGAL, ROOT }

// The order is the shell's. The program names itself before anything else,
// the legal notice comes before any work, and the root is chosen before a
// single file is written, the log included.
private fun firstSteps(s: Settings): List<FirstStep> = buildList {
    if (s.lang == null) {
        add(FirstStep.LANGUAGE)
        add(FirstStep.LICENCE)
    }
    if (s.legalRev != SettingsStore.LEGAL_REV) add(FirstStep.LEGAL)
    if (s.root == null) add(FirstStep.ROOT)
}

private fun stepLabelKey(step: FirstStep): String = when (step) {
    FirstStep.LANGUAGE -> "lang_title"
    FirstStep.LICENCE -> "lic_title"
    FirstStep.LEGAL -> "step_legal"
    FirstStep.ROOT -> "step_root"
}

@Composable
fun Shell(
    settings: Settings,
    version: String,
    onChange: (Settings) -> Unit,
    onExit: () -> Unit,
) {
    // gsettings is a process spawn. Once per run, never once per recomposition.
    val detected = remember { readSystemMode() }
    // Computed once. A step list recomputed on every change would drop the
    // licence the moment the language is saved.
    val steps = remember { firstSteps(settings) }
    var stepIndex by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var updates by remember { mutableStateOf<List<Update>>(emptyList()) }
    val setupDone = stepIndex >= steps.size

    // Once per launch, after the first run, when the user allows it. The
    // app lives longer than any tool release, so it asks the publishers
    // instead of carrying versions. Only installed tools are looked up.
    val root = RunSession.root
    LaunchedEffect(setupDone, root, settings.checkUpdates) {
        if (setupDone && root != null && settings.checkUpdates) {
            updates = withContext(Dispatchers.IO) { runCatching { Updates.check(root) }.getOrDefault(emptyList()) }
        }
    }

    val lang = langOf(settings.lang) ?: detectLang()
    val theme = ThemeChoice.entries.firstOrNull { it.name == settings.theme } ?: ThemeChoice.SYSTEM
    val direction = if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLang provides lang, LocalLayoutDirection provides direction) {
        GutapkTheme(theme, detected) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                if (stepIndex < steps.size) {
                    val sub = StepProgress(steps.map { t(stepLabelKey(it)) }, stepIndex)
                    val next = { stepIndex += 1 }
                    when (steps[stepIndex]) {
                        FirstStep.LANGUAGE -> LanguageStep(sub) {
                            onChange(settings.copy(lang = it.code))
                            next()
                        }
                        FirstStep.LICENCE -> LicenceScreen(sub, onBack = null, onContinue = next)
                        FirstStep.LEGAL -> LegalScreen(
                            progress = sub,
                            onBack = null,
                            onAccept = {
                                onChange(settings.copy(legalRev = SettingsStore.LEGAL_REV))
                                next()
                            },
                            onDecline = onExit,
                        )
                        FirstStep.ROOT -> RootScreen(
                            progress = sub,
                            initial = settings.root ?: Storage.defaultRoot(),
                            note = null,
                            onBack = null,
                            onDone = {
                                onChange(settings.copy(root = it.toString()))
                                next()
                            },
                        )
                    }
                } else {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            version = version,
                            onSettings = { screen = Screen.SETTINGS },
                            onLicence = { screen = Screen.LICENCE },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            lang = lang,
                            theme = theme,
                            detected = detected,
                            version = version,
                            root = RunSession.root,
                            onChange = onChange,
                            onBack = { screen = Screen.HOME },
                            onRoot = { screen = Screen.ROOT },
                            onDisk = { screen = Screen.DISK },
                            onLicence = { screen = Screen.LICENCE },
                            onLegal = { screen = Screen.LEGAL },
                        )
                        Screen.ROOT -> RootScreen(
                            progress = null,
                            initial = settings.root ?: Storage.defaultRoot(),
                            note = t("root_next_launch"),
                            onBack = { screen = Screen.SETTINGS },
                            onDone = {
                                onChange(settings.copy(root = it.toString()))
                                screen = Screen.SETTINGS
                            },
                        )
                        Screen.DISK -> DiskScreen(RunSession.root, onBack = { screen = Screen.SETTINGS })
                        Screen.LICENCE -> LicenceScreen(null, onBack = { screen = Screen.SETTINGS }, onContinue = null)
                        Screen.LEGAL -> LegalScreen(null, onBack = { screen = Screen.SETTINGS }, onAccept = null, onDecline = null)
                    }
                    if (updates.isNotEmpty() && root != null) {
                        UpdateDialog(root, updates, onClose = { updates = emptyList() })
                    }
                }
            }
        }
    }
}
