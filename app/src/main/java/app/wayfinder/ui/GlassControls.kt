package app.wayfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wayfinder.ButtonNames
import app.wayfinder.ThorButton

/*
 * dev build 17 (WP8): the shared controls — one look per job, used by every page instead of its own copy (SourceRulesTest
 * rule 6 keeps the copies gone): on / off ([GlassSwitchRow]), an action ([GlassButton]), a delete that asks twice
 * ([ConfirmButton]), a controller shortcut ([KeyHint]), Back and the headers ([BackPill], [PageHeader], [ModalHeader]),
 * warnings and empty lists ([GlassNotice], [EmptyState]) and the two text roles ([FieldLabel], [Caption]; the third is
 * [SectionHeader]). A choice is [GlassOption] / [GlassSegmentedControl] (GlassComponents.kt).
 */

// ── on / off ──────────────────────────────────────────────────────────────────

/**
 * dev build 17 (WP8): on / off — the whole row is the button (A, a tap), the switch only shows it. [inset]: a row inside a
 * card (no glass of its own, the focus look without the scale; was Hub's FocusRow); [compact]: Game controls' dense popups
 * (was RemapScreen's Toggle, which wrote On / Off). [details]: more lines under the explanation (SettingCard's "more");
 * [trailing]: before the switch, not focusable (SettingCard's "?", a [KeyHint]). [onFocus]: the row's focus (a card that
 * opens its help while the controller is on it).
 * Build 18 (A0): [confirmOff] = a main switch that asks before it turns off — on, the first press shows these words in
 * place of the explanation (accent) for 3 s, a second press within them turns it off; turning on is always one press. A
 * blank explanation takes no line.
 */
