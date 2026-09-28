package app.wayfinder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Thor Wayfinder "glass" design system. Glass only reads as glass when there is
 * something colourful BEHIND it, so the look is built from:
 *   - an "aurora" backdrop: large saturated colour blobs drifting slowly across a
 *     virtual canvas that spans BOTH screens (see [GlassScreen]);
 *   - genuinely translucent panels that let the aurora through, with a faint
 *     top-lit frost gradient;
 *   - a specular rim: a diagonal hairline, bright at the top-left, fading out;
 *   - a soft accent halo on the focused element.
 * One source of truth for tokens.
 */
object Glass {
    val Accent = Color(0xFF0A84FF)        // iOS system blue
    val Accent2 = Color(0xFF5E5CE6)       // iOS indigo (for gradients/glow)
    val Positive = Color(0xFF30D158)
    val Warn = Color(0xFFFFD60A)
    val Danger = Color(0xFFFF453A)

    val CardRadius = 24.dp
    val PanelRadius = 30.dp
    val ButtonRadius = 20.dp
}

data class GlassColors(
    val dark: Boolean,
    val base: Color,               // backdrop base under the aurora
    val blobs: List<Color>,        // aurora colours
    val blobAlpha: Float,
    val scrim: Brush,              // gentle legibility wash over the aurora
    val panelFill: Brush,          // translucent frost, lit from the top
    val panelFillFocused: Brush,   // brighter frost for focused/raised
    val panel: Color,              // flat fallback (dots, etc.)
    val rimTop: Color,             // specular edge, top-left
    val rimBottom: Color,          // specular edge, bottom-right
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accent2: Color,
)

val LocalGlass = staticCompositionLocalOf<GlassColors> { error("GlassColors not provided") }

/**
 * True when the window behind us is being blurred live by the system (real glass
 * over the user's own background). [GlassScreen] then draws only a legibility tint
 * instead of the opaque aurora.
 */
val LocalRealGlass = androidx.compose.runtime.compositionLocalOf { false }

private val GlassType = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun ThorGlassTheme(dark: Boolean, content: @Composable () -> Unit) {
    val black = dark && app.wayfinder.AppSettings.themeMode == app.wayfinder.ThemeMode.BLACK
    val colors = if (black) {
        // 1.3: pure black (OLED) — no aurora, frost a touch lighter so the panels still read
        GlassColors(
            dark = true,
            base = Color.Black,
            blobs = listOf(Color.Black),
            blobAlpha = 0f,
            scrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
            panelFill = Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x14FFFFFF))),
            panelFillFocused = Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x24FFFFFF))),
            panel = Color(0x1FFFFFFF),
            rimTop = Color(0x66FFFFFF),
            rimBottom = Color(0x1FFFFFFF),
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xD1FFFFFF),
            textTertiary = Color(0x94FFFFFF),
            accent = Color(0xFF3D9BFF), accent2 = Color(0xFF8A7BFF),
        )
    } else if (dark) {
        GlassColors(
            dark = true,
            base = Color(0xFF06070F),
            blobs = listOf(
                Color(0xFF2F5BFF), // electric blue
                Color(0xFF8A3FFC), // violet
                Color(0xFFFF3D8B), // pink
                Color(0xFF00C2B8), // teal
                Color(0xFFFF8A3D), // amber
            ),
            blobAlpha = 0.60f,
            scrim = Brush.verticalGradient(listOf(Color(0x14000000), Color(0x40000000))),
            panelFill = Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x0FFFFFFF))),
            panelFillFocused = Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1FFFFFFF))),
            panel = Color(0x1FFFFFFF),
            rimTop = Color(0x99FFFFFF),
            rimBottom = Color(0x29FFFFFF),
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xD1FFFFFF),
            textTertiary = Color(0x94FFFFFF),
            accent = Color(0xFF3D9BFF), accent2 = Color(0xFF8A7BFF),
        )
    } else {
        GlassColors(
            dark = false,
            base = Color(0xFFF3F4FA),
            blobs = listOf(
                Color(0xFF4F86FF), // blue
                Color(0xFFB07CFF), // lilac
                Color(0xFFFF7EB6), // rose
                Color(0xFF3FDCCB), // mint
                Color(0xFFFFB45C), // apricot
            ),
            blobAlpha = 0.72f,
            scrim = Brush.verticalGradient(listOf(Color(0x0AFFFFFF), Color(0x1FFFFFFF))),
            panelFill = Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x38FFFFFF))),
            panelFillFocused = Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x73FFFFFF))),
            panel = Color(0x73FFFFFF),
            rimTop = Color(0xF2FFFFFF),
            rimBottom = Color(0x4DFFFFFF),
            textPrimary = Color(0xFF0A0C14),
            textSecondary = Color(0xC7000000),
            textTertiary = Color(0x8A000000),
            accent = Glass.Accent, accent2 = Glass.Accent2,
        )
    }
    val scheme = if (dark)
        darkColorScheme(primary = colors.accent, background = colors.base, surface = Color(0xFF161B3A))
    else
        lightColorScheme(primary = colors.accent, background = colors.base, surface = Color(0xFFEAF0FF))

    CompositionLocalProvider(LocalGlass provides colors) {
        MaterialTheme(colorScheme = scheme, typography = GlassType, content = content)
    }
}
