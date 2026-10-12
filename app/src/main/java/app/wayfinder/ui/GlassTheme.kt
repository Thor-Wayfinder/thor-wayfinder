package app.wayfinder.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
    /** Build 18 (A23): only for Views without the tokens — Compose reads [GlassColors.positive] (it reads on light glass). */
    val Positive = Color(0xFF30D158)
    // dev build 17 (WP8): danger / warn are the theme's (g.danger / g.warn), the radii [Radii]'s roles
}

/** dev build 17 (WP8): corner radii by role — a page never writes its own number (SourceRulesTest rule 5). */
object Radii {
    val Hairline = 2.dp   // underlines (popup tabs, controller tabs)
    val KeyCap = 10.dp    // a key or key-sized cell: Wayfinder keyboard, Game controls' key picker, the Test page's buttons
    val Chip = 12.dp      // compact buttons and chips in dense places (GlassButton COMPACT, popup tabs, lists in popups)
    val Control = 14.dp   // a control: option / segment, choice row, text field, notice, inner switch row, GlassButton REGULAR
    val Row = 16.dp       // a page-level pill or row: Back pill, GlassListRow, header pill groups, listen / record buttons
    val Card = 18.dp      // a setting card, a quick-panel card, an app row
    val Action = 20.dp    // GlassActionButton tiles
    val Tile = 22.dp      // the Hub's small tiles and strip, the combo capture card
    val Panel = 24.dp     // a panel (GlassPanel / FocusableGlass default), Game controls' drawing panel
    val Modal = 24.dp     // popups and sheets
    val Large = 26.dp     // the Hub's menu cards, the tour card
    val Swatch = 24.dp    // a colour swatch's round glass (half its 48 dp)
    /** A fill drawn [by] inside a rounded element (DirArm's changed fill): its radius. */
    fun inset(outer: Dp, by: Dp = 2.dp) = outer - by
}

/** dev build 17 (WP8): a mark drawn ON a colour that isn't the theme's (a colour swatch's check): black on a light one, else white. */
fun inkOn(argb: Int): Color = if (TokenArgb.luminance(TokenArgb.of(argb)) > 0.5) Color.Black else Color.White

/** dev build 17 (WP8): app icons drawn rounded-square (28 / 40 / 44 dp). */
val AppIconShape = RoundedCornerShape(percent = 25)
/** dev build 17 (WP8): bars and capsules (the Test page's trigger bars, the slider). */
val CapsuleShape = RoundedCornerShape(percent = 50)
/** dev build 17 (WP8): the hex field's colour square (22 dp -> 6, 40 dp -> 11). */
val SwatchPreviewShape = RoundedCornerShape(percent = 27)

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
    // dev build 17 (WP8): what pages wrote as literals (values: TokenArgb)
    val danger: Color,             // what deletes
    val warn: Color,               // warning text and marks
    val surfaceModal: Color,       // a popup's / sheet's card
    val wash: Color,               // Game controls' drawing panel
    val onAccent: Color,           // text and marks on an accent fill
    // build 18 (A1 / A3)
    val focusRing: Color,          // the focus ring, readable on a selected (accent) fill and on the card
    val optionWell: Color,         // an unselected option's opaque base under its glass
    // build 18 (A23)
    val positive: Color,           // what went right ("All set", "working ✓"): a green that reads on the card
)

// dev build 17 (WP8): the derived tokens — they only follow light / dark ("Your own colours" sets [GlassColors.dark] from its
// background, so they follow it too)
/** The dim behind popups and sheets. */
val GlassColors.modalDim: Color get() = Color(TokenArgb.MODAL_DIM)
/** Keyboard overlay and input deck base. */
val GlassColors.surfaceOverlay: Color get() = if (dark) Color(0x990A0C14) else Color(0x99E9ECF4)
/** The IME's base; the guide's "couldn't load" screens. */
val GlassColors.surfaceSolid: Color get() = if (dark) Color(0xF20A0C14) else Color(0xF2E9ECF4)
/** Keyboard bar buttons, deck chips (unselected). */
val GlassColors.fillSubtle: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0x66FFFFFF)
/** Keyboard preview well, deck wells, the deck guide's notes box. */
val GlassColors.fillWell: Color get() = if (dark) Color(0x26FFFFFF) else Color(0x80FFFFFF)
/** Keyboard popup choices, the deck's small keys / stick-mouse button off. */
val GlassColors.fillChip: Color get() = if (dark) Color(0x33FFFFFF) else Color(0x99FFFFFF)
/** A key's face (the keyboard, the deck) — [functional]: the dimmer keys (Shift, Enter, the bar's). */
fun GlassColors.keyFill(functional: Boolean): Brush = Brush.verticalGradient(
    if (dark) (if (functional) listOf(Color(0x1FFFFFFF), Color(0x14FFFFFF)) else listOf(Color(0x40FFFFFF), Color(0x26FFFFFF)))
    else (if (functional) listOf(Color(0x66FFFFFF), Color(0x40FFFFFF)) else listOf(Color(0xE6FFFFFF), Color(0xB3FFFFFF))))