@Composable
fun GlassSwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier,
                   explanation: String? = null, inset: Boolean = false, compact: Boolean = false,
                   focusRequester: FocusRequester? = null, focusId: String? = null,
                   onFocus: (Boolean) -> Unit = {},
                   confirmOff: String? = null,
                   details: @Composable ColumnScope.() -> Unit = {},
                   trailing: @Composable RowScope.() -> Unit = {}) {
    val g = LocalGlass.current
    val onSet by rememberUpdatedState(onChecked)
    val now by rememberUpdatedState(checked)
    val ask by rememberUpdatedState(confirmOff)
    // build 18 (A0): the two presses of a switch that asks before turning off (a new value starts over)
    val confirm = rememberConfirmState(checked)
    // read through a State: the inset row's tap handler is installed once (pointerInput(Unit)), the confirm state follows [checked]
    val set by rememberUpdatedState<(Boolean) -> Unit> { v ->
        if (!v && ask != null) confirm.press { onSet(false) } else { confirm.cancel(); onSet(v) }
    }
    val row: @Composable (Modifier) -> Unit = { pad ->
        Row(Modifier.fillMaxWidth().then(pad), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GlyphText(title, color = g.textPrimary, maxLines = if (compact) 2 else Int.MAX_VALUE,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge)
                if (confirm.asked && confirmOff != null)
                    GlyphText(confirmOff, color = g.accent, style = MaterialTheme.typography.bodySmall)
                else if (!explanation.isNullOrBlank()) Caption(explanation)
                details()
            }
            trailing()
            Spacer(Modifier.width(8.dp))
            // build 18 (A16): the glass colours — Material's defaults drew a dark-purple knob in Dark, a navy one on Your own
            // colours and a heavy outline when off (ComponentRulesTest: no Switch without colors)
            Switch(checked = checked, onCheckedChange = null, modifier = Modifier.focusProperties { canFocus = false },
                colors = SwitchDefaults.colors(checkedThumbColor = g.onAccent, checkedTrackColor = g.accent,
                    checkedBorderColor = Color.Transparent, uncheckedThumbColor = g.textTertiary,
                    uncheckedTrackColor = g.textTertiary.copy(alpha = 0.18f), uncheckedBorderColor = g.textTertiary.copy(alpha = 0.35f)))
        }
    }
    if (!inset) {
        FocusableGlass(onClick = { set(!now) }, modifier = modifier, radius = if (compact) Radii.Chip else Radii.Card,
            focusRequester = focusRequester, focusId = focusId, role = FocusRole.SWITCH) { focused ->
            val tell by rememberUpdatedState(onFocus)
            SideEffect { tell(focused) }
            row(if (compact) Modifier.padding(horizontal = 12.dp, vertical = 4.dp) else Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        return
    }
    // inside a card: one focus target with the focus look drawn inside its edge, no glass of its own (Hub's FocusRow)
    val gate = remember { ReleaseGate() }   // dev build 16 (WP7): a release whose press came here
    val own = remember { FocusRequester() }
    val req = focusRequester ?: own
    val entry = LocalEntryFocus.current
    DisposableEffect(entry, focusId, req) {
        if (entry != null && focusId != null) entry.ids[focusId] = req
        onDispose { if (focusId != null && entry?.ids?.get(focusId) === req) entry.ids.remove(focusId) }
    }
    OfferEntry(req)                         // a page opening on this card opens here
    val tell by rememberUpdatedState(onFocus)
    Box(modifier.fillMaxWidth()
        .focusRequester(req)
        .focusIndicator(RoundedCornerShape(Radii.Control), inset = true)
        .onFocusChanged { FocusTruth.report(req, it.hasFocus); tell(it.isFocused); if (!it.isFocused) gate.armed = false }
        .onKeyEvent { e -> gate.onKey(e) { set(!now) } }
        .focusRole(FocusRole.SWITCH)        // build 18 (A14): the page's button legend
        .focusable()
        .pointerInput(Unit) { detectTapGestures(onTap = { set(!now) }) }) {
        row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

// ── actions ───────────────────────────────────────────────────────────────────

/** dev build 17 (WP8): what a [GlassButton] does, in its label's colour: accent · text · secondary text · danger. */
enum class ButtonKind { PRIMARY, NORMAL, QUIET, DANGER }

/** Build 18 (A0): a disabled control's content (a [GlassButton] that can't act yet). */
internal const val DISABLED_ALPHA = 0.45f

/** dev build 17 (WP8): COMPACT for dense places (Game controls, the quick panel, rows of small actions); REGULAR elsewhere. */
enum class ButtonSize(val padH: Dp, val padV: Dp, val radius: Dp) {
    COMPACT(10.dp, 6.dp, Radii.Chip),
    REGULAR(16.dp, 10.dp, Radii.Control),
}

object GlassButtonDefaults {
    /** The paddings a button adds around its words (a measured header: Game controls' Performance / More). */
    fun chromeWidth(size: ButtonSize): Dp = size.padH * 2
}

private fun ButtonKind.ink(g: GlassColors) = when (this) {
    ButtonKind.PRIMARY -> g.accent
    ButtonKind.NORMAL -> g.textPrimary
    ButtonKind.QUIET -> g.textSecondary
    ButtonKind.DANGER -> g.danger
}

/**
 * dev build 17 (WP8): an action — one look, its kind in the label's colour (a filled accent surface means "selected", never a
 * button). [hint]: the menu job whose button is drawn after the label, in the hand's names (Game controls' "Reset ⓨ"); an
 * empty [text] = icon only ([iconDescription] then says it). Wraps its content unless [modifier] gives it a width (then
 * centred). Replaces RemapScreen's Pill (not selected), Hub's SetupPill, the quick panel's SmallButton and the pages' own
 * FocusableGlass { Text } buttons.
 * Build 18 (A0): [enabled] false = its content at 45 % and a press doesn't run [onClick] — [onDisabledClick] instead, if
 * given (say why it does nothing). It stays focusable unless the caller adds `focusProperties { canFocus = false }`.
 */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.NORMAL,
                size: ButtonSize = ButtonSize.REGULAR, hint: ThorButton? = null, icon: ImageVector? = null,
                iconDescription: String? = null, focusRequester: FocusRequester? = null, focusId: String? = null,
                enabled: Boolean = true, onDisabledClick: (() -> Unit)? = null) {
    val g = LocalGlass.current
    val ink = kind.ink(g)
    val act by rememberUpdatedState(onClick)
    val why by rememberUpdatedState(onDisabledClick)
    val on by rememberUpdatedState(enabled)
    FocusableGlass(onClick = { if (on) act() else why?.invoke() }, modifier = modifier, radius = size.radius,
        focusRequester = focusRequester, focusId = focusId, contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = size.padH, vertical = size.padV).alpha(if (enabled) 1f else DISABLED_ALPHA),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, if (text.isEmpty()) iconDescription else null,
                tint = if (kind == ButtonKind.NORMAL) g.accent else ink, modifier = Modifier.size(18.dp))
            if (text.isNotEmpty()) GlyphText(text, color = ink, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint != null) KeyHint(hint)
        }
    }
}

/** A number changed by steps: [label], then − [value] + ([dec] / [inc] keep it in range). b18h: moved here from
 *  RemapScreen (Game controls' speeds, the gyro, macro timings), unchanged, for Keyboard's Pointer speed too. */
