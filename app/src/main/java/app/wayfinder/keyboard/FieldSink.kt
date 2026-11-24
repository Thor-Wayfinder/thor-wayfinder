package app.wayfinder.keyboard

import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.5-beta2 (GitHub #129): everything Wayfinder Keyboard sends to a text field or asks it goes through here, for every
 * app's field, on one ordered thread (b18p's typing thread, GitHub #108, which did it for Wayfinder's own fields only).
 *
 * Why: the keyboard runs on Wayfinder's one main thread with the accessibility service and the button engine. A question
 * to a field (its caps mode, the text around the cursor) waits for the app's answer, up to Android's 2 s limit. Chrome does
 * not answer for a search box it is tearing down (a page loading) or not showing yet (coming back to the front): on 's
 * Thor (b2f) the keyboard's start froze Wayfinder 6 s, 16 times, once 14 s, with the buttons late and no window drawn, and
 * a touch on a Wayfinder window then would have raised "not responding". The user sees a crash and restarts Wayfinder.
 *
 * Now no call to the field is made on the main thread. Sends and questions are queued in the order the keys were pressed
 * ([send]); answers come back to the main thread ([main]); a question whose answer would be dropped (a newer one is queued)
 * is not asked. The sends (text, keys, the action key) don't wait for the app; only the questions do, and they now wait on
 * the typing thread. All of this is called on the main thread; the blocks given to [send] run on the typing thread.
 *
 * 1.5-beta2 (GitHub #129, lost key): Chrome connects the same box again 0.5-0.9 s after it gets the keys (its window
 * shrinks for the thin bar). A key sent to the old connection just then is dropped by Chrome ("commitText on inactive
 * InputConnection") while Android tells the keyboard it was sent: commitText's true only means the message left. On b2h a
 * space was lost 3 times in 12 quick-typing runs. So the keyboard keeps a short record of what it typed since the field
 * last showed its text ([seen]), and when the app restarts the SAME box ([started]: restarting, same app, id, type,
 * options, hint) it reads the new connection: the text each side of the cursor says which of those keys arrived. Only the
 * ones missing, typed in the last [RESEND_MS], are sent again, once, in order; keys still queued go to the new connection.
 * Any doubt (another box, an empty box, a field that doesn't say its text, the user moved the cursor, an arrow or the
 * action key in between, text that matches nothing) = nothing resent and the old connection kept, as before: a key typed for
 * one box never lands in another.
 */
internal class FieldSink(
    /** The field's connection now: the service's currentInputConnection. */
    private val connection: () -> InputConnection?,
    private val editor: () -> EditorInfo?,
    /** The field hides its text (a password or a PIN). */
    private val password: () -> Boolean,
    private val enterAction: () -> EnterAction,
    /** Runs a block on the typing thread, after the ones given before. */
    private val typing: (Runnable) -> Unit,
    /** Runs a block on the main thread. */
    private val main: (Runnable) -> Unit,
    /** Runs a block on the main thread after a delay (ms). */
    private val mainLater: (Long, Runnable) -> Unit,
    /** A send that changes the text was queued: the host's preview asks again (its question goes after it). */
    private val edited: () -> Unit,
    /** The Enter key was pressed and its new line or action queued (the host's Eden close: [afterSent]). */
    private val entered: (EnterAction) -> Unit,
    /** The time now (ms), on either thread. */
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) : KeySink {

    companion object {
        private const val TAG = "ThorKeyboard"
        /** The text read each side of the cursor for the preview strip. */
        const val PREVIEW_CHARS = 80
        /** 1.5-beta2 (GitHub #129, lost key): how recent a key must be to be sent again after the box restarted, and a key
         *  still queued to go to the new connection (Chrome restarts its box 0.5-0.9 s after it gets the keys). */
        const val RESEND_MS = 1500L
        /** The most text kept of what is before the cursor, and of keys not yet seen in the field. */
        private const val KEEP_CHARS = 200
        private const val KEEP_KEYS = 32

        /** The app restarted the same box: same app, same field id and name, same type, options and hint. Chrome and
         *  WebView give one id to every box of a page, so two boxes alike pass this: the text check ([recover]) decides. */
        fun sameEditor(a: EditorInfo?, b: EditorInfo?): Boolean = a != null && b != null &&
            a.packageName == b.packageName && a.fieldId == b.fieldId && a.fieldName == b.fieldName &&
            a.inputType == b.inputType && a.imeOptions == b.imeOptions && a.privateImeOptions == b.privateImeOptions &&
            a.hintText?.toString() == b.hintText?.toString() && a.label?.toString() == b.label?.toString()
    }

    /** Blocks given to [send] and not finished yet (added here, taken off on the typing thread). */
    private val queued = AtomicInteger()

    // ── 1.5-beta2 (GitHub #129, lost key): the field's starts ──────────────────
    /** One start of the field: its connection, and its box's number (kept by a restart of the same box). [secret]: a
     *  password or a PIN: its text is not kept here, so nothing is checked or sent again there. */
    private class Start(val n: Int, val ic: InputConnection?, val box: Int, val at: Long, val secret: Boolean)
    @Volatile private var current = Start(0, null, 0, 0L, secret = false)
    /** The last start's editor (main thread). */
    private var lastEditor: EditorInfo? = null

    /**
     * The service's onStartInput (main thread, the new connection already current). [restarting] and the same editor = the
     * same box: the typing thread checks it and sends what the old connection lost ([settle]). [anchor]: the keys are in
     * the keyboard window (no preview strip asks the field's text), so a new box is read once here, for that check.
     * 1.5-beta2 (big test t7): true = that same box (the host keeps its keys' page, [KeyboardRules.newField]).
     */
    fun started(info: EditorInfo?, restarting: Boolean, anchor: Boolean): Boolean {
        val same = restarting && sameEditor(lastEditor, info)
        lastEditor = info
        val ic = connection()
        val was = current
        val secret = password()
        current = Start(was.n + 1, ic, if (same) was.box else was.box + 1, clock(), secret)
        Log.d(TAG, "field start restarting=$restarting same=$same")
        queued.incrementAndGet()
        typing(Runnable {
            try { val now = current; if (now.n != settled) settle(now, old = false) }
            catch (e: RuntimeException) { Log.w(TAG, "typing: ${e.message}") }
            finally { queued.decrementAndGet() }
        })
        if (anchor && !same && ic != null && !secret) preview(hide = false, answer = null)
        return same
    }

    /** [block] with the field's connection [ic] on the typing thread, after everything sent before. A RuntimeException
     *  there (a field gone) is logged, as b18p's typing thread did. */
    fun send(ic: InputConnection, block: (InputConnection) -> Unit) {
        val start = current
        val at = clock()
        queued.incrementAndGet()
        typing(Runnable {
            try { block(target(start, at, ic)) } catch (e: RuntimeException) { Log.w(TAG, "typing: ${e.message}") }
            finally { queued.decrementAndGet() }
        })
    }

    /** [send] with the field's connection now; nothing without a field. */
    fun send(block: (InputConnection) -> Unit) { send(connection() ?: return, block) }

    /** 1.5-beta2 (GitHub #129, lost key): where a block queued at [start] goes (typing thread). No start since: its own
     *  connection. The same box restarted since, checked, and queued in the last [RESEND_MS]: the new one. Anything
     *  else (another box, not checked): its own, as before, even if that one is gone. */
    private fun target(start: Start, at: Long, ic: InputConnection): InputConnection {
        val now = current
        if (now.n != settled) {
            try { settle(now, old = start.n != now.n) }
            catch (e: RuntimeException) { Log.w(TAG, "typing: ${e.message}"); forget(); sameBox = false }
        }
        val moved = now.ic
        return if (start.n != now.n && start.box == now.box && sameBox && moved != null && at >= now.at - RESEND_MS) moved else ic
    }

    // ── 1.5-beta2 (GitHub #129, lost key): what was typed, until the field shows it (typing thread only) ──────
    /** One edit: [text] committed, or (null) one character deleted before the cursor. */
    private class Edit(val text: String?)
    /** A key sent and not seen in the field yet: when, its edits, the text before the cursor after it. */
    private class Sent(val at: Long, val edits: List<Edit>, val after: String, val whole: Boolean)

    /** The start the typing thread last checked, and whether its box is the one before (its keys may go there). */
    private var settled = 0
    private var sameBox = false
    /** The box followed, and its connection. */
    private var box = -1
    private var boxIc: InputConnection? = null
    /** The text before the cursor last seen in the field (null: not known), [baseWhole]: all of it (else its end). */
    private var base: String? = null
    private var baseWhole = false
    /** The text after the cursor last seen (typing at the cursor doesn't change it). */
    private var tail: String? = null
    private var tailWhole = false
    private val sent = ArrayList<Sent>()

    private fun forget() { base = null; tail = null; sent.clear() }

    /** A new start of the field ([started]): a new box is followed from nothing; a restart of the same one is checked
     *  when keys may be missing there or still queued for it ([old]). */
    private fun settle(now: Start, old: Boolean) {
        settled = now.n
        sameBox = false
        val y = now.ic
        if (now.box != box || y == null || now.secret) { box = now.box; boxIc = if (now.secret) null else y; forget(); return }
        if (y === boxIc) { sameBox = true; return }
        val recent = sent.any { it.at >= now.at - RESEND_MS }
        sameBox = (old || recent) && recover(y, now.at)
        if (!sameBox) forget()
        boxIc = y
    }

    /**
     * The box restarted on [y]: read its text each side of the cursor. The text after it must be the one last seen (else
     * the cursor moved, or it is another box); the text before it says how many of the keys [sent] arrived (the longest
     * run that matches: a doubt never sends a key twice). The rest, if typed after [restartAt] - [RESEND_MS], is sent
     * to [y] in order. true: it is the same box.
     */
    private fun recover(y: InputConnection, restartAt: Long): Boolean {
        val b0 = base ?: return false
        val t0 = tail ?: return false
        val texts = listOf(b0) + sent.map { it.after }
        val wholes = listOf(baseWhole) + sent.map { it.whole }
        val n = texts.maxOf { it.length } + 1
        val before = y.getTextBeforeCursor(n, 0)?.toString() ?: return false
        val after = y.getTextAfterCursor(t0.length + 1, 0)?.toString() ?: return false
        // 1.5-beta2 (GitHub #129, lost key review): an empty box tells nothing. A page that moves on to its next box by
        // itself (a code typed one digit a box) gives an empty box alike: the digit that arrived would go there again, and
        // on to the box after. So a key lost in an empty box stays lost, as on b2h
        if (before.isEmpty() && after.isEmpty()) return false
        if (if (tailWhole) after != t0 else t0.isEmpty() || !after.startsWith(t0)) return false
        val arrived = texts.indices.reversed().firstOrNull { i ->
            if (wholes[i]) before == texts[i] else texts[i].isNotEmpty() && before.endsWith(texts[i])
        } ?: return false
        val lost = sent.drop(arrived)
        if (lost.isNotEmpty() && lost.first().at < restartAt - RESEND_MS) return false
        base = texts[arrived]; baseWhole = wholes[arrived]
        sent.clear()
        boxIc = y
        for (s in lost) {
            for (e in s.edits) if (e.text != null) y.commitText(e.text, 1) else y.deleteSurroundingTextInCodePoints(1, 0)
            typed(y, s.edits)
        }
        if (lost.isNotEmpty()) Log.w(TAG, "typing: the field restarted, ${lost.size} key(s) sent again")
        return true
    }

    /** [edits] were sent to [ic]: kept until the field shows them. Not the box followed, or nothing known of it: no record. */
    private fun typed(ic: InputConnection, edits: List<Edit>) {
        if (ic !== boxIc) return
        var s = sent.lastOrNull()?.after ?: base ?: return
        var whole = sent.lastOrNull()?.whole ?: baseWhole
        for (e in edits) s = when {
            e.text != null -> s + e.text
            s.isEmpty() -> { forget(); return }
            s.length >= 2 && Character.isSurrogatePair(s[s.length - 2], s[s.length - 1]) -> s.dropLast(2)
            else -> s.dropLast(1)
        }
        if (s.length > KEEP_CHARS) { s = s.takeLast(KEEP_CHARS); whole = false }
        if (sent.size >= KEEP_KEYS) { forget(); return }
        sent += Sent(clock(), edits, s, whole)
    }

    /** A key that moves the cursor or ends the typing (an arrow, a word jump, Enter, a Delete key): what [ic]'s box holds
     *  around the cursor isn't known any more, nothing before it can be checked. */
    private fun unknown(ic: InputConnection) { if (ic === boxIc) forget() }

    /** [ic] answered the text before the cursor ([asked] characters asked): everything sent before has arrived (Chrome
     *  answers in order, and drops everything once a connection is gone). */
    private fun seen(ic: InputConnection, before: CharSequence?, asked: Int) {
        if (ic !== boxIc || before == null) return
        val b = before.toString()
        val bWhole = b.length < asked
        val s = sent.lastOrNull()?.after ?: base
        val sWhole = sent.lastOrNull()?.whole ?: baseWhole
        when {
            s != null && !bWhole && s.endsWith(b) -> { base = s; baseWhole = sWhole }       // the longer known end
            else -> { base = b; baseWhole = bWhole }
        }
        sent.clear()
    }

    /** [ic] answered the text after the cursor. */
    private fun seenAfter(ic: InputConnection, after: CharSequence?, asked: Int) {
        if (ic !== boxIc || after == null) return
        tail = after.toString(); tailWhole = after.length < asked
    }

    /** 1.5-beta2 (GitHub #129, review): something sent or asked is still waiting for the field. A held Backspace repeats
     *  only when nothing waits ([KeySink.busy]), so it deletes at the field's speed, as when it waited on the main thread:
     *  a slow field no longer piles up deletes that go on after the key is let go. */
    override fun busy(): Boolean = queued.get() > 0

    /** 1.5-beta2 (GitHub #129, review): the keyboard closes: the caps and preview questions still queued are not asked
     *  (nobody reads their answers now), so only the typing waits before the close ([afterSent]), not a slow field's
     *  questions. One already being asked runs to its end. */
    fun dropQuestions() { capsAsks++; previewAsks++ }

    /**
     * [block] on the main thread once everything queued before has been sent: at once when nothing is waiting (the usual
     * case). An app that watches the keyboard window (Eden submits its text the moment it closes) gets all the text before
     * the keyboard hides, as when the keyboard sent on the main thread. [capMs] > 0: no later than that, so a field that
     * doesn't answer a question can't hold the close for long.
     */
    fun afterSent(capMs: Long = 0L, block: () -> Unit) {
        if (queued.get() == 0) { block(); return }
        var done = false                                                    // main thread only
        val once = Runnable { if (!done) { done = true; block() } }
        typing(Runnable { main(once) })
        if (capMs > 0L) mainLater(capMs, once)
    }

    /** A key's press and release sent through [ic] (the events Android's sendDownUpKeyEvents sends). */
    private fun tap(ic: InputConnection, code: Int) {
        val t = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    /** A key tapped into the field (the text cursor's arrows), in order with the typing. */
    fun key(code: Int) = send { tap(it, code); unknown(it) }

    /** Ctrl+←/→: a word at a time ([code]: the arrow), in order with the typing. */
    fun wordJump(code: Int) {
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        send { ic ->
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
            unknown(ic)
        }
    }

    /** What [deleteIn] did: nothing (nothing before the cursor), one character, the selection, or a Delete key. */
    private enum class Deleted { NOTHING, CHAR, SELECTION, KEY }

    /** One character back (or the selection) in [ic], on the typing thread. */
    private fun deleteIn(ic: InputConnection): Deleted {
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return Deleted.SELECTION }
        // 1.4.1 (GitHub #77): nothing before the cursor = nothing to delete — asking anyway crashed Compose text fields
        // (they delete from -1); an app that doesn't say gets a plain Delete key, as a hardware keyboard sends
        val before = ic.getTextBeforeCursor(1, 0)
        seen(ic, before, 1)
        return when {
            before == null -> { tap(ic, KeyEvent.KEYCODE_DEL); Deleted.KEY }
            before.isNotEmpty() -> { ic.deleteSurroundingTextInCodePoints(1, 0); Deleted.CHAR }
            else -> Deleted.NOTHING
        }
    }

    // ── KeySink ──────────────────────────────────────────────────────────
    override fun commit(text: String) { send { it.commitText(text, 1); typed(it, listOf(Edit(text))) }; edited() }

    override fun deleteBackward() {
        if (connection() == null) return
        send { ic ->
            when (deleteIn(ic)) {
                Deleted.CHAR -> typed(ic, listOf(Edit(null)))
                Deleted.NOTHING -> {}
                else -> unknown(ic)
            }
        }
        edited()
    }

    override fun enter() {
        if (connection() == null) return
        val action = editor()?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val kind = enterAction()
        send { ic ->
            if (kind == EnterAction.NEWLINE) ic.commitText("\n", 1) else ic.performEditorAction(action)
            unknown(ic)
        }
        // 1.5-beta2 (GitHub #129, review): the host hears it right after the action is queued, before the preview's
        // question: its close counts from the action sent, not from a page that is loading answering the preview
        entered(kind)
        edited()
    }

    override fun moveCursor(delta: Int) {
        repeat(kotlin.math.abs(delta)) { key(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
    }

    /** Never asked here (it would wait on the main thread): [capsLater] asks on the typing thread. */
    override fun autoCapitalize(): Boolean = false

    /** Never asked here: [space] reads the text before the cursor on the typing thread. */
    override fun textBeforeCursor(n: Int): String = ""

    /** Caps questions asked so far (main thread); an older one still queued is not asked: its answer would be dropped
     *  (each question comes with a newer edit, [KeyboardController.afterEdit]). */
    @Volatile private var capsAsks = 0

    /** Should the next letter be a capital (the field's caps mode)? [answer] later, on the main thread. Not a text field,
     *  a password, or no field: no, at once. */
    override fun capsLater(answer: (Boolean) -> Unit) {
        val asked = ++capsAsks
        val info = editor()
        val ic = connection()
        if (info == null || ic == null || (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT || password()) {
            answer(false); return
        }
        val type = info.inputType
        send(ic) { f ->
            if (asked != capsAsks) return@send
            val caps = f.getCursorCapsMode(type) != 0
            main(Runnable { answer(caps) })
        }
    }

    /** A space, with the double-space rule ("word␣" + space → "word. "), read and sent on the typing thread in order.
     *  1.5-beta2 (GitHub #129, lost key): the "␣" to replace was just read, so it is deleted without reading the text
     *  before the cursor a second time (#77's check is that read): one wait less, and a box that restarts between the two
     *  reads no longer turns the period into a Delete key nobody can check (so it can be sent again, [recover]). */
    override fun space() {
        send { ic ->
            val before = ic.getTextBeforeCursor(2, 0)
            seen(ic, before, 2)
            if (KeyboardRules.doubleSpace(before ?: "")) {
                if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); ic.commitText(". ", 1); unknown(ic) }
                else {
                    ic.deleteSurroundingTextInCodePoints(1, 0)
                    ic.commitText(". ", 1)
                    typed(ic, listOf(Edit(null), Edit(". ")))
                }
            } else { ic.commitText(" ", 1); typed(ic, listOf(Edit(" "))) }
        }
        edited()
    }

    /** Preview questions asked so far (main thread): only the newest is asked and shown. */
    @Volatile private var previewAsks = 0

    /** Both sides of the cursor, for the preview strip: [answer] on the main thread, for the newest question only. [hide]:
     *  dots for the text (a password). No field: empty, at once. [answer] null (1.5-beta2, GitHub #129, lost key): read
     *  only to know the field's text ([started]'s check), nothing shown. */
    fun preview(hide: Boolean, answer: ((Preview) -> Unit)?) {
        val asked = ++previewAsks
        val ic = connection() ?: run { answer?.invoke(Preview("", "")); return }
        fun clean(t: CharSequence?) = (t?.toString() ?: "").replace('\n', ' ')
            .let { if (hide) "•".repeat(it.length) else it }
        send(ic) { f ->
            if (asked != previewAsks) return@send
            val before = f.getTextBeforeCursor(PREVIEW_CHARS, 0)
            seen(f, before, PREVIEW_CHARS)
            if (asked != previewAsks) return@send
            val after = f.getTextAfterCursor(PREVIEW_CHARS, 0)
            seenAfter(f, after, PREVIEW_CHARS)
            val p = Preview(clean(before), clean(after))
            if (answer != null) main(Runnable { if (asked == previewAsks) answer(p) })
        }
    }
}
