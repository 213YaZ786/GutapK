package io.gutapk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.gutapk.core.QrCode

// Black on white whatever the theme, with the four module quiet zone the
// standard asks for: phone cameras read it best that way.
@Composable
fun QrImage(code: QrCode, side: Dp = 240.dp) {
    Canvas(Modifier.size(side)) {
        val cells = code.size + 8
        val cell = size.minDimension / cells
        drawRect(Color.White, Offset.Zero, Size(cell * cells, cell * cells))
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code.isDark(x, y)) drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell, cell))
        }
    }
}