@Composable
fun Stepper(label: String, value: String, dec: () -> Unit, inc: () -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        GlassButton("−", dec, Modifier.width(46.dp), size = ButtonSize.COMPACT)
        Text(value, color = g.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        GlassButton("+", inc, Modifier.width(46.dp), size = ButtonSize.COMPACT)
    }
}

/** dev build 17 (WP8): one [ConfirmButton]'s two presses (hoisted when a key asks too: Game controls' Y). */
class ConfirmState internal constructor() {
    private val two = TwoPress()
    /** The first press came: the button shows its confirm words. */
    var asked by mutableStateOf(false)
        private set
    /** Asked → [act] (and back to the start); else it asks. */
    fun press(act: () -> Unit) {
        if (two.press()) { asked = false; act() } else asked = two.asked
    }
    fun cancel() { two.cancel(); asked = false }
}

/** dev build 17 (WP8): a [ConfirmState] that stops asking after [windowMs] and when it leaves the screen. Review WP8-3:
 *  and when its target changes — [keys] = what the confirmation is for (the selected step, a row's item): a row drawn in
 *  a plain loop takes the next item's place when one is deleted, and must not hand it a pending "Press again". */
@Composable
fun rememberConfirmState(vararg keys: Any?, windowMs: Long = 3000): ConfirmState {
    val s = remember(*keys) { ConfirmState() }
    LaunchedEffect(s, s.asked) { if (s.asked) { kotlinx.coroutines.delay(windowMs); s.cancel() } }
    DisposableEffect(s) { onDispose { s.cancel() } }
    return s
}

/**
 * dev build 17 (WP8): anything that deletes what the user made — two presses: the first shows [confirmText] for 3 s, the
 * second acts. Always the danger colour. [state] hoisted when a key asks too (Game controls' Y); [enabled] false = a press
 * does nothing (nothing to delete). Replaces RemapScreen's ConfirmPill and every page's own confirm flag.
 */
@Composable
fun ConfirmButton(text: String, confirmText: String, onConfirm: () -> Unit, modifier: Modifier = Modifier,
                  size: ButtonSize = ButtonSize.REGULAR, hint: ThorButton? = null, icon: ImageVector? = null,
                  iconDescription: String? = null, enabled: Boolean = true,
                  state: ConfirmState = rememberConfirmState(), focusRequester: FocusRequester? = null) {
    val act by rememberUpdatedState(onConfirm)
    GlassButton(if (state.asked) confirmText else text, { if (enabled) state.press { act() } }, modifier, ButtonKind.DANGER, size,
        hint, if (state.asked && text.isEmpty()) null else icon, iconDescription, focusRequester)
}

// ── a controller shortcut ─────────────────────────────────────────────────────

/**
 * dev build 17 (WP8): a controller shortcut, the same everywhere: the button's name (or PlayStation mark) in a filled accent
 * dot; a capsule when [wide] or the name is longer than 2 characters (a sideways Joy-Con's "Right"). Moved from RemapScreen's
 * Hint (dev build 15, WP2: PlayStation's marks drawn as shapes, not typed in a fallback font).
 * Build 18 (A18, ps-F10): a PlayStation mark is the badge the text beside it draws (its colour on its pale disc, [MarkBadge])
 * — the Back pill's ○ was a white ring on the accent next to the subtitle's red one; letters and words keep the accent chip.
 */
