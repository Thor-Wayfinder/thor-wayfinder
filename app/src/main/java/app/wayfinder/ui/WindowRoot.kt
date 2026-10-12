package app.wayfinder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * dev build 16 (WP7): what every Wayfinder window gives its pages — one Back path ([BackLayerHost]), the sheets
 * ([GlassSheet]), the focus memory, and the names of the controller in hand (a PlayStation player sees ✕ ○ □ △ and PS;
 * with the Thor in hand they are the Thor's). Thor-only surfaces provide the Thor's names themselves. Used by
 * MainActivity, the quick panel and the companion screen.
 * Build 18 (E7, V01): a window that gets the controller back keeps its focus ([KeepFocusOnReturn]).
 */
@Composable
fun WindowRoot(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheets = remember { SheetHost(scope) }
    val mem = remember { FocusMemory() }
    // whether anything in the window has the focus (a plain holder: read when the window comes back, never drawn)
    val inside = remember { booleanArrayOf(false) }
    KeepFocusOnReturn(mem, inside)
    BackLayerHost {
        CompositionLocalProvider(LocalSheetHost provides sheets, LocalFocusMemory provides mem,
            LocalPadLook provides app.wayfinder.ButtonNames.handLook) {
            Box(Modifier.fillMaxSize().onFocusChanged { inside[0] = it.hasFocus }) { content(); SheetLayer(sheets) }
        }
    }
}

/**
 * Build 18 (E7, V01): the controller went to the other screen and came back (Home + RS down / up), or the quick panel or
 * Keyboard & mouse opened and closed — the focus landed on the window's first focusable (the Hub's "Support me on Ko-fi":
 * one A opened a web page). The control focused when the window LOST the focus is asked again when it gets it back:
 * [FocusMemory.last] then may already name what Compose focused on return. Not when the focus stayed where it was (a
 * control that doesn't report itself to the memory — a slider — keeps it). A requester that left the composition (the page
 * changed meanwhile: a deep link, a panel tile) throws: the new page's entry stays. A sheet open then gets its control back.
 */
@Composable
private fun KeepFocusOnReturn(mem: FocusMemory, inside: BooleanArray) {
    val info = LocalWindowInfo.current
    LaunchedEffect(info, mem) {
        var saved: FocusRequester? = null
        snapshotFlow { info.isWindowFocused }.collect { focused ->
            if (!focused) { saved = mem.last; return@collect }
            val s = saved ?: return@collect
            withFrameNanos { }
            if (inside[0] && mem.last === s) return@collect             // still where it was
            var ok = runCatching { s.requestFocus() }.isSuccess
            // the window's own touch-mode change can still clear it on the frames after: once more if nothing has it
            if (ok) { withFrameNanos { }; withFrameNanos { }; if (!inside[0]) ok = runCatching { s.requestFocus() }.isSuccess }
            // debug builds only: a device check (adb logcat -s ThorFocus)
            if (app.wayfinder.BuildConfig.DEBUG) android.util.Log.d("ThorFocus", "window back: ${if (ok) "focus restored" else "control gone"}")
        }
    }
}
