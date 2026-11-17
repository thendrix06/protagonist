package capture.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import capture.presentation.StatusTokens

/** Converts a verified 0xAARRGGBB token into a Compose color. */
fun Long.asColor(): Color = Color(this)

object ControlColors {
    val Surface = StatusTokens.SURFACE_ARGB.asColor()
    val OnSurface = StatusTokens.ON_SURFACE_ARGB.asColor()
    val Selection = StatusTokens.SELECTION_ARGB.asColor()
    val SurfaceRaised = Color(0xFF1B2128)
    val Outline = Color(0xFF3A4654)
    val Primary = Color(0xFF00E5FF)
    val OnPrimary = Color(0xFF001F24)
    val Critical = Color(0xFFE5484D)
    val Warning = Color(0xFFF2B33D)
}

object ControlDimens {
    /** Minimum touch target for every interactive element (scenario G). */
    val MinTouchTarget = 48.dp
    val PrimaryActionHeight = 56.dp
    val SelectionStroke = 4.dp
}

/**
 * Brand-neutral operator theme. Dark by default: the app is used outdoors at night as well as by day, and a dark
 * surface keeps the observation view dominant. The product name is applied by the app module, not here.
 */
@Composable
fun ControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = ControlColors.Primary,
                onPrimary = ControlColors.OnPrimary,
                background = ControlColors.Surface,
                onBackground = ControlColors.OnSurface,
                surface = ControlColors.Surface,
                onSurface = ControlColors.OnSurface,
                surfaceVariant = ControlColors.SurfaceRaised,
                onSurfaceVariant = ControlColors.OnSurface,
                outline = ControlColors.Outline,
                error = ControlColors.Critical,
            ),
        content = content,
    )
}