@Composable
fun KeyHint(text: String, wide: Boolean = false) {
    val g = LocalGlass.current
    val mark = text.singleOrNull()?.let { FaceMark.ofSymbol(it) }
    if (mark != null) { MarkBadge(mark, 22.dp); return }
    Box((if (wide || keyHintWide(text)) Modifier.height(24.dp).background(g.accent, RoundedCornerShape(Radii.Chip)).padding(horizontal = 9.dp)
        else Modifier.size(22.dp).background(g.accent, CircleShape)), contentAlignment = Alignment.Center) {
        Text(text, color = g.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** dev build 17 (WP8): the hand's button for that menu job (B = Back: ○ on a DualSense, B on the Thor…). */
@Composable
fun KeyHint(job: ThorButton) = KeyHint(app.wayfinder.Say.menu(job))

// ── headers ───────────────────────────────────────────────────────────────────

/**
 * dev build 17 (WP8): Back, the same on every page and popup: ← (accent), its word, and the hand's Back button (the menu B
 * job: B, ○, a sideways Joy-Con's name). Never a page's entry (it offers no entry focus: a sheet whose header is inside its
 * entry scope never opens on Close).
 */
@Composable
fun BackPill(onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = "Back",
             contentDescription: String? = label, focusRequester: FocusRequester? = null) {
    val g = LocalGlass.current
    CompositionLocalProvider(LocalEntryFocus provides null) {
        FocusableGlass(onClick = onClick, radius = Radii.Row, focusRequester = focusRequester,
            modifier = modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = g.accent, modifier = Modifier.size(20.dp))
                if (label != null) Text(label, color = g.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                KeyHint(ThorButton.B)
            }
        }
    }
}

object BackPillDefaults {
    /** Its paddings, arrow and the arrow's gap (54 dp); a measured header adds the words (+ their 6 dp gap) and the hint. */
    val CHROME: Dp = 14.dp + 20.dp + 6.dp + 14.dp
    /** The Back button's dot (a capsule is measured: [keyHintWide]). */
    val HINT: Dp = 22.dp
    /** Build 18 (A11): its height, as PageHeader lines it up with the title. */
    val HEIGHT: Dp = 44.dp
}

/**
 * dev build 17 (WP8): a page's header — Back on the left, then the title (headlineMedium) and its line. [onBack] = the page's
 * Back press already wrapped (rememberBackPress); [backModifier] reaches the Back pill (Combos: out of reach behind a picker).
 * Used by SubPage (every Hub page), Combos, Games, Test the controller; Game controls keeps its measured header with BackPill.
 * Build 18 (A11): everything top-aligned — the Back pill sits at the same height on every page whatever its subtitle's
 * lines (centred on them, it moved from page to page), centred on the title's first line when that line is the taller;
 * the subtitle at most 2 lines. (A14): the button legend ([ButtonLegend]) before [trailing], hidden when [trailing] is
 * wider than 40 % of the header (Games' search and Import).
 */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
               overline: String? = null, backLabel: String = "Back", backModifier: Modifier = Modifier,
               trailing: @Composable RowScope.() -> Unit = {}) {
    val g = LocalGlass.current
    val density = LocalDensity.current
    val titleStyle = MaterialTheme.typography.headlineMedium
    val pillTop = with(density) {
        if (titleStyle.lineHeight.isSp) ((titleStyle.lineHeight.toDp() - BackPillDefaults.HEIGHT) / 2).coerceAtLeast(0.dp) else 0.dp
    }
    var headerW by remember { mutableIntStateOf(0) }
    var trailW by remember { mutableIntStateOf(0) }
    Row(modifier.onSizeChanged { headerW = it.width }, verticalAlignment = Alignment.Top) {
        BackPill(onBack, Modifier.padding(top = pillTop).then(backModifier), backLabel)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            if (overline != null) Text(overline, color = g.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(title, color = g.textPrimary, style = titleStyle)
            if (subtitle != null) GlyphText(subtitle, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // build 18 (A14, decision H3): the button legend — its ONE call site (no legend = delete this line). Not when
        // [trailing] takes over 40 % of the header, nor in a narrow header (the bottom screen: the title keeps its room)
        if (headerW > 0 && trailW <= headerW * 0.4f && with(density) { headerW.toDp() } >= LEGEND_MIN_HEADER) ButtonLegend(Modifier.padding(start = 14.dp, top = pillTop + 12.dp).width(minOf(LEGEND_WIDTH, with(density) { (headerW * 0.3f).toDp() })))
        Row(Modifier.gapBefore(14.dp).onSizeChanged { trailW = it.width }, verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) { trailing() }
    }
}

/** A leading [gap] only when the content has a width — an empty slot (a header without [PageHeader]'s trailing) takes no room. */
private fun Modifier.gapBefore(gap: Dp): Modifier = layout { m, c ->
    val g = gap.roundToPx()
    val max = if (c.hasBoundedWidth) (c.maxWidth - g).coerceAtLeast(0) else c.maxWidth
    val p = m.measure(c.copy(minWidth = 0, maxWidth = max))
    val lead = if (p.width > 0) g else 0
    layout(p.width + lead, p.height) { p.place(lead, 0) }
}

/**
 * dev build 17 (WP8): a popup's or sheet's header — Close / Back on the LEFT (with the hand's Back button), the title
 * (titleMedium semibold: popups must fit the bottom screen), what's on the right ([trailing]: Reset, Save), and an optional
 * line under it. [status] = a current value after the title, in the accent ("Now: …"): it takes the room left, the title
 * keeps its own; [fillTitle] false: the same without a status ([trailing] takes the room: a search field). [closeFocus]: only
 * for a sheet whose ONLY action is closing (a popup opens on its first content — rule 6; BackPill offers no entry). Replaces
 * RemapScreen's PanelTitle and the "Cancel · B" pills of Combos' pickers and the sheets.
 */
@Composable
fun ModalHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, closeLabel: String = "Close",
                subtitle: String? = null, closeVisible: Boolean = true, closeFocus: FocusRequester? = null,
                status: String? = null, fillTitle: Boolean = true, leading: (@Composable () -> Unit)? = null,
                trailing: @Composable RowScope.() -> Unit = {}) {
    val g = LocalGlass.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (closeVisible) BackPill(onClose, label = closeLabel, focusRequester = closeFocus)
            leading?.invoke()
            val titleStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            if (status == null && fillTitle) GlyphText(title, Modifier.weight(1f), color = g.textPrimary, style = titleStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            else GlyphText(title, color = g.textPrimary, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) GlyphText(status, Modifier.weight(1f), color = g.accent,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing()
        }
        if (subtitle != null) GlyphText(subtitle, color = g.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── notices ───────────────────────────────────────────────────────────────────

/** dev build 17 (WP8): what a [GlassNotice] says — information, a warning, or something broken. */
enum class NoticeKind { INFO, WARN, DANGER }

/**
 * dev build 17 (WP8): a warning or banner — tinted (accent 10 % · amber 22 % dark / 35 % light · danger 20 %), its icon (Info ·
 * WarningAmber · ErrorOutline in the kind's colour), the text in textPrimary; an [action] button, or the whole notice
 * pressable ([onClick]: the Hub's "press to fix" warnings). Replaces ControlsScreen's WarnBox, Game controls' input-layer
 * banner, the gyro popup's red line, the Hub warnings drawn as GlassActionButtons, the tour's consent line.
 */
@Composable
fun GlassNotice(text: String, kind: NoticeKind = NoticeKind.WARN, modifier: Modifier = Modifier, title: String? = null,
                icon: ImageVector? = null, action: String? = null, onAction: (() -> Unit)? = null,
                actionModifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val g = LocalGlass.current
    val ink = when (kind) { NoticeKind.INFO -> g.accent; NoticeKind.WARN -> g.warn; NoticeKind.DANGER -> g.danger }
    val tint = when (kind) {
        NoticeKind.INFO -> g.accent.copy(alpha = 0.10f)
        NoticeKind.WARN -> g.warnWash
        NoticeKind.DANGER -> g.danger.copy(alpha = 0.20f)
    }
    val shape = RoundedCornerShape(Radii.Control)
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().background(tint, shape).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon ?: when (kind) {
                NoticeKind.INFO -> Icons.Rounded.Info; NoticeKind.WARN -> Icons.Rounded.WarningAmber; NoticeKind.DANGER -> Icons.Rounded.ErrorOutline
            }, null, tint = ink, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) GlyphText(title, color = g.textPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                GlyphText(text, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            if (action != null && onAction != null) GlassButton(action, onAction, actionModifier, size = ButtonSize.COMPACT)
        }
    }
    if (onClick != null) FocusableGlass(onClick = onClick, modifier = modifier, radius = Radii.Control) { body() }
    // build 18 (widener review): with an action, the notice is one focus group as wide as itself — its small button at the
    // end lost the D-pad's vertical search to the full-width card below (Down from the row above skipped it); the group,
    // nearest and centred, wins and hands the focus to its button, and Down from the button goes on below as before
    else Box(if (action != null && onAction != null) modifier.focusGroup() else modifier) { body() }
}

/** dev build 17 (WP8): an empty list says so in one place, one way (+ an optional way out). */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val g = LocalGlass.current
    Column(modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlyphText(text, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
        if (action != null && onAction != null) GlassButton(action, onAction, kind = ButtonKind.PRIMARY, size = ButtonSize.COMPACT)
    }
}

// ── text roles ────────────────────────────────────────────────────────────────

/** dev build 17 (WP8): a control's name (above a choice row, beside a group of options) — labelLarge, textSecondary. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    GlyphText(text, modifier, color = LocalGlass.current.textSecondary, style = MaterialTheme.typography.labelLarge)

/** dev build 17 (WP8): an explanation under a control — bodySmall, textTertiary. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    GlyphText(text, modifier, color = LocalGlass.current.textTertiary, style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
