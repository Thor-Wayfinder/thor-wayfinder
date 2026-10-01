package app.wayfinder

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.wayfinder.keyboard.ALL_LAYOUTS
import app.wayfinder.keyboard.KbLayout
import app.wayfinder.keyboard.KeyboardControlsLegend
import app.wayfinder.keyboard.KeyboardPlacement
import app.wayfinder.keyboard.KeyboardSettings
import app.wayfinder.keyboard.ThorKeyboardService
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassActionButton
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.SectionHeader

/** Is the Thor Keyboard switched on in Android's keyboard list / the one in use? */
enum class KeyboardState { OFF, ENABLED, ACTIVE }

fun keyboardState(ctx: Context): KeyboardState {
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    val enabled = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
    val current = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    return when {
        current.startsWith(ctx.packageName + "/") -> KeyboardState.ACTIVE
        enabled -> KeyboardState.ENABLED
        else -> KeyboardState.OFF
    }
}

fun keyboardSummary(ctx: Context): String = when (keyboardState(ctx)) {
    KeyboardState.OFF -> "Press to turn on"
    KeyboardState.ENABLED -> "Ready · not in use"
    KeyboardState.ACTIVE -> "On · " + KeyboardSettings.layoutIds.size.let { if (it == 1) "1 language" else "$it languages" }
}

/**
 * Keyboard page. Setup is two taps and Android's own (it insists the user OKs any
 * new keyboard, since a keyboard sees what you type — no app may skip that): switch
 * it on in the list, then pick it. Gboard can stay installed; nothing to disable.
 */
@Composable
fun KeyboardPage(myDisplayId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    KeyboardSettings.init(ctx)
    // Re-read the state whenever we come back from Android's keyboard screens.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val state = remember(tick) { keyboardState(ctx) }

    SubPage(myDisplayId, "Keyboard", "Type with touch or the controller — on the other screen or the same one", onBack) {
        when (state) {
            KeyboardState.OFF -> GlassActionButton(
                "Turn on Wayfinder Keyboard", Icons.Rounded.Keyboard, Modifier.fillMaxWidth(),
                subtitle = "One press — your old keyboard stays available",
            ) { Setup.useKeyboard(ctx); ForegroundAppService.later(1500) { tick++ } }
            KeyboardState.ENABLED -> GlassActionButton(
                "Use Wayfinder Keyboard", Icons.Rounded.SwapHoriz, Modifier.fillMaxWidth(),
                subtitle = "One press — your old keyboard stays available",
            ) { Setup.useKeyboard(ctx); ForegroundAppService.later(1500) { tick++ } }
            KeyboardState.ACTIVE -> GlassActionButton(
                "Wayfinder Keyboard is on", Icons.Rounded.CheckCircle, Modifier.fillMaxWidth(),
                subtitle = "Press to switch keyboards",
            ) { ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }

        SettingCard(
            "Where it opens",
            if (KeyboardSettings.placement == KeyboardPlacement.OTHER_SCREEN)
                "With a text box on the top screen, the keyboard fills the bottom screen, so the game keeps the whole top one"
            else "Always at the bottom of the screen you're typing on, like a phone",
        ) {
            GlassSegmentedControl(listOf("Bottom screen", "Same screen"), KeyboardSettings.placement.ordinal, Modifier.fillMaxWidth()) {
                KeyboardSettings.setPlacementMode(KeyboardPlacement.values()[it])
            }
        }

        // 1.3 (GitHub #11)
        SettingCard("The D-pad picks the keys",
            if (KeyboardSettings.dpadKeys) "On: the D-pad moves between keys, the left stick moves the text cursor"
            else "Off: the left stick picks keys, the D-pad moves the text cursor",
            checked = KeyboardSettings.dpadKeys, onChecked = { KeyboardSettings.chooseDpadKeys(it) })

        SectionHeader("Languages · ${KeyboardSettings.layoutIds.size} on — the globe key switches")
        // On first, in their switching order; then the rest.
        val on = KeyboardSettings.layoutIds.mapNotNull { id -> ALL_LAYOUTS.firstOrNull { it.id == id } }
        val off = ALL_LAYOUTS.filter { it.id !in KeyboardSettings.layoutIds }
        // 1.4: your languages; the others behind one row (like "Add a combo")
        for (row in on.chunked(3)) LanguageRow(row)
        if (off.isNotEmpty()) MoreOptions("${off.size} more", title = "Add a language") { for (row in off.chunked(3)) LanguageRow(row) }

        // 1.4
        MoreOptions("the controller's keys, Keyboard & mouse: my pad") {
        SectionHeader("With the controller — also on the keyboard: the ? key (or click the left stick)")
        GlassPanel(Modifier.fillMaxWidth(), radius = 18.dp) {
            KeyboardControlsLegend(Modifier.fillMaxWidth().padding(16.dp).height(300.dp), big = false)
        }

        DeckEditorSection()
        }
    }
}

@Composable
private fun LanguageRow(row: List<KbLayout>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (l in row) LanguageTile(l, Modifier.weight(1f))
        repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun LanguageTile(l: KbLayout, modifier: Modifier) {
    val g = LocalGlass.current
    val checked = l.id in KeyboardSettings.layoutIds
    FocusableGlass(onClick = {
        KeyboardSettings.toggleLayout(l.id)
        ThorKeyboardService.instance?.kb?.setLayouts(KeyboardSettings.layoutIds, KeyboardSettings.currentId)
    }, modifier = modifier, radius = 16.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = if (checked) g.accent else g.textTertiary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(l.rows[0].take(6).joinToString("").uppercase(l.locale), color = g.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
