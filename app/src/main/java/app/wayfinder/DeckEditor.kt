package app.wayfinder

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wayfinder.deck.CustomKey
import app.wayfinder.deck.DeckSettings
import app.wayfinder.deck.PointerSpeed
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.glassSurface
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader

/**
 * "My pad" editor (Hub → Keyboard): the user's own input-deck buttons. Each one either
 * types a text snippet or presses a key combo written the natural way ("Ctrl+S", "F5",
 * "Alt+Enter"). Lives in the Hub because typing needs a focusable window — the deck
 * itself never takes focus from the game.
 */
@Composable
fun DeckEditorSection() {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    DeckSettings.init(ctx)

    // dev build 16 (theme F): its combo as the controller in hand says it
    SectionHeader("Keyboard & mouse for games" + (Say.combo(ThorAction.KEYBOARD, app.wayfinder.ui.currentLook())?.let { " · $it in any game" } ?: ""))
    SettingCard(
        "Each app's pad",
        if (DeckSettings.autoPick) "RetroArch gets the emulator keys, video apps the video pad, streaming apps the trackpad. Your own choice in an app is remembered."
        else "Always opens on the last pad you used",
        checked = DeckSettings.autoPick, onChecked = { DeckSettings.setAutoPickOn(it) },
    )
    // b18h (GitHub #49 "a tad too sensitive", #99): the trackpad's Pointer speed, Mouse and Touch — 5 = the speed it always
    // had; the deck has the same stepper beside its pad. Not Direct (the pad is the game's screen, 1:1)
    SettingCard(NavCards.KB_SPEED, "How far the pointer goes for a finger on the trackpad, in Mouse and Touch") {
        app.wayfinder.ui.Stepper(Say.POINTER_SCALE, "${DeckSettings.pointerSpeed}",
            { DeckSettings.choosePointerSpeed(PointerSpeed.step(DeckSettings.pointerSpeed, faster = false)) },
            { DeckSettings.choosePointerSpeed(PointerSpeed.step(DeckSettings.pointerSpeed, faster = true)) })
    }

    SectionHeader("Your own buttons · My pad")
    GlassPanel(Modifier.fillMaxWidth(), radius = app.wayfinder.ui.Radii.Card) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Buttons you make for Keyboard & mouse: each one types a text you save, or presses a key " +
                "combo a PC game or app needs — Ctrl+Z, F5 quick save, Alt+Enter…",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (DeckSettings.custom.isEmpty()) {
                app.wayfinder.ui.Caption("Nothing yet — start from an example:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // (Ctrl = META_CTRL_ON | META_CTRL_LEFT_ON; Alt = META_ALT_ON | META_ALT_LEFT_ON.)
                    for (ex in listOf(CustomKey("Undo", code = android.view.KeyEvent.KEYCODE_Z, meta = 0x3000),
                        CustomKey("Quick save", code = android.view.KeyEvent.KEYCODE_F5),
                        CustomKey("Full screen", code = android.view.KeyEvent.KEYCODE_ENTER, meta = 0x12),
                        CustomKey("gg", text = "gg"))) {
                        app.wayfinder.ui.GlassButton("+ ${ex.label}", { DeckSettings.setCustomKeys(DeckSettings.custom + ex) },
                            kind = app.wayfinder.ui.ButtonKind.PRIMARY, size = app.wayfinder.ui.ButtonSize.COMPACT)
                    }
                }
            }
            for ((i, k) in DeckSettings.custom.withIndex()) CustomKeyRow(k) {
                DeckSettings.setCustomKeys(DeckSettings.custom.filterIndexed { j, _ -> j != i })
            }
            AddCustomKey { DeckSettings.setCustomKeys(DeckSettings.custom + it) }
        }
    }
}

@Composable
private fun CustomKeyRow(k: CustomKey, onDelete: () -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(k.label, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(if (k.text != null) "Types: ${k.text}" else "Presses: ${comboLabel(k.code, k.meta)}",
                color = g.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        // dev build 17 (WP8): deletes a key the user made — two presses
        // review WP8-3: the confirmation is this key's (a plain loop: one deleted, the next takes its place — not armed)
        app.wayfinder.ui.ConfirmButton("", "Press again to delete", onDelete, size = app.wayfinder.ui.ButtonSize.COMPACT,
            icon = Icons.Rounded.Delete, iconDescription = "Delete", state = app.wayfinder.ui.rememberConfirmState(k))
    }
}

@Composable
private fun AddCustomKey(onAdd: (CustomKey) -> Unit) {
    val g = LocalGlass.current
    var kind by remember { mutableIntStateOf(0) }   // 0 = text, 1 = keys
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassSegmentedControl(listOf("Types text", "Presses keys"), kind, Modifier.fillMaxWidth()) { kind = it; error = null }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        EditField("Button name", label, Modifier.weight(1f)) { label = it }
        EditField(if (kind == 0) "Text to type" else "Keys, e.g. Ctrl+S or F5", value, Modifier.weight(2f)) { value = it }
        app.wayfinder.ui.GlassButton("Add", {
            val name = label.trim().ifEmpty { value.trim().take(12) }
            if (value.isBlank()) { error = "Type what the button should do"; return@GlassButton }
            val key = if (kind == 0) CustomKey(name, text = value) else parseCombo(value)?.let { (code, meta) ->
                CustomKey(name, code = code, meta = meta)
            }
            if (key == null) { error = "Didn't recognise “$value” — try Ctrl+S, Alt+Enter, F5, Esc"; return@GlassButton }
            onAdd(key); label = ""; value = ""; error = null
        }, icon = Icons.Rounded.Add)
    }
    error?.let { Text(it, color = g.danger, style = MaterialTheme.typography.bodySmall) }
}

