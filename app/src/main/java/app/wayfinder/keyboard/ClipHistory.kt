package app.wayfinder.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.3.2 (GitHub #45) — the last few things you copied, for the clipboard key (Wayfinder Keyboard) and
 * the Clipboard button of the Keyboard & mouse deck. Android only lets the active keyboard read the
 * clipboard, so the list is kept by Wayfinder Keyboard while it's your keyboard ([start] / [stop]).
 * In memory only — never saved, never sent; clips Android marks as sensitive (passwords) are skipped.
 */
object ClipHistory {
    private const val MAX = 5
    /** Newest first. */
    val items = mutableStateListOf<String>()
    /** Wayfinder Keyboard is listening (it's the keyboard in use). */
    var listening by mutableStateOf(false)
        private set

    private var cm: ClipboardManager? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { read() }

    fun start(context: Context) {
        if (cm != null) return
        cm = context.getSystemService(ClipboardManager::class.java)?.also { it.addPrimaryClipChangedListener(listener) }
        listening = cm != null
        read()
    }

    fun stop() {
        runCatching { cm?.removePrimaryClipChangedListener(listener) }
        cm = null; listening = false
        clear()   // not kept once Wayfinder Keyboard isn't the keyboard any more
    }

    private const val MAX_CHARS = 20_000
    private const val KEEP_MS = 60 * 60 * 1000L
    private val addedAt = HashMap<String, Long>()
    private fun clear() { items.clear(); addedAt.clear() }
    /** Clips older than an hour go (Android clears its own clipboard after an hour too). */
    private fun expire() {
        val now = System.currentTimeMillis()
        items.filter { now - (addedAt[it] ?: now) > KEEP_MS }.forEach { items.remove(it); addedAt.remove(it) }
    }

    /** The clipboard now (also when the key is pressed: a copy made before the keyboard started). */
    fun read() {
        val m = cm ?: return
        runCatching {
            expire()
            // the system clipboard emptied (cleared by the user or by Android after an hour): the list goes too
            val clip = m.primaryClip ?: run { clear(); return }
            if (clip.itemCount == 0) { clear(); return }
            val sensitive = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
            if (sensitive) return
            // text only, never a file / URI (reading one could block), and capped
            val item = clip.getItemAt(0)
            val text = (item.text ?: item.htmlText?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT) })
                ?.toString()?.takeIf { it.isNotBlank() && it.length <= MAX_CHARS } ?: return
            items.remove(text)
            items.add(0, text)
            addedAt[text] = System.currentTimeMillis()
            while (items.size > MAX) addedAt.remove(items.removeAt(items.lastIndex))
        }
    }

    /** One line for a chip: newlines as spaces, long text cut. */
    fun preview(text: String, max: Int = 40): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }
}
