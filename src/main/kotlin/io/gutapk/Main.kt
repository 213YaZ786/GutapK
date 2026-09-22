package io.gutapk

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.gutapk.registry.Features
import io.gutapk.settings.SettingsStore
import io.gutapk.tools.RunLog
import io.gutapk.tools.RunSession
import io.gutapk.ui.Shell
import java.awt.Toolkit
import java.nio.file.Paths
import javax.imageio.ImageIO

// The dock matches a window to its desktop entry by WM_CLASS. AWT derives it
// from the main class, io-gutapk-MainKt, which matches nothing. It is set
// before the first window exists, and a failure only costs the dock match.
// The log is not open yet, so the reason is returned and logged later.
private fun setWmClass(): String? = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
    field.isAccessible = true
    field.set(toolkit, "gutapk")
}.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

// Without a desktop entry installed, the dock falls back to the window's own
// icon. Same file as the AppImage icon, read from the jar.
private fun appIcon(): Painter? = runCatching {
    SettingsStore::class.java.getResourceAsStream("/io/gutapk/gutapk.png")?.use {
        BitmapPainter(ImageIO.read(it).toComposeImageBitmap())
    }
}.getOrNull()

fun main() {
    val wmClassError = setWmClass()
    val icon = appIcon()
    // Set by the build through jvmArgs, so the AppImage and the jar agree.
    val version = System.getProperty("gutapk.version") ?: "dev"
    val initial = SettingsStore.load()
    Features.registerAll()
    // Started before the window so every screen, and the update check at
    // launch, sees the root of this run from the first frame. A first run
    // has no root yet and starts it once the root step is done.
    initial.root?.let { RunSession.start(Paths.get(it), version) }
    wmClassError?.let { RunLog.line("wm class not set, $it") }
    if (icon == null) RunLog.line("window icon not loaded")

    application {
        var settings by remember { mutableStateOf(initial) }

        // Nothing is written under the root before it is known, the log
        // included. On a first run this fires once the root step is done.
        LaunchedEffect(settings.root) {
            settings.root?.let { RunSession.start(Paths.get(it), version) }
        }

        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(onCloseRequest = ::exitApplication, title = "GutapK", state = state, icon = icon) {
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