/** A text field the controller can reach: the D-pad passes over it, A starts typing (QA2 — it was an EditText the
 *  D-pad skipped). */
@Composable
private fun EditField(hint: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val g = LocalGlass.current
    app.wayfinder.ui.ControllerTextField(value, onChange, modifier,
        textStyle = androidx.compose.ui.text.TextStyle(color = g.textPrimary, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)),
        placeholder = hint, surface = Modifier.glassSurface(g, androidx.compose.foundation.shape.RoundedCornerShape(app.wayfinder.ui.Radii.Control), raised = false),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp))
}

private val MOD_WORDS = mapOf(
    "ctrl" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON), "control" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    "alt" to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
    "shift" to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
    "win" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON), "meta" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
    "cmd" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
)

private val KEY_WORDS = mapOf(
    "esc" to KeyEvent.KEYCODE_ESCAPE, "escape" to KeyEvent.KEYCODE_ESCAPE, "enter" to KeyEvent.KEYCODE_ENTER,
    "return" to KeyEvent.KEYCODE_ENTER, "tab" to KeyEvent.KEYCODE_TAB, "space" to KeyEvent.KEYCODE_SPACE,
    "backspace" to KeyEvent.KEYCODE_DEL, "del" to KeyEvent.KEYCODE_FORWARD_DEL, "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
    "right" to KeyEvent.KEYCODE_DPAD_RIGHT, "home" to KeyEvent.KEYCODE_MOVE_HOME, "end" to KeyEvent.KEYCODE_MOVE_END,
    "pgup" to KeyEvent.KEYCODE_PAGE_UP, "pageup" to KeyEvent.KEYCODE_PAGE_UP, "pgdn" to KeyEvent.KEYCODE_PAGE_DOWN,
    "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN, "ins" to KeyEvent.KEYCODE_INSERT, "insert" to KeyEvent.KEYCODE_INSERT,
    "-" to KeyEvent.KEYCODE_MINUS, "=" to KeyEvent.KEYCODE_EQUALS, "[" to KeyEvent.KEYCODE_LEFT_BRACKET,
    "]" to KeyEvent.KEYCODE_RIGHT_BRACKET, ";" to KeyEvent.KEYCODE_SEMICOLON, "'" to KeyEvent.KEYCODE_APOSTROPHE,
    "," to KeyEvent.KEYCODE_COMMA, "." to KeyEvent.KEYCODE_PERIOD, "/" to KeyEvent.KEYCODE_SLASH, "`" to KeyEvent.KEYCODE_GRAVE,
)

/** "Ctrl+Shift+S" → (KEYCODE_S, ctrl|shift). Null if the key isn't recognised. */
fun parseCombo(s: String): Pair<Int, Int>? {
    val parts = s.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    var meta = 0
    for (p in parts.dropLast(1)) meta = meta or (MOD_WORDS[p.lowercase()] ?: return null)
    val key = parts.last().lowercase()
    val code = KEY_WORDS[key]
        ?: Regex("f([1-9]|1[0-2])").matchEntire(key)?.let { KeyEvent.KEYCODE_F1 + it.groupValues[1].toInt() - 1 }
        ?: if (key.length == 1 && key[0].isLetterOrDigit()) KeyEvent.keyCodeFromString("KEYCODE_${key.uppercase()}") else null
    return if (code == null || code == KeyEvent.KEYCODE_UNKNOWN) null else code to meta
}

fun comboLabel(code: Int, meta: Int): String = buildList {
    if (meta and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
    if (meta and KeyEvent.META_ALT_ON != 0) add("Alt")
    if (meta and KeyEvent.META_SHIFT_ON != 0) add("Shift")
    if (meta and KeyEvent.META_META_ON != 0) add("Win")
    add(KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() })
}.joinToString("+")