/** A key held down (the deck). */
val GlassColors.keyPressed: Brush get() = Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x40FFFFFF)))
/** The keyboard's key press flash. */
val GlassColors.pressVeil: Color get() = Color(0x33FFFFFF)
/** A sunk well (Game controls' key picker, the gyro's live box). */
val GlassColors.recess: Color get() = Color.Black.copy(alpha = 0.035f)
/** A warning notice's tint: the bright amber, whatever [GlassColors.warn] is for text. */
internal val GlassColors.warnWash: Color get() = Color(TokenArgb.WARN).copy(alpha = if (dark) 0.22f else 0.35f)

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

/** [this] with [other] mixed in by [amount] (0–1). */
private fun Color.compositeOverSelf(other: Color, amount: Float) = Color(
    red + (other.red - red) * amount, green + (other.green - green) * amount, blue + (other.blue - blue) * amount, alpha)

/** 1.4 (GitHub #66): for fixed-size surfaces (overlays) — the font size follows Android's setting up to 1.1×. */
@Composable
fun CappedFontScale(max: Float = 1.1f, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    if (d.fontScale <= max) content()
    else CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
        androidx.compose.ui.unit.Density(d.density, max), content = content)
}

/** dev build 17 (WP8): "Your own colours" (AppSettings.customColours): background, cards, text, accent (ARGB). */
class CustomColours(val bg: Int, val card: Int, val text: Int, val accent: Int)

/** dev build 17 (WP8): the theme's colours from its three inputs — the composable theme and (WP10) the overlays read this
 *  one answer. [custom] = "Your own colours" (null: off). */
