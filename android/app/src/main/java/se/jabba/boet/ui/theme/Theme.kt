package se.jabba.boet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = MossDeep,
    onPrimary = WarmWhite,
    primaryContainer = Leaf,
    onPrimaryContainer = Charcoal,
    secondary = Sage,
    onSecondary = Charcoal,
    secondaryContainer = Leaf,
    onSecondaryContainer = Charcoal,
    background = WarmWhite,
    onBackground = Charcoal,
    surface = WarmWhite,
    onSurface = Charcoal,
    surfaceVariant = Stone,
    onSurfaceVariant = CharcoalMuted,
    outline = Stone,
)

// Used for Shopping Mode and dark theme — Night Base / Night Surface.
private val DarkColors = darkColorScheme(
    primary = Sage,
    onPrimary = NightBase,
    primaryContainer = MossDeep,
    onPrimaryContainer = WarmWhite,
    secondary = Sage,
    onSecondary = NightBase,
    background = NightBase,
    onBackground = WarmWhite,
    surface = NightSurface,
    onSurface = WarmWhite,
    surfaceVariant = NightSurface,
    onSurfaceVariant = Stone,
    outline = MossDeep,
)

private val BoetTypography = Typography(
    headlineSmall = BoetType.headline,
    titleMedium = BoetType.title,
    bodyLarge = BoetType.body,
    labelMedium = BoetType.label,
)

@Composable
fun BoetTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Boet is a deliberately light, warm "daylight" app; Shopping Mode is the one
    // dark counterpoint (forceDark). We don't follow the system into dark mode, so
    // the brand's light surfaces stay consistent and on-palette.
    val dark = forceDark
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BoetTypography,
        content = content,
    )
}
