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
import app.wayfinder.CueOrigin
import app.wayfinder.ForegroundAppService
import app.wayfinder.Overlays
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
        /** 1.5-beta2 (GitHub #129): the longest a close waits for the typing still queued ([close]). */
        private const val CLOSE_WAIT_MS = 500L
        /** 1.5-beta2 (review of big test t8): the box went, the keys close this much later unless another box took them
         *  meanwhile (a web box's show request comes later, over binder): they closed and opened again (a blink). The action
         *  key's close waits the same. */
        private const val LEAVE_SETTLE_MS = 150L
        @Volatile var instance: ThorKeyboardService? = null
            private set
        /** 1.3: the keyboard is open (the controller is its own — the app's Game controls pause). */
        fun isOpen(): Boolean = instance?.keyboardVisible == true
        /**
         * b18n (N3-ui 2, b18v #8): the keyboard is up for a field of Wayfinder's own windows, wherever its keys are — Compose
         * state, for ControllerTextField's "the keyboard closed: the typing ends there too" (one B). That rule read Android's
         * IME insets, which only see a keyboard WINDOW on the field's screen: with the keys on the other screen and Keyboard's
         * "Keep a thin bar" off there is none, so closing the keys never ended the typing — the next B only left the field and
         * a second one left the page. Up from the keys' showing (either screen) until the user or the app closes them or the
         * field goes; a move between the screens keeps it up.
         */
        var upForOwnField by mutableStateOf(false)
            private set
        /** 1.5-beta2 (big test t8): boxes of Wayfinder's own windows typing now (ControllerTextField says, main thread). */
        private var ownTypingNow = 0
        /**
         * 1.5-beta2 (big test t8): a box of Wayfinder's own windows starts ([on]) or ends its typing (Back, another page,
         * another row). Compose hides the keyboard through the window's insets, which see only a keyboard WINDOW on that
         * screen: with the keys on the other screen and Keyboard's "Keep a thin bar" off there is none, so leaving the Hub's
         * Search left the keys up and they kept the controller (A typed into nothing). When no box of ours types any more
         * the keys close here ([ownFieldLeft]). After the current work on the main thread: a box that took over (a tap on
         * it) said so first, and its keys stay; keys asked for meanwhile (the Guide's web page) stay too.
         */
        fun ownTyping(on: Boolean) {
            if (on) { ownTypingNow++; return }
            ownTypingNow = (ownTypingNow - 1).coerceAtLeast(0)
            val asked = instance?.showRequests ?: return
            // review: a short wait, not the next main-loop turn (a web box's show request can come after it)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (ownTypingNow == 0) instance?.ownFieldLeft(asked) }, LEAVE_SETTLE_MS)
        }
        /** 1.3: a key Wayfinder injected itself (a remapped control → a keyboard key): the app's input,
         *  not the keyboard's controller (its release was taken → a stuck ↑, found testing). */
        private fun injected(e: KeyEvent) = e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
    }

    val kb = KeyboardController { if (currentInputConnection != null) line else null }
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
    /** b19 (1.5-beta1 device pass F1): the move button's choice for this field. true = the keys sent to the other screen,
     *  false = back to the field's screen, null = no move yet ("Where it opens" decides). Every later show request for the
     *  same field keeps it, on either screen: the move's own echo, a tap on the field, a config change
     *  ([KeyboardRules.keysOnOther]). Cleared when the field goes. */
    private var movedOther: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)
        KeyboardSettings.init(this)
        kb.setLayouts(KeyboardSettings.layoutIds, KeyboardSettings.currentId)
        kb.onLayoutChanged = { KeyboardSettings.setCurrent(it) }
        kb.numberRowOn = { KeyboardSettings.numberRow }      // 1.5-beta2 (Q7, GitHub D#125 / #106): Keyboard's switch
        // b18n (N3-ui 1): the top bar's buttons lit with the controller do what their taps do — the move button as each host
        // wires it (the overlay: back to the field's screen; the keyboard window: to the other screen), the hide button close()
        // 1.5-beta2 (GitHub #144): keys already on the other screen can always come back to the field's (docked: no other one)
        kb.canMove = { overlayUp || otherDisplay() != null }
        kb.onBar = { k -> when (k) {
            BarKey.MOVE -> if (overlayUp) moveToSameScreen() else moveToOtherScreen()
            BarKey.HIDE -> close()
            BarKey.BUTTONS -> kb.toggleHelp()
        } }
        ClipHistory.start(this)       // 1.3.2 (GitHub #45): the active keyboard may read the clipboard
    }

    override fun onDestroy() {
        ClipHistory.stop()
        hideOverlay()
        upForOwnField = false
        if (typingThread.isInitialized()) typingThread.value.quitSafely()      // b18p (#108)
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

    fun isDark(): Boolean = app.wayfinder.ui.GlassTokens.isDark(this)       // dev build 17 (WP10): the one dark rule

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { owner.attach(it) }
        return ComposeView(this).apply {
            owner.attach(this)
            setContent {
                ThorGlassTheme(dark = isDark()) { app.wayfinder.ui.CappedFontScale {
                    if (inIme) {
                        // 1.5-beta2 (Q7): taller with the number row, so the letter keys keep their height (its row is
                        // drawn a little lower, ThorKeyboardPanel). By the switch, not the page (review): ?123 / ABC never
                        // resize the window under the app
                        val h = (LocalConfiguration.current.screenHeightDp * (if (kb.numberRowOn()) 0.58f else 0.5f)).dp
                        ThorKeyboardPanel(
                            kb, overlay = false, preview = preview,
                            modifier = Modifier.fillMaxWidth().height(h),
                            onHide = { close() },
                            // 1.5-beta2 (GitHub #144, review R2): docked read as Compose state, so a TV plugged in while the keys
                            // are up takes the move button away (it stayed, dead); docked = no other screen ([otherDisplay])
                            moveLabel = if (app.wayfinder.Dock.dockedForUi) null else otherDisplay()?.let { screenLabel(it) },
                            onMove = { moveToOtherScreen() },
                        )
                    // review R2: where the keys really are (the overlay's screen); the other screen only when it isn't up
                    } else if (keepStrip) KeyboardStrip((ForegroundAppService.keyboardOverlay()?.displayId ?: otherDisplay()) ==
                        android.view.Display.DEFAULT_DISPLAY)
                    else Spacer(Modifier.height(0.dp))
                }}
            }
        }
    }

    /** 1.4: the thin window left open for apps that watch it — says where the keys are ([keysOnTop]: on the top screen).
     *  Build 18: what picks a key (KB1, decision H2), Start and Back as the controller in hand's badges with what they do
     *  (KB3, A7 tokens). b19 (I21): the window keeps Android's own insets (KB5's strip insets removed, see below). */
    @androidx.compose.runtime.Composable
    private fun KeyboardStrip(keysOnTop: Boolean) = androidx.compose.runtime.CompositionLocalProvider(
        app.wayfinder.ui.LocalPadLook provides app.wayfinder.ButtonNames.handLook) {
        val g = app.wayfinder.ui.LocalGlass.current
        val text = KeyboardWords.strip(if (keysOnTop) "top screen" else "bottom screen", KeyboardSettings.dpadKeys, kb.enterAction,
            app.wayfinder.ButtonNames.handLook)
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(34.dp)
            .background(g.base.copy(alpha = 0.92f)),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            app.wayfinder.ui.GlyphText(text, color = g.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }

    // KB5 (build 18) removed in b19 (I21): no onComputeInsets here, Android's own insets in every mode. In strip mode
    // Android 13 draws AYN's keyboard-mode bar (∨ · pin · switcher, no public API to hide it) inside this window, under the
    // strip, so Android's content top already is the strip's top: the b19 device pass read contentTopInsets=0 with the
    // strip at 0 of a 189 px window on both screens. KB5 capped its value at the strip's top and wrote only one greater than
    // Android's, so it never changed a value.

    // ── field lifecycle ──────────────────────────────────────────────────
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 1.5-beta2 (big test t8): the app's box went while the keys are on the other screen with no keyboard window on its
        // screen (Keyboard's "Keep a thin bar" off): the app's own hide reached nobody, the keys close below, any app
        val gone = KeyboardRules.fieldGone(wasText = KeyboardRules.typesText(editor?.inputType),
            nowText = KeyboardRules.typesText(attribute?.inputType),
            // review: the same view (Compose: one per window) — another view that takes typing itself (a terminal) is no box gone
            sameView = editor?.packageName == attribute?.packageName && editor?.fieldId == attribute?.fieldId,
            noWindowHere = overlayUp && !keepStrip)
        editor = attribute
        // 1.5-beta2 (GitHub #129, lost key): Chrome connects the same box again a moment after it gets the keys; a key sent
        // to the old connection then is dropped. The typing thread checks a restart of the same box and sends it again
        // ([FieldSink.started]); with the keys in this window (no preview strip) a new box's text is read once for that
        val sameBox = line.started(attribute, restarting, anchor = inIme)
        kb.setLayouts(KeyboardSettings.layoutIds, KeyboardSettings.currentId ?: kb.layout.id)
        kb.enterAction = attribute?.let { enterActionOf(it.imeOptions) } ?: EnterAction.NEWLINE
        // 1.5-beta2 (GitHub #129, review): a new field's preview starts empty: its text comes a moment later (asked off the
        // main thread), and the keys drew the previous field's text until then. 1.5-beta2 (big test t7): a new field also
        // when Android calls it a restart: another box, or the keys closed since they showed (Wayfinder's own boxes share
        // one view: the next one opened on the last one's page). Nothing here asks the field on the main thread
        if (KeyboardRules.newField(sameBox, keysClosed)) { preview = Preview("", ""); kb.reset() } else kb.afterEdit()
        refreshPreview()
        if (overlayUp) ForegroundAppService.keyboardOverlay()?.setSecure(isPassword())
        goneAt = if (gone) attribute else null                  // 1.5-beta2 (gv2 F2): Android's own show that follows is no request
        if (gone) boxGone(attribute)
    }

    /** 1.5-beta2 (review of big test t8): the box went ([onStartInput]): the keys close [LEAVE_SETTLE_MS] later, if no other
     *  start ([at] still the field) and no show request came meanwhile (another box took them: no close and reopen). */
    private fun boxGone(at: EditorInfo?) {
        val asked = showRequests
        main.postDelayed({ if (editor === at && showRequests == asked && overlayUp && !keepStrip) close() }, LEAVE_SETTLE_MS)
    }

    /**
     * The app asks for a keyboard. Field on the top screen + a bottom screen → draw the
     * keyboard there and DON'T open the IME window at all: an open IME window (even an
     * empty one) puts Android's navigation bar into "keyboard mode", a black strip on
     * the top screen. The overlay then lives as long as the field (see [onFinishInput],
     * [hideWindow]) and typing still goes through our InputConnection.
     * 1.4: a thin window (the strip) is opened there after all, for apps that watch it; b18i (D3) only while Keyboard's
     * "Keep a thin bar" is on ([KeyboardSettings.thinBar], the default) — off, 1.3's path above.
     * b19 (F1): after a move, the move button's choice for the field decides ([movedOther]), so the move's own echo (a new
     * show request, [hideWindow]) keeps the keys where the user sent them, from a field on either screen.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        // 1.5-beta2 (gv2 F2, h_diag A): the box went with the keys up and no keyboard window here, and Android shows the keys
        // again by itself on what replaced it (it still counts them up, the app's hide reached nobody: ATTACH_NEW_INPUT, right
        // after that start). Not a request for keys: not counted, nothing shown, the box-gone close goes ahead. Once: a show
        // the app asks for later on the same view (a game or terminal view that takes typing itself) counts as before
        if (KeyboardRules.carriedOver(boxWent = goneAt != null && goneAt === editor, typesNow = KeyboardRules.typesText(editor?.inputType))) { goneAt = null; return false }
        showRequests++                                          // 1.5-beta2 (GitHub #129, review): a close still waiting skips its hide
        val field = fieldDisplay()
        val other = otherDisplay()
        val wantOther = KeyboardRules.keysOnOther(movedOther, placementOther = KeyboardSettings.placement == KeyboardPlacement.OTHER_SCREEN,
            fieldOnTop = field == android.view.Display.DEFAULT_DISPLAY, otherScreen = other != null)
        Log.d(TAG, "show requested field=$field other=$other moved=$movedOther -> ${if (wantOther) "overlay on $other" else "in IME"}")
        if (wantOther && showOverlay(other!!)) {
            inIme = false
            refreshPreview()
            // 1.4 (Tomodachi Life in Eden): an app may watch whether a keyboard WINDOW is visible — Eden submits the
            // text the moment it isn't (an empty name, asked again, for ever). A thin window stays open here.
            // b18i (D3, GitHub #99): only while Keyboard's "Keep a thin bar" is on (the default). Off = 1.3's: no IME window
            // here at all, so AYN's keyboard-mode navigation bar (the black bar) doesn't come either — those apps submit at once.
            keepStrip = KeyboardSettings.thinBar
            if (keepStrip) return super.onShowInputRequested(flags, configChange)
            // Off, with this screen's window still up from the previous field (its keys were here): it would stay, empty,
            // with AYN's bar under it — hidden right after this request, as the move down hides it; the keys stay there
            main.post { if (!keepStrip && !inIme && overlayUp && isInputViewShown) hideHereKeepKeys() }
            return false
        }
        keepStrip = false
        inIme = true
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        markUp()
        if (keepStrip && overlayUp) { lightIfPad(); return }      // the thin window only: the keys stay on the other screen
        inIme = true
        hideOverlay()
        lightIfPad()
    }

    /** Build 18 (KB1): a controller opened the field (A0's [app.wayfinder.ui.InputMode]: a pad key in the last 1.5 s, no
     *  Hub touch since — the keyboard's own presses count too, [controllerDown]) → a key is lit at once, so the first A / ✕
     *  types. The release of the press that opened the field still types nothing (#61: only a press the keyboard had).
     *  Opened by a tap: nothing lit, as before. */
    private fun lightIfPad() {
        if (KeyboardRules.openedByPad(SystemClock.uptimeMillis(), app.wayfinder.ui.InputMode.lastPadKeyAt,
                app.wayfinder.ui.InputMode.lastWasTouch)) kb.lightKey()
    }


    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        shown = false
        // b18n review: not for the move to the other screen ([hideHereKeepKeys]) — the keys stay up there with the key or bar
        // button lit and the badges ([showOverlay] just lit them): "Move down" with A cleared them, the next A only lit a key
        if (!movingToOverlay) kb.dismissFocus()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        hideOverlay()
        upForOwnField = false                                   // b18n (N3-ui 2): the field went
        keysClosed = true                                       // 1.5-beta2 (big test t7): the next start is a new field
        goneAt = null                                           // 1.5-beta2 (gv2 F2)
        movedOther = null                                       // b19 (F1): the next field opens by "Where it opens"
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
        upForOwnField = false                                   // b18n (N3-ui 2): closed (not a move)
        keysClosed = true                                       // 1.5-beta2 (big test t7): closed (not a move)
    }
    private var movingToOverlay = false
    /** Until then, a hide request is the echo of a screen move, not the user closing. */
    private var moveGraceUntil = 0L

    /** Close the keyboard wherever it is. */
    fun close() {
        hideOverlay()
        upForOwnField = false                                   // b18n (N3-ui 2): the user closed it (B, Back, the hide key)
        keysClosed = true                                       // 1.5-beta2 (big test t7): the next start is a new field
        goneAt = null                                           // 1.5-beta2 (gv2 F2)
        // ALWAYS tell Android — with the keyboard on the other screen our IME window was
        // never shown, but Android still counts the keyboard as open and would keep
        // routing Back (and other keys) to us first, leaving the app deaf.
        // 1.5-beta2 (GitHub #129): once what was typed has been sent (at once when nothing waits): an app that submits when
        // the keyboard closes (Eden) gets all the text first, as when the keys were sent on the main thread; no more than
        // CLOSE_WAIT_MS for a field that doesn't answer. Review: the questions still queued are dropped first (only the
        // typing waits), and a show request meanwhile (the user taps the box again, the app asks for the keys) keeps the
        // keys it just opened: the waiting hide is skipped
        line.dropQuestions()
        val asked = showRequests
        line.afterSent(CLOSE_WAIT_MS) { if (showRequests == asked) requestHideSelf(0) }
    }
    /** 1.5-beta2 (GitHub #129, review): show requests so far ([onShowInputRequested]): a close or an action key's close that
     *  waited for the typing doesn't hide keys asked for since. */
    private var showRequests = 0
    /** 1.5-beta2 (big test t7): the keys closed (the user, the app, the field gone; never a move between the screens) since
     *  they last showed ([markUp]): the next start of the input is a new field ([KeyboardRules.newField]). */
    private var keysClosed = true
    /** 1.5-beta2 (gv2 F2): the start that said the box went ([onStartInput], [boxGone]), until the next start, the close or
     *  the field's end: Android's own show right after it is not a request for keys ([KeyboardRules.carriedOver]). */
    private var goneAt: EditorInfo? = null

    /** 1.5-beta2 (big test t8): no box of Wayfinder's own types any more ([ownTyping]) and the keys are still up for one,
     *  on the other screen with no keyboard window on its screen: Compose's hide reached nobody, they close. With a window
     *  there (the thin bar, or the keys on the box's screen) Compose's own hide works, as before. Not if the keys were asked
     *  for since [asked] (another box of ours took them). */
    private fun ownFieldLeft(asked: Int) { if (showRequests == asked && upForOwnField && overlayUp && !keepStrip) close() }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshPreview()
        if (newSelStart == newSelEnd) kb.afterEdit()
    }

    /** Both sides of the cursor — so moving the cursor moves the caret, not the text. */
    private fun refreshPreview() {
        if (inIme) return
        // b18p (GitHub #108): read on the typing thread, the newest read is the one shown. 1.5-beta2 (GitHub #129): for every
        // app's field ([FieldSink.preview]): Chrome's box being torn down made this pair wait 4 s on the main thread
        line.preview(hide = isPassword() && !visiblePassword()) { preview = it }
    }

    /** The move button's words for sending the keys to [displayId]: "Move up" / "Move down" — the panel draws the
     *  screen beside them (build 18, KB2: "▲ Top screen" read as PlayStation's △ and as a status). */
    fun screenLabel(displayId: Int): String = KeyboardWords.move(toTop = displayId == android.view.Display.DEFAULT_DISPLAY)

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

    /** The screen the keyboard can move to: the other one. 1.5-beta2 (GitHub #144): none while docked on a TV or monitor
     *  ([KeyboardRules.keysOtherScreen]): the keys stay on the field's screen, the one the TV shows. */
    private fun otherDisplay(): Int? {
        val here = fieldDisplay()
        return KeyboardRules.keysOtherScreen(app.wayfinder.Dock.docked, getSystemService(DisplayManager::class.java).displays
            .map { it.displayId }.firstOrNull { it != here })
    }

    private fun showOverlay(displayId: Int): Boolean =
        (ForegroundAppService.keyboardOverlay()?.show(displayId, this, secure = isPassword()) == true)
            .also { if (it) { overlayUp = true; markUp(); firstTimeTip(displayId); lightIfPad() } }

    /** b18n (N3-ui 2): the keys show (either screen) — [upForOwnField] for a field of Wayfinder's own windows (any other app's
     *  field reads its own insets, or nothing). b19 (H1a): the keyboard owns the controller then, so Keyboard & mouse gives
     *  it back ([app.wayfinder.deck.DeckHold]). */
    private fun markUp() {
        keysClosed = false                                      // 1.5-beta2 (big test t7)
        upForOwnField = editor?.packageName == packageName
        app.wayfinder.deck.DeckHold.end(app.wayfinder.deck.DeckHoldRules.End.KEYBOARD)
    }

    /** Once ever: what the keyboard is and its main buttons — shown on the field's screen, where
     *  the user is looking, so it doesn't cover the keys (tour rework, 2026-09-25). */
    private fun firstTimeTip(kbDisplay: Int) {
        val where = if (kbDisplay == android.view.Display.DEFAULT_DISPLAY) "top" else "bottom"
        // dev build 17 (WP10): seen once it has been on screen 2 s (the host writes "tip_keyboard"); no service: next time
        Overlays.tip(this, "tip_keyboard", CueOrigin.Screen(fieldDisplay()), listOf("Wayfinder Keyboard opened on the $where screen — type with the controller"),
            listOf(KeyboardWords.pickWith(KeyboardSettings.dpadKeys) to "pick a key", app.wayfinder.Say.menu(app.wayfinder.ThorButton.A) to "type it", app.wayfinder.Say.menu(app.wayfinder.ThorButton.X) to "delete",
                app.wayfinder.Say.menu(app.wayfinder.ThorButton.Y) to "space", app.wayfinder.Say.menu(app.wayfinder.ThorButton.B) to "close",
                "Left stick click" to "all its buttons"))
    }

    /** [keepFocus] (b18n review): the keys move back to the field's screen ([moveToSameScreen]) — the lit key or bar button,
     *  the badges and the legend go with them, as across the move down. */
    private fun hideOverlay(keepFocus: Boolean = false) {
        if (!overlayUp) return
        overlayUp = false
        ForegroundAppService.keyboardOverlay()?.hide()
        if (!keepFocus) kb.dismissFocus()
    }

    /**
     * Moving between screens is purely local: Android keeps counting the keyboard as
     * open either way (the same state as when it first opened on the other screen), so
     * no hide/show round trip can arrive late and undo the move.
     */
    fun moveToOtherScreen() {
        val other = otherDisplay() ?: return
        if (showOverlay(other)) {
            movedOther = true                   // b19 (F1): kept by the echo's re-show (hideWindow → requestShowSelf), either screen
            inIme = false
            hideHereKeepKeys()
            refreshPreview()
        }
    }

    /** Hides the IME window on this screen while the keys stay on the other one (the overlay): an app's echo hide within
     *  1.5 s is the move's, not the user closing ([hideWindow]). The move down, and b18i (D3) a window left up when the thin
     *  bar is off. */
    private fun hideHereKeepKeys() {
        moveGraceUntil = SystemClock.uptimeMillis() + 1500
        movingToOverlay = true; hideWindow(); movingToOverlay = false
    }

    fun moveToSameScreen() {
        movedOther = false
        // b18n review: the focus stays as it is (no event needed: nothing clears it on the way — the keyboard window's
        // onStartInputView keeps a lit key, and with the thin bar its view is already started). Cleared here, "Move up" with A
        // came back with the middle key lit (no thin bar) or nothing (thin bar)
        hideOverlay(keepFocus = true)
        inIme = true
        // Showing must go through Android: since Android 11 the app's side owns the
        // keyboard window's visibility, so a local showWindow() alone stays invisible.
        // onShowInputRequested() sees movedOther = false and lets the IME window open.
        requestShowSelf(0)
    }

    // ── b18p (GitHub #108), 1.5-beta2 (GitHub #129): every field is typed into off the main thread ──────────────
    /**
     * b18p: the Guide's web page lives in Wayfinder's own process, so it answered the keyboard's questions (the text before
     * the cursor, the caps mode) through the main thread the keyboard was waiting on: one key froze Wayfinder ~12 s. That
     * fix sent a field of Wayfinder's own through this one thread and left every other app's field on the main thread.
     * 1.5-beta2 (GitHub #129): any app can stop answering for a while (Chrome, for a search box it is tearing down or not
     * showing yet: the keyboard's start froze Wayfinder 6-14 s, buttons and windows with it). So everything the keyboard
     * sends or asks, for every field, goes through this thread in order ([FieldSink]), and the answers come back here.
     */
    private val typingThread = lazy { android.os.HandlerThread("WfTyping").apply { start() } }
    private val typing by lazy { android.os.Handler(typingThread.value.looper) }

    // ── output ──────────────────────────────────────────────────────────
    /** The keyboard's output: the field's connection, used only on the typing thread ([FieldSink]).
     *  1.5-beta2 (GitHub #129): its type is written out (kb reads line and line reads kb, so Kotlin cannot infer both). */
    private val line: FieldSink = FieldSink(
        connection = { currentInputConnection },
        editor = { editor },
        password = { isPassword() },
        enterAction = { kb.enterAction },
        typing = { r -> typing.post(r) },
        main = { r -> main.post(r) },
        mainLater = { ms, r -> main.postDelayed(r, ms) },
        edited = { refreshPreview() },
        entered = { a -> actionKeyPressed(a) },
    )

    /** A key tapped into the field (the text cursor), in order with the typing. */
    private fun keyTap(code: Int) = line.key(code)

    /** 1.4: an action key (Done, Go, Search, Send) ends the typing — the keyboard closes too, as an app expects
     *  when it hides the keyboard itself (Eden's name field submits when the keyboard window closes).
     *  1.5-beta2 (GitHub #129): its 150 ms count from when the action was sent (it goes out on the typing thread).
     *  Review: no later than CLOSE_WAIT_MS behind a question the field doesn't answer, and not if the keys were asked for
     *  again meanwhile (Chrome: Search, then a tap on the new page's box: the keys just opened there stay).
     *  1.5-beta2 (gv2 F2): with the keys on the other screen whatever "Keep a thin bar" says ([KeyboardRules.closesOnAction]):
     *  off, there is no keyboard window on the box's screen, so an app's own hide reached nobody and the keys stayed up. */
    private fun actionKeyPressed(a: EnterAction) {
        if (KeyboardRules.closesOnAction(keysOnOther = overlayUp, action = a)) {
            val asked = showRequests
            line.afterSent(CLOSE_WAIT_MS) { main.postDelayed({ if (overlayUp && showRequests == asked) close() }, 150) }
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
        // Controller in use → keys show their button badges (both input paths get here). Build 18 review (KB1): and InputMode
        // hears the press — the keyboard eats it before ButtonEngine / ExtEngine feed InputMode, so the clock stopped at the
        // press that opened the field and a field opened by Start (Next) came up unlit. Before the press acts: Start's
        // Next restarts the input on the next field, whose lightIfPad reads this time.
        if (KeyboardRules.countsAsPadPress(keyCode)) { kb.notePad(); app.wayfinder.ui.InputMode.notePadKey() }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true                  // closes on release
        // D-pad = the text cursor; the left stick picks keys (onGenericMotionEvent).
        // (1.3, GitHub #11: or the D-pad picks keys — then the left stick is the cursor)
        dpadDir(keyCode)?.let { (dx, dy) -> if (KeyboardSettings.dpadKeys) kb.moveFocus(dx, dy, event.eventTime) else keyTap(keyCode); return true }
        val b = ButtonEngine.menuButton(event) ?: return KeyEvent.isGamepadButton(keyCode)   // Xbox style: bottom = A
        if (event.repeatCount > 0) return true
        when (b) {
            ThorButton.A -> { aLongFired = false; aDown = true; kb.controllerArm(event.eventTime); main.postDelayed(longPressA, KeyboardRules.HOLD_MS) }
            ThorButton.B -> close()
            ThorButton.X -> kb.press(KeySpec(KeyKind.BACKSPACE))
            ThorButton.Y -> kb.press(KeySpec(KeyKind.SPACE))
            // 1.5-beta2 (Q2, GitHub D#127): held = Shift held (capitals until it comes up), a tap = Shift as before
            ThorButton.L2 -> kb.shiftDown(event.eventTime)
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
        if (b == ThorButton.L2) kb.shiftUp(event.eventTime)                  // 1.5-beta2 (Q2): a tap's Shift, or the hold ends
        return true
    }

    /** Ctrl+←/→: a word at a time (b18p, #108; 1.5-beta2, #129: in order with the typing, every field). */
    private fun jumpWord(code: Int) = line.wordJump(code)

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
        fun cursor(dx: Int, dy: Int) = keyTap(when {
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
