package app.wayfinder.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * dev build 16 (WP7): ONE Back path per window. B (on its release: Android's BACK) and the on-screen Back pill close the
 * innermost open thing — a list in a sheet in a page, the page itself last. Every closable thing is a [BackLayer]; the
 * window's root ([BackLayerHost], in [WindowRoot]) owns the window's only callback. Raw BackHandlers raced each other by
 * the order they registered in (a page's own Back beat a list's when the page came later — uinav 5 / 10): none outside
 * this file.
 */

/** One closable thing (a list, a sheet, an in-page sub-screen, the page): [depth] = how deep it is nested, [seq] = when
 *  it was last enabled (the later wins among equals). */
internal class BackEntry(val depth: Int) { var enabled = false; var seq = 0L; var onBack: () -> Unit = {} }

/** Pure (JVM test): the innermost enabled layer. */
internal object BackOrder {
    fun pick(entries: List<BackEntry>): BackEntry? =
        entries.filter { it.enabled }.maxWithOrNull(compareBy<BackEntry>({ it.depth }, { it.seq }))
}

/** A window's layers and its one callback. */
class BackLayers internal constructor() {
    internal val entries = ArrayList<BackEntry>()
    private var seq = 0L
    internal fun nextSeq() = ++seq
    /** The window's ONE callback: enabled only while some layer is (else the window's default — leave / close). */
    internal val callback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { back() }
    }
    /** Closes the innermost open thing; false = nothing open here. */
    fun back(): Boolean = BackOrder.pick(entries)?.let {
        if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorBack", "layer ${it.depth}")
        it.onBack(); true
    } ?: false
    internal fun changed() { callback.isEnabled = entries.any { it.enabled } }   // eager: no recomposition lag
}

val LocalBackLayers = staticCompositionLocalOf<BackLayers?> { null }
internal val LocalBackDepth = compositionLocalOf { 0 }

/** The window root: registers [BackLayers.callback] on the window's dispatcher (MainActivity's, the quick panel's
 *  PanelOwner, the companion's own) — its first and only callback. */
@Composable
fun BackLayerHost(content: @Composable () -> Unit) {
    val layers = remember { BackLayers() }
    val owner = LocalOnBackPressedDispatcherOwner.current
    DisposableEffect(owner) {
        owner?.onBackPressedDispatcher?.addCallback(layers.callback)
        onDispose { layers.callback.remove() }
    }
    CompositionLocalProvider(LocalBackLayers provides layers, LocalBackDepth provides 0) { content() }
}

/**
 * A closable thing: while [enabled], B (and the Back pill) run [onBack] — unless something nested in it is open: what
 * opens inside [content] (composed one level deeper) closes first. No host (a window without [WindowRoot]): a plain
 * BackHandler where a dispatcher exists, as before dev build 16.
 */
@Composable
fun BackLayer(enabled: Boolean = true, onBack: () -> Unit, content: @Composable () -> Unit = {}) {
    val layers = LocalBackLayers.current
    val depth = LocalBackDepth.current + 1
    if (layers == null) {
        if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(enabled, onBack)
    } else {
        val entry = remember { BackEntry(depth) }
        DisposableEffect(layers) {
            layers.entries += entry; layers.changed()
            onDispose { layers.entries -= entry; layers.changed() }
        }
        SideEffect {
            entry.onBack = onBack
            if (entry.enabled != enabled) { entry.enabled = enabled; if (enabled) entry.seq = layers.nextSeq() }
            layers.changed()
        }
    }
    CompositionLocalProvider(LocalBackDepth provides depth) { content() }
}

/** The Back pill: the same path as B — the innermost open thing, else [fallback] (a window with nothing open, or
 *  without a dispatcher). Then B and the pill always agree. */
@Composable
fun rememberBackPress(fallback: () -> Unit): () -> Unit {
    val owner = LocalOnBackPressedDispatcherOwner.current
    val fb by rememberUpdatedState(fallback)
    return remember(owner) {
        {
            val d = owner?.onBackPressedDispatcher
            if (d != null && d.hasEnabledCallbacks()) d.onBackPressed() else fb()
        }
    }
}
