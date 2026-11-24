@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.wayfinder

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.wayfinder.ui.ButtonColours
import app.wayfinder.ui.ButtonGlyph
import app.wayfinder.ui.ButtonSize
import app.wayfinder.ui.ConfirmButton
import app.wayfinder.ui.GlassSegmentedControl
import app.wayfinder.ui.TokenArgb
import app.wayfinder.ui.arrivalTo
import app.wayfinder.ui.glyphName

/**
 * 1.5-beta2 (GitHub #134): Appearance's Button colours card, its body: Coloured (the default, as before) · One colour · Your
 * own ([ButtonColourWords]). Coloured and One colour show the four face badges as they now look; Your own shows a picker per
 * face button (Your own colours' wheel and Colour code: [ColorPick]) headed by the badge itself, in the slot order A B X Y
 * (the Xbox-way letters: a PlayStation hand sees ✕ ○ □ △ there), and "Reset button colours" (two presses). The badges are
 * drawn in the names of the controller in hand (the Thor's when a Joy-Con is held sideways: its dots have no colour). Down
 * into the pickers lands on the first, as Your own colours' (B11).
 */
@Composable
internal fun ButtonColoursBody() {
    val mode = AppSettings.buttonColourMode
    GlassSegmentedControl(ButtonColourWords.CHOICES, mode, Modifier.fillMaxWidth()) { AppSettings.chooseButtonColours(it) }
    val look = ButtonNames.handLook.takeUnless { it.sideways } ?: ButtonNames.thorLook
    // the four positions in slot order (slot i = the i-th): each shows the letter or mark of its slot
    val faces = remember(look) { Vocab.FACE_ORDER.sortedBy { Vocab.faceSlot(it, look) } }
    if (mode != ButtonColours.OWN) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            for (p in faces) ButtonGlyph(p, 30.dp, look = look)
        }
        return
    }
    val ps = look.names == FaceStyle.PLAYSTATION
    val own = AppSettings.buttonOwn.map { c -> c?.let { TokenArgb.of(it) } }
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().focusProperties { enter = { arrivalTo(first) } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        faces.forEachIndexed { slot, p ->
            // a slot never picked starts from its built-in colour (PlayStation's for a PlayStation hand)
            val argb = (TokenArgb.faceColour(slot, ps, ButtonColours.OWN, own) ?: TokenArgb.FACE_XBOX[slot]).toInt()
            ColorPick(glyphName(p, look), argb, Modifier.weight(1f).then(if (slot == 0) Modifier.focusRequester(first).focusGroup() else Modifier),
                head = { ButtonGlyph(p, 30.dp, look = look) }) { AppSettings.setButtonColour(slot, it) }
        }
    }
    ConfirmButton(ButtonColourWords.RESET, ButtonColourWords.RESET_CONFIRM, { AppSettings.resetButtonColours() }, size = ButtonSize.COMPACT)
}
