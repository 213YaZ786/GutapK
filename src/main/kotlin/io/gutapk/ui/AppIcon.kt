package io.gutapk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import io.gutapk.core.apk.IconArt
import io.gutapk.core.apk.IconColour
import io.gutapk.core.apk.IconGroup
import io.gutapk.core.apk.IconLayer
import io.gutapk.core.apk.IconNode
import io.gutapk.core.apk.IconPath
import io.gutapk.core.apk.VectorArt

private sealed interface Drawn {
    data class Fill(val colour: Color) : Drawn

    class Picture(val vector: ImageVector) : Drawn
}

// An app icon drawn from its vectors. When a path cannot be read, the
// fallback shows instead of half a picture.
@Composable
fun AppIcon(art: IconArt, size: Dp, shape: Shape, fallback: @Composable () -> Unit) {
    val seed = LocalAccentSeed.current ?: REFERENCE_SEED
    val layers = remember(art, seed) {
        runCatching {
            val scheme = SchemeTonalSpot(Hct.fromInt(seed), false, 0.0)
            listOfNotNull(art.background, art.foreground).map { layer ->
                when (layer) {
                    is IconLayer.Colour -> Drawn.Fill(resolve(layer.colour, scheme))
                    is IconLayer.Vector -> Drawn.Picture(imageVector(layer.art, scheme))
                }
            }
        }.getOrNull()
    }
    if (layers.isNullOrEmpty()) {
        fallback()
        return
    }
    // An adaptive layer is 108 dp, the launcher shows the 72 in the middle,
    // so each layer is drawn half again larger and cut by the shape.
    val layerSize = if (art.adaptive) size * 1.5f else size
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        layers.forEach { d ->
            when (d) {
                is Drawn.Fill -> Box(Modifier.requiredSize(layerSize).align(Alignment.Center).background(d.colour))
                is Drawn.Picture -> Image(
                    rememberVectorPainter(d.vector),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(layerSize).align(Alignment.Center),
                )
            }
        }
    }
}

// Android's system_accent and system_neutral shades are tones of the five
// palettes of the scheme built from the seed.
private fun resolve(c: IconColour, scheme: DynamicScheme): Color = when (c) {
    is IconColour.Argb -> Color(c.argb)
    is IconColour.System -> Color(palette(scheme, c.palette).tone(c.tone))
}

private fun palette(scheme: DynamicScheme, index: Int): TonalPalette = when (index) {
    0 -> scheme.neutralPalette
    1 -> scheme.neutralVariantPalette
    2 -> scheme.primaryPalette
    3 -> scheme.secondaryPalette
    else -> scheme.tertiaryPalette
}

private fun imageVector(art: VectorArt, scheme: DynamicScheme): ImageVector {
    val b = ImageVector.Builder(
        defaultWidth = art.viewportWidth.dp,
        defaultHeight = art.viewportHeight.dp,
        viewportWidth = art.viewportWidth,
        viewportHeight = art.viewportHeight,
    )
    fun add(node: IconNode) {
        when (node) {
            is IconGroup -> {
                b.addGroup(
                    rotate = node.rotation,
                    pivotX = node.pivotX,
                    pivotY = node.pivotY,
                    scaleX = node.scaleX,
                    scaleY = node.scaleY,
                    translationX = node.translateX,
                    translationY = node.translateY,
                )
                node.children.forEach { add(it) }
                b.clearGroup()
            }
            is IconPath -> {
                b.addPath(
                    pathData = addPathNodes(node.data),
                    pathFillType = if (node.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = node.fill?.let { SolidColor(resolve(it, scheme)) },
                    fillAlpha = node.fillAlpha,
                    stroke = node.stroke?.let { SolidColor(resolve(it, scheme)) },
                    strokeAlpha = node.strokeAlpha,
                    strokeLineWidth = node.strokeWidth,
                )
            }
        }
    }
    // The root group carries no transform, its children go straight in.
    art.root.children.forEach { add(it) }
    return b.build()
}
