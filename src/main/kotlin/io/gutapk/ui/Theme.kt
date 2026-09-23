package io.gutapk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

// GNOME's nine accent colours, in the order its settings panel shows them.
// SYSTEM reads the one GNOME has set.
enum class AccentChoice { SYSTEM, BLUE, TEAL, GREEN, YELLOW, ORANGE, RED, PINK, PURPLE, SLATE }

fun accentOf(name: String?): AccentChoice? =
    AccentChoice.entries.firstOrNull { it != AccentChoice.SYSTEM && it.name.equals(name, ignoreCase = true) }

// libadwaita's own values, from adw-accent-color.c, so a fixed choice is the
// exact colour GNOME paints with that name.
fun accentSeed(accent: AccentChoice): Int? = when (accent) {
    AccentChoice.SYSTEM -> null
    AccentChoice.BLUE -> 0xFF3584E4.toInt()
    AccentChoice.TEAL -> 0xFF2190A4.toInt()
    AccentChoice.GREEN -> 0xFF3A944A.toInt()
    AccentChoice.YELLOW -> 0xFFC88800.toInt()
    AccentChoice.ORANGE -> 0xFFED5B00.toInt()
    AccentChoice.RED -> 0xFFE62D42.toInt()
    AccentChoice.PINK -> 0xFFD56199.toInt()
    AccentChoice.PURPLE -> 0xFF9141AC.toInt()
    AccentChoice.SLATE -> 0xFF6F8396.toInt()
}

// Tonal spot is the variant Android uses for wallpaper colours, so a seed
// gives the same palette the phone would.
internal fun accentScheme(seed: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun c(role: DynamicColor) = Color(role.getArgb(scheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(roles.primary()),
        onPrimary = c(roles.onPrimary()),
        primaryContainer = c(roles.primaryContainer()),
        onPrimaryContainer = c(roles.onPrimaryContainer()),
        inversePrimary = c(roles.inversePrimary()),
        secondary = c(roles.secondary()),
        onSecondary = c(roles.onSecondary()),
        secondaryContainer = c(roles.secondaryContainer()),
        onSecondaryContainer = c(roles.onSecondaryContainer()),
        tertiary = c(roles.tertiary()),
        onTertiary = c(roles.onTertiary()),
        tertiaryContainer = c(roles.tertiaryContainer()),
        onTertiaryContainer = c(roles.onTertiaryContainer()),
        background = c(roles.background()),
        onBackground = c(roles.onBackground()),
        surface = c(roles.surface()),
        onSurface = c(roles.onSurface()),
        surfaceVariant = c(roles.surfaceVariant()),
        onSurfaceVariant = c(roles.onSurfaceVariant()),
        surfaceTint = c(roles.surfaceTint()),
        inverseSurface = c(roles.inverseSurface()),
        inverseOnSurface = c(roles.inverseOnSurface()),
        error = c(roles.error()),
        onError = c(roles.onError()),
        errorContainer = c(roles.errorContainer()),
        onErrorContainer = c(roles.onErrorContainer()),
        outline = c(roles.outline()),
        outlineVariant = c(roles.outlineVariant()),
        scrim = c(roles.scrim()),
        surfaceBright = c(roles.surfaceBright()),
        surfaceDim = c(roles.surfaceDim()),
        surfaceContainer = c(roles.surfaceContainer()),
        surfaceContainerHigh = c(roles.surfaceContainerHigh()),
        surfaceContainerHighest = c(roles.surfaceContainerHighest()),
        surfaceContainerLow = c(roles.surfaceContainerLow()),
        surfaceContainerLowest = c(roles.surfaceContainerLowest()),
    )
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
)

private val GutapkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun GutapkTheme(
    choice: ThemeChoice,
    detected: SystemMode,
    accent: AccentChoice,
    systemAccent: String?,
    content: @Composable () -> Unit,
) {
    val dark = when (choice) {
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
        ThemeChoice.SYSTEM -> when (detected) {
            SystemMode.DARK -> true
            SystemMode.LIGHT -> false
            SystemMode.UNKNOWN -> isSystemInDarkTheme()
        }
    }
    // No seed, when GNOME sets no accent, keeps the reference palette.
    val seed = accentSeed(accent) ?: accentOf(systemAccent)?.let { accentSeed(it) }
    val colours = remember(seed, dark) {
        if (seed != null) accentScheme(seed, dark) else if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(
        colorScheme = colours,
        shapes = GutapkShapes,
        content = content,
    )
}
