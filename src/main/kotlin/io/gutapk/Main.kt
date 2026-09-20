package io.gutapk

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.gutapk.ui.Shell

fun main() = application {
    val state = rememberWindowState(size = DpSize(1100.dp, 720.dp))
    Window(onCloseRequest = ::exitApplication, title = "GutapK", state = state) {
        Shell()
    }
}
