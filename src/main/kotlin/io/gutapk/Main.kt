package io.gutapk

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.gutapk.settings.SettingsStore
import io.gutapk.tools.RunLog
import io.gutapk.tools.RunSession
import io.gutapk.ui.Shell
import java.nio.file.Paths

fun main() {
    // Set by the build through jvmArgs, so the AppImage and the jar agree.
    val version = System.getProperty("gutapk.version") ?: "dev"
    val initial = SettingsStore.load()

    application {
        var settings by remember { mutableStateOf(initial) }

        // Nothing is written under the root before it is known, the log
        // included. On a first run this fires once the root step is done.
        LaunchedEffect(settings.root) {
            settings.root?.let { RunSession.start(Paths.get(it), version) }
        }

        val state = rememberWindowState(size = DpSize(1100.dp, 720.dp))
        Window(onCloseRequest = ::exitApplication, title = "GutapK", state = state) {
            Shell(
                settings = settings,
                version = version,
                onChange = { next ->
                    settings = next
                    runCatching { SettingsStore.save(next) }
                        .onFailure { RunLog.line("settings not saved: ${it.message}") }
                },
                onExit = ::exitApplication,
            )
        }
    }
}
