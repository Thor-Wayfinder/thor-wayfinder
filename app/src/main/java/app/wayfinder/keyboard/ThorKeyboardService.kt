package app.wayfinder.keyboard

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.wayfinder.AppSettings
import app.wayfinder.ButtonEngine
import app.wayfinder.ForegroundAppService
import app.wayfinder.ThemeMode
import app.wayfinder.ThorButton
import app.wayfinder.ui.ThorGlassTheme

/**
 * The Thor Keyboard, an input method.
 *
 * On a dual-screen Thor the IME is also the conduit: when the text field is on the
 * TOP screen the IME's own window collapses to nothing (the game/app keeps the whole
 * screen) and the keyboard is drawn full-size on the BOTTOM screen by
 * [KeyboardOverlay] (an accessibility overlay that never takes focus away from the
 * field). Typing still goes through this service's InputConnection. With the field on
 * the bottom screen — or without a second screen — it's a normal keyboard.
 *
 * Gamepad keys reach an IME before the app, so the controller types too (see
 * [KeyboardController]); they're only taken while the keyboard is showing.
 */
class ThorKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "ThorKeyboard"
        @Volatile var instance: ThorKeyboardService? = null
            private set
        /** 1.3: the keyboard is open (the controller is its own — the app's Game controls pause). */
        fun isOpen(): Boolean = instance?.keyboardVisible == true
        /** 1.3: a key Wayfinder injected itself (a remapped control → a keyboard key): the app's input,
         *  not the keyboard's controller (its release was taken → a stuck ↑, found testing). */
        private fun injected(e: KeyEvent) = e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
    }

    val kb = KeyboardController { if (currentInputConnection != null) sink else null }
    private val owner = ComposeOwner()

    /** true = keyboard drawn in the IME window; false = on the other screen (overlay). */
    private var inIme by mutableStateOf(true)
    /** 1.4: the keyboard is on the other screen, but a thin window stays open here for an app that watches it
     *  (Eden and the yuzu family submit the text the moment no keyboard window is visible). */
    private var keepStrip by mutableStateOf(false)
    /** The field's text around the cursor, for the overlay's preview strip. */
    var preview by mutableStateOf(Preview("", ""))
        private set
    private var editor: EditorInfo? = null
    private var shown = false
    /** The keyboard is on the other screen right now. */
    private var overlayUp = false
    /** "Same screen" was tapped for this field. */
    private var forceSameScreen = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)
        KeyboardSettings.init(this)
        kb.setLayouts(KeyboardSettings.layoutIds, KeyboardSettings.currentId)
        kb.onLayoutChanged = { KeyboardSettings.setCurrent(it) }
        ClipHistory.start(this)       // 1.3.2 (GitHub #45): the active keyboard may read the clipboard
    }

    override fun onDestroy() {
        ClipHistory.stop()
        hideOverlay()
        owner.destroy()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Landscape screens would otherwise get the full-screen "extract" editor.
    override fun onEvaluateFullscreenMode() = false

    /*
     * AYN's framework ignores onEvaluateFullscreenMode(): its InputMethodService.
     * updateFullscreenMode() forces the full-screen extract editor (the big white box +
     * "EXECUTE" over the app) whenever getResources() says LANDSCAPE, unless the keyboard
     * is on its hard-coded whitelist. The bottom screen (1240×1080) is landscape. So the
     * keyboard's own resources report portrait — same sizes, only the orientation flag.
     * (Verified in AYN's framework.jar, 2026-09-23.)
     */
    private var portraitRes: android.content.res.Resources? = null
    private var portraitFor: Configuration? = null

    override fun getResources(): android.content.res.Resources {
        val base = super.getResources()
        val cfg = base.configuration
        if (cfg.orientation != Configuration.ORIENTATION_LANDSCAPE) return base
        portraitRes?.let { if (portraitFor == cfg) return it }
        return runCatching {
            createConfigurationContext(Configuration(cfg).apply { orientation = Configuration.ORIENTATION_PORTRAIT }).resources
        }.getOrNull()?.also { portraitRes = it; portraitFor = Configuration(cfg) } ?: base
    }

    fun isDark(): Boolean = when (AppSettings.themeMode) {
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { owner.attach(it) }
        return ComposeView(this).apply {
            owner.attach(this)
            setContent {
                ThorGlassTheme(dark = isDark()) { app.wayfinder.ui.CappedFontScale {
                    if (inIme) {
                        val h = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
                        ThorKeyboardPanel(
                            kb, overlay = false, preview = preview,
                            modifier = Modifier.fillMaxWidth().height(h),
                            onHide = { close() },
                            moveLabel = otherDisplay()?.let { screenLabel(it) },
                            onMove = { moveToOtherScreen() },
                        )
                    } else if (keepStrip) KeyboardStrip(otherDisplay()?.let { screenLabel(it) } ?: "")
                    else Spacer(Modifier.height(0.dp))
                }}
            }
        }
    }

    /** 1.4: the thin window left open for apps that watch it — says where the keys are. */
    @androidx.compose.runtime.Composable
    private fun KeyboardStrip(where: String) {
        val g = app.wayfinder.ui.LocalGlass.current
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(34.dp)
            .background(g.base.copy(alpha = 0.92f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text("⌨  Typing on the ${where.substringAfter(' ').lowercase()} — ↵ or ${app.wayfinder.ButtonNames.m("B")} when done",
                color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }

    // ── field lifecycle ──────────────────────────────────────────────────
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editor = attribute
        kb.setLayouts(KeyboardSettings.layoutIds, KeyboardSettings.currentId ?: kb.layout.id)
        kb.enterAction = enterActionFor(attribute)
        if (!restarting) kb.reset() else kb.afterEdit()
        refreshPreview()
        if (overlayUp) ForegroundAppService.keyboardOverlay()?.setSecure(isPassword())
    }

    /**
     * The app asks for a keyboard. Field on the top screen + a bottom screen → draw the
     * keyboard there and DON'T open the IME window at all: an open IME window (even an
     * empty one) puts Android's navigation bar into "keyboard mode", a black strip on
     * the top screen. The overlay then lives as long as the field (see [onFinishInput],
     * [hideWindow]) and typing still goes through our InputConnection.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val field = fieldDisplay()
        val other = otherDisplay()
        val wantOther = !forceSameScreen && KeyboardSettings.placement == KeyboardPlacement.OTHER_SCREEN &&
            field == android.view.Display.DEFAULT_DISPLAY && other != null
        Log.d(TAG, "show requested field=$field other=$other -> ${if (wantOther) "overlay on $other" else "in IME"}")
        if (wantOther && showOverlay(other!!)) {
            inIme = false
            refreshPreview()
            // 1.4 (Tomodachi Life in Eden): an app may watch whether a keyboard WINDOW is visible — Eden submits the
            // text the moment it isn't (an empty name, asked again, for ever). A thin window stays open here.
            keepStrip = true
            return super.onShowInputRequested(flags, configChange)
        }
        keepStrip = false
        inIme = true
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        if (keepStrip && overlayUp) return      // the thin window only: the keys stay on the other screen
        inIme = true
        hideOverlay()
    }


    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        shown = false
        kb.dismissFocus()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        hideOverlay()
        forceSameScreen = false
        keepStrip = false
    }

    /** The app (or Back / B) hides the keyboard — the overlay goes too. */
    override fun hideWindow() {
        super.hideWindow()
        if (movingToOverlay) return
        if (SystemClock.uptimeMillis() < moveGraceUntil) {
            // The echo of our own move: once our IME window hid itself, the app asked
            // Android to hide the keyboard. Keep the overlay, and put Android back in
            // the "keyboard open" state (onShowInputRequested → overlay already up).
            moveGraceUntil = 0
            main.post { requestShowSelf(0) }
            return
        }
        hideOverlay()
    }
    private var movingToOverlay = false
    /** Until then, a hide request is the echo of a screen move, not the user closing. */
    private var moveGraceUntil = 0L

    /** Close the keyboard wherever it is. */
    fun close() {
        hideOverlay()
        // ALWAYS tell Android — with the keyboard on the other screen our IME window was
        // never shown, but Android still counts the keyboard as open and would keep
        // routing Back (and other keys) to us first, leaving the app deaf.
        requestHideSelf(0)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshPreview()
        if (newSelStart == newSelEnd) kb.afterEdit()
    }

    /** Both sides of the cursor — so moving the cursor moves the caret, not the text. */
    private fun refreshPreview() {
        if (inIme) return
        val ic = currentInputConnection
        fun clean(t: CharSequence?) = (t?.toString() ?: "").replace('\n', ' ')
            .let { if (isPassword() && !visiblePassword()) "•".repeat(it.length) else it }
        preview = Preview(clean(ic?.getTextBeforeCursor(80, 0)), clean(ic?.getTextAfterCursor(80, 0)))
    }

    /** "▲ Top screen" / "▼ Bottom screen" for the move button. */
    fun screenLabel(displayId: Int) =
        if (displayId == android.view.Display.DEFAULT_DISPLAY) "▲ Top screen" else "▼ Bottom screen"

    /** Where the move button sends the keyboard from the overlay: back to the field's screen. */
    fun overlayMoveLabel(): String = screenLabel(fieldDisplay())

    /** A "visible password" field: games and emulators use it to turn suggestions off — the text is meant to be seen. */
    private fun visiblePassword(): Boolean = editor?.inputType?.let {
        (it and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    } ?: false

    private fun isPassword(): Boolean {
        val t = editor?.inputType ?: return false
        val v = t and InputType.TYPE_MASK_VARIATION
        val cls = t and InputType.TYPE_MASK_CLASS
        return (cls == InputType.TYPE_CLASS_TEXT &&
            (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            // PIN fields: numbers, hidden
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    // ── placement ───────────────────────────────────────────────────────
    // The screen holding the focused text field. Ask accessibility first: the keyboard
    // window can still sit on the PREVIOUS screen when a new field asks for it (a Notes
    // box on the bottom screen was taken for the top one, 2026-09-23).
    private fun fieldDisplay(): Int =
        editor?.packageName?.let { ForegroundAppService.editorDisplay(it) }
            ?: window.window?.decorView?.display?.displayId
            ?: ForegroundAppService.focusedDisplay()
            ?: android.view.Display.DEFAULT_DISPLAY

    /** The screen the keyboard can move to: the other one. */
    private fun otherDisplay(): Int? {
        val here = fieldDisplay()
        return getSystemService(DisplayManager::class.java).displays
            .map { it.displayId }.firstOrNull { it != here }
    }

    private fun showOverlay(displayId: Int): Boolean =
        (ForegroundAppService.keyboardOverlay()?.show(displayId, this, secure = isPassword()) == true).also { if (it) { overlayUp = true; firstTimeTip(displayId) } }

    /** Once ever: what the keyboard is and its main buttons — shown on the field's screen, where
     *  the user is looking, so it doesn't cover the keys (tour rework, 2026-09-25). */
    private fun firstTimeTip(kbDisplay: Int) {
        val prefs = getSharedPreferences("thor_settings", MODE_PRIVATE)
        if (prefs.getBoolean("tip_keyboard", false)) return
        val where = if (kbDisplay == android.view.Display.DEFAULT_DISPLAY) "top" else "bottom"
        val shown = ForegroundAppService.tip(fieldDisplay(), listOf("Wayfinder Keyboard opened on the $where screen — type with the controller"),
            listOf("Left stick" to "pick a key", app.wayfinder.ButtonNames.m("A") to "type it", app.wayfinder.ButtonNames.m("X") to "delete",
                app.wayfinder.ButtonNames.m("Y") to "space", app.wayfinder.ButtonNames.m("B") to "close",
                "Left stick click" to "all its buttons"), 9000)
        if (shown) prefs.edit().putBoolean("tip_keyboard", true).apply()     // service not up: next time
    }

    private fun hideOverlay() {
        if (!overlayUp) return
        overlayUp = false
        ForegroundAppService.keyboardOverlay()?.hide()
        kb.dismissFocus()
    }

    /**
     * Moving between screens is purely local: Android keeps counting the keyboard as
     * open either way (the same state as when it first opened on the other screen), so
     * no hide/show round trip can arrive late and undo the move.
     */
    fun moveToOtherScreen() {
        val other = otherDisplay() ?: return
        forceSameScreen = false
        if (showOverlay(other)) {
            inIme = false
            moveGraceUntil = SystemClock.uptimeMillis() + 1500
            movingToOverlay = true; hideWindow(); movingToOverlay = false
            refreshPreview()
        }
    }

    fun moveToSameScreen() {
        forceSameScreen = true
        hideOverlay()
        inIme = true
        // Showing must go through Android: since Android 11 the app's side owns the
        // keyboard window's visibility, so a local showWindow() alone stays invisible.
        // onShowInputRequested() sees forceSameScreen and lets the IME window open.
        requestShowSelf(0)
    }

    // ── output ──────────────────────────────────────────────────────────
    private val sink: KeySink = object : KeySink {
        override fun commit(text: String) { currentInputConnection?.commitText(text, 1); refreshPreview() }
        override fun deleteBackward() {
            val ic = currentInputConnection ?: return
            if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); refreshPreview(); return }
            // 1.4.1 (GitHub #77): nothing before the cursor = nothing to delete — asking anyway crashed Compose text fields
            // (they delete from -1); an app that doesn't say gets a plain Delete key, as a hardware keyboard sends
            val before = ic.getTextBeforeCursor(1, 0)
            when {
                before == null -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                before.isNotEmpty() -> ic.deleteSurroundingTextInCodePoints(1, 0)
            }
            refreshPreview()
        }
        override fun enter() {
            val ic = currentInputConnection ?: return
            val action = editor?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            if (kb.enterAction == EnterAction.NEWLINE) ic.commitText("\n", 1)
            else ic.performEditorAction(action)
            refreshPreview()
            // 1.4: an action key (Done, Go, Search, Send) ends the typing — the keyboard closes too, as an app expects
            // when it hides the keyboard itself (Eden's name field submits when the keyboard window closes)
            if (keepStrip && kb.enterAction != EnterAction.NEWLINE && kb.enterAction != EnterAction.NEXT)
                main.postDelayed({ if (overlayUp) close() }, 150)
        }
        override fun moveCursor(delta: Int) {
            repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        override fun autoCapitalize(): Boolean {
            val info = editor ?: return false
            if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT || isPassword()) return false
            return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        }
        override fun textBeforeCursor(n: Int): String = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString() ?: ""
    }

    private fun enterActionFor(info: EditorInfo?): EnterAction {
        info ?: return EnterAction.NEWLINE
        val multiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 || multiLine) return EnterAction.NEWLINE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            else -> EnterAction.NEWLINE
        }
    }

    // ── controller ──────────────────────────────────────────────────────
    private val keyboardVisible get() =
        (overlayUp && ForegroundAppService.keyboardOverlay()?.displayId != null) || (shown && isInputViewShown)
    private var aLongFired = false
    private var aDown = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressA = Runnable { aLongFired = true; kb.controllerLongPress() }

    /**
     * Called by the Wayfinder service's key filter, which sees every button BEFORE any
     * app (games that read the pad directly included). While the keyboard is open the
     * whole pad belongs to it: every gamepad button is handled or swallowed here, so
     * nothing leaks to the app underneath. Home is left alone.
     */
    fun captureKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (injected(event)) return false
        // A release whose press we took is ours too — even if that press CLOSED the
        // keyboard (B / Back). Otherwise the lone release leaks to the app, which
        // reads it as "back" and leaves the page.
        if (event.action == KeyEvent.ACTION_UP && swallowUps.remove(code)) {
            if (keyboardVisible) controllerUp(event)
            return true
        }
        if (!keyboardVisible) return false
        if (code == KeyEvent.KEYCODE_HOME) return false
        // a combo (Home / Back held + a button) is Wayfinder's: taken here, Home's release went
        // Home and left the app mid-typing (layer off — review 2026-09-25)
        if (ButtonEngine.systemHeld && event.action == KeyEvent.ACTION_DOWN) return false
        val pad = ButtonEngine.printedButton(event) != null || dpadDir(code) != null ||
            KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_BACK
        if (!pad) return false
        kb.notePad()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> { swallowUps.add(code); controllerDown(event) }
            // its press went elsewhere (a combo let through): eating the release left that button
            // stuck in the game later (review 2026-09-25)
            KeyEvent.ACTION_UP -> return false
        }
        return true
    }

    /** Key codes whose press the keyboard consumed — their release is consumed too. */
    private val swallowUps = HashSet<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (injected(event)) return super.onKeyDown(keyCode, event)
        if (keyboardVisible && controllerDown(event)) { swallowUps.add(keyCode); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (injected(event)) return super.onKeyUp(keyCode, event)
        if (swallowUps.remove(keyCode)) { if (keyboardVisible) controllerUp(event); return true }
        return (keyboardVisible && controllerUp(event)) || super.onKeyUp(keyCode, event)
    }

    private fun controllerDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // Controller in use → keys show their button badges (both input paths get here).
        if (keyCode != KeyEvent.KEYCODE_BACK) kb.notePad()
        if (keyCode == KeyEvent.KEYCODE_BACK) return true                  // closes on release
        // D-pad = the text cursor; the left stick picks keys (onGenericMotionEvent).
        // (1.3, GitHub #11: or the D-pad picks keys — then the left stick is the cursor)
        dpadDir(keyCode)?.let { (dx, dy) -> if (KeyboardSettings.dpadKeys) kb.moveFocus(dx, dy, event.eventTime) else sendDownUpKeyEvents(keyCode); return true }
        val b = ButtonEngine.menuButton(event) ?: return KeyEvent.isGamepadButton(keyCode)   // Xbox style: bottom = A
        if (event.repeatCount > 0) return true
        when (b) {
            ThorButton.A -> { aLongFired = false; aDown = true; kb.controllerArm(event.eventTime); main.postDelayed(longPressA, 450) }
            ThorButton.B -> close()
            ThorButton.X -> kb.press(KeySpec(KeyKind.BACKSPACE))
            ThorButton.Y -> kb.press(KeySpec(KeyKind.SPACE))
            ThorButton.L2 -> kb.press(KeySpec(KeyKind.SHIFT))
            ThorButton.L1 -> jumpWord(KeyEvent.KEYCODE_DPAD_LEFT)
            ThorButton.R1 -> jumpWord(KeyEvent.KEYCODE_DPAD_RIGHT)
            ThorButton.R2 -> kb.press(KeySpec(if (kb.page == Page.LETTERS) KeyKind.TO_SYMBOLS else KeyKind.TO_LETTERS))
            ThorButton.START -> kb.press(KeySpec(KeyKind.ENTER))
            ThorButton.SELECT -> kb.nextLayout()
            ThorButton.L3 -> kb.toggleHelp()
            ThorButton.HOME -> return false
            else -> {}                                                     // L3/R3…: swallowed
        }
        return true
    }

    private fun controllerUp(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK) { close(); return true }
        if (dpadDir(keyCode) != null) return true
        val b = ButtonEngine.menuButton(event) ?: return KeyEvent.isGamepadButton(keyCode)
        if (b == ThorButton.HOME) return false
        // 1.4: only a release whose press we had (the A that opened the field came up here and typed a key)
        if (b == ThorButton.A) { main.removeCallbacks(longPressA); if (aDown && !aLongFired) kb.controllerPress(); aDown = false }
        return true
    }

    /** Ctrl+←/→: a word at a time. */
    private fun jumpWord(code: Int) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun dpadDir(keyCode: Int): Pair<Int, Int>? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
        KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
        KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
        else -> null
    }

    // The Thor's D-pad is a HAT axis (→ text cursor) and the left stick an analog pair
    // (→ key highlight) — both step on edges.
    private var hatX = 0; private var hatY = 0; private var stickLatched = false

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!keyboardVisible || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK)
            return super.onGenericMotionEvent(event)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        val swap = KeyboardSettings.dpadKeys        // 1.3 (GitHub #11): D-pad = keys, stick = cursor
        fun cursor(dx: Int, dy: Int) = sendDownUpKeyEvents(when {
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT; dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dy < 0 -> KeyEvent.KEYCODE_DPAD_UP; else -> KeyEvent.KEYCODE_DPAD_DOWN })
        if (hx != hatX || hy != hatY) {
            if (hx != 0 && hx != hatX) { if (swap) kb.moveFocus(hx, 0, event.eventTime) else cursor(hx, 0) }
            if (hy != 0 && hy != hatY) { if (swap) kb.moveFocus(0, hy, event.eventTime) else cursor(0, hy) }
            hatX = hx; hatY = hy
        }
        val x = event.getAxisValue(MotionEvent.AXIS_X); val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val mag = maxOf(kotlin.math.abs(x), kotlin.math.abs(y))
        if (mag > 0.6f && !stickLatched) {
            stickLatched = true
            val (dx, dy) = if (kotlin.math.abs(x) > kotlin.math.abs(y)) (if (x > 0) 1 else -1) to 0 else 0 to (if (y > 0) 1 else -1)
            if (swap) cursor(dx, dy) else kb.moveFocus(dx, dy, event.eventTime)
        } else if (mag < 0.3f) stickLatched = false
        return true
    }
}