fun glassColorsFor(dark: Boolean, black: Boolean, custom: CustomColours?): GlassColors {
    val themed = if (black) {
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
            danger = Color(TokenArgb.danger(true)), warn = Color(TokenArgb.warn(true)),
            surfaceModal = Color(TokenArgb.surfaceModal(true)), wash = Color(TokenArgb.wash(true)),
            onAccent = Color(TokenArgb.onAccent(0xFF3D9BFF)),
            focusRing = Color(TokenArgb.focusRing(true, 0xFF3D9BFF, 0xFFFFFFFF)), optionWell = Color(TokenArgb.optionWell(true)),
            positive = Color(TokenArgb.positive(true)),
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
            danger = Color(TokenArgb.danger(true)), warn = Color(TokenArgb.warn(true)),
            surfaceModal = Color(TokenArgb.surfaceModal(true)), wash = Color(TokenArgb.wash(true)),
            onAccent = Color(TokenArgb.onAccent(0xFF3D9BFF)),
            focusRing = Color(TokenArgb.focusRing(true, 0xFF3D9BFF, 0xFFFFFFFF)), optionWell = Color(TokenArgb.optionWell(true)),
            positive = Color(TokenArgb.positive(true)),
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
            // build 18 (A4): the rim 50 % stronger on light glass (was F2 / 4D) — cards keep an edge over a white app
            rimTop = Color(0xFFFFFFFF),
            rimBottom = Color(0x73FFFFFF),
            textPrimary = Color(0xFF0A0C14),
            textSecondary = Color(0xC7000000),
            textTertiary = Color(0x8A000000),
            accent = Glass.Accent, accent2 = Glass.Accent2,
            danger = Color(TokenArgb.danger(false)), warn = Color(TokenArgb.warn(false)),
            surfaceModal = Color(TokenArgb.surfaceModal(false)), wash = Color(TokenArgb.wash(false)),
            onAccent = Color(TokenArgb.onAccent(0xFF0A84FF)),
            focusRing = Color(TokenArgb.focusRing(false, 0xFF0A84FF, 0xFF0A0C14)), optionWell = Color(TokenArgb.optionWell(false)),
            positive = Color(TokenArgb.positive(false)),
        )
    }
    // 1.4 (GitHub #24): your own colours — flat: solid background and cards, no aurora
    if (custom == null) return themed
    val bg = Color(custom.bg); val card = Color(custom.card); val text = Color(custom.text); val acc = Color(custom.accent)
    val lightBg = bg.luminance() > 0.5f
    val cardArgb = TokenArgb.of(custom.card)
    return themed.copy(
        dark = !lightBg, base = bg, blobs = listOf(bg), blobAlpha = 0f,
        scrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
        panelFill = Brush.verticalGradient(listOf(card, card)),
        panelFillFocused = Brush.verticalGradient(listOf(card.copy(alpha = 1f).compositeOverSelf(text, 0.10f), card.compositeOverSelf(text, 0.06f))),
        panel = card, rimTop = text.copy(alpha = 0.22f), rimBottom = text.copy(alpha = 0.08f),
        // build 18 (A24, MORE-D01): the secondary / tertiary text no fainter than reads on their card (4.5 / 3 : 1) — at a
        // fixed 0.82 / 0.58 the captions vanished on a low-contrast pair (blue text on cyan cards)
        textPrimary = text,
        textSecondary = text.copy(alpha = TokenArgb.minAlpha(TokenArgb.of(custom.text), cardArgb, 4.5, 0.82).toFloat()),
        textTertiary = text.copy(alpha = TokenArgb.minAlpha(TokenArgb.of(custom.text), cardArgb, 3.0, 0.58).toFloat()),
        accent = acc, accent2 = acc.compositeOverSelf(Color(0xFF8A7BFF), 0.35f),
        // dev build 17 (WP8): red and amber follow the background's light or dark; the popup card and the wash are the card's
        danger = Color(TokenArgb.danger(!lightBg)), warn = Color(TokenArgb.warn(!lightBg)),
        surfaceModal = Color(TokenArgb.surfaceModal(!lightBg, cardArgb)), wash = Color(TokenArgb.wash(!lightBg, cardArgb)),
        onAccent = Color(TokenArgb.onAccent(TokenArgb.of(custom.accent))),
        // build 18 (A1 / A3): the ring reads on their accent and card; the unselected option's base is their card
        focusRing = Color(TokenArgb.focusRing(!lightBg, TokenArgb.of(custom.accent), TokenArgb.of(custom.text), cardArgb)),
        optionWell = Color(TokenArgb.optionWell(!lightBg, cardArgb)),
        positive = Color(TokenArgb.positive(!lightBg, cardArgb)),       // build 18 (A23): the green that reads on their card
    )
}

/** dev build 17 (WP10, critique K5): the theme outside Compose — the overlays (FocusCue), the deck and the keyboard read
 *  the same answer [ThorGlassTheme] provides ([glassColorsFor]). */
object GlassTokens {
    /** Dark? The theme setting, else the system's night mode on [ctx] (a display context for an overlay). */
    fun isDark(ctx: android.content.Context): Boolean = when (app.wayfinder.AppSettings.themeMode) {
        app.wayfinder.ThemeMode.DARK, app.wayfinder.ThemeMode.BLACK -> true
        app.wayfinder.ThemeMode.LIGHT -> false
        app.wayfinder.ThemeMode.SYSTEM -> (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    /** The colours a Wayfinder window on [ctx]'s display draws with — black and "Your own colours" included. */
    fun resolve(ctx: android.content.Context): GlassColors = isDark(ctx).let { dark ->
        glassColorsFor(dark, dark && app.wayfinder.AppSettings.themeMode == app.wayfinder.ThemeMode.BLACK,
            app.wayfinder.AppSettings.customColours())
    }
}

@Composable
fun ThorGlassTheme(dark: Boolean, content: @Composable () -> Unit) {
    // dev build 17 (WP8): one builder (glassColorsFor) — WP10's overlays read the same answer
    val colors = glassColorsFor(dark, dark && app.wayfinder.AppSettings.themeMode == app.wayfinder.ThemeMode.BLACK,
        app.wayfinder.AppSettings.customColours())
    val scheme = if (colors.dark)
        darkColorScheme(primary = colors.accent, background = colors.base, surface = Color(0xFF161B3A))
    else
        lightColorScheme(primary = colors.accent, background = colors.base, surface = Color(0xFFEAF0FF))

    CompositionLocalProvider(LocalGlass provides colors) {
        MaterialTheme(colorScheme = scheme, typography = GlassType, content = content)
    }
}
