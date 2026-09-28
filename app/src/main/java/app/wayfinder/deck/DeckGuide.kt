package app.wayfinder.deck

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.wayfinder.CompanionStore
import app.wayfinder.ForegroundAppService
import app.wayfinder.GameProfiles
import kotlinx.coroutines.delay
import app.wayfinder.ui.LocalGlass
import androidx.compose.ui.Modifier as UiModifier

/** The deck's Guide tab (1.2) — not a key pad: the game's guide and notes. */
const val PAD_GUIDE = "guide"

/**
 * 1.2 — Guide & notes inside the Home + Y deck. The deck is an accessibility overlay, so it shows
 * even over a dual-screen game's second screen (where the Guide page, an activity, can't).
 * Same data as the Guide page ([CompanionStore], per app): the pinned page or a web search for the
 * game, and the notes — read-only here (the deck never takes the keyboard focus, the game keeps it);
 * "Edit / full page" opens the real Guide page.
 */
@Composable
@Suppress("UNUSED_PARAMETER")   // appLabel: the game's own title is used now
fun DeckGuidePane(pkg: String?, appLabel: String?, onClose: () -> Unit, modifier: UiModifier) {
    val g = LocalGlass.current
    if (pkg == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No game on the other screen", color = g.textSecondary, fontSize = 18.sp)
        }
        return
    }
    val ctx = LocalContext.current
    // the game inside an emulator, else the app — asked for when unknown (Wayfinder restarted mid-game:
    // the search said "Azahar map guide" instead of the game's name)
    var key by remember(pkg) { mutableStateOf(CompanionStore.keyFor(pkg)) }
    var game by remember(pkg) { mutableStateOf(CompanionStore.titleFor(ctx, pkg)) }
    LaunchedEffect(pkg) {
        if (key != pkg) return@LaunchedEffect
        GameProfiles.ask(pkg)   // the root helper answers in about a second
        repeat(10) {
            delay(400)
            val k = CompanionStore.keyFor(pkg)
            if (k != key) { key = k; game = CompanionStore.titleFor(ctx, pkg); return@LaunchedEffect }
        }
    }
    var notesTab by remember(pkg) { mutableStateOf(false) }
    var web by remember(pkg) { mutableStateOf<WebView?>(null) }
    var pinned by remember(key) { mutableStateOf(CompanionStore.url(ctx, key)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(UiModifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Guide", selected = !notesTab) { notesTab = false }
            Chip("Notes", selected = notesTab) { notesTab = true }
            if (!notesTab) {
                Chip("‹ Back", selected = false) { web?.let { if (it.canGoBack()) it.goBack() } }
                val offline = app.wayfinder.GuideCache.isOffline(web)
                Chip(if (pinned != null && (pinned == web?.url || offline)) "📌 Pinned" else "📌 Pin", selected = false) {
                    val w = web ?: return@Chip
                    if (offline) return@Chip   // the saved copy is showing: it IS the pinned page
                    val u = w.url ?: return@Chip
                    val next = if (pinned == u) null else u
                    CompanionStore.pin(ctx, key, next); pinned = next
                    // pinned → an offline copy now; unpinned → the copy goes
                    if (next != null) app.wayfinder.GuideCache.save(ctx, key, w) else app.wayfinder.GuideCache.drop(ctx, key)
                }
            }
            Box(UiModifier.weight(1f))
            Chip("Full page", selected = false) { onClose(); ForegroundAppService.openCompanionNow(pkg) }
        }
        if (!notesTab) key(key) { GuideWeb(key, game, pinned, UiModifier.fillMaxWidth().weight(1f)) { web = it } }
        else {
            val notes = remember(key) { CompanionStore.notes(ctx, key) }
            Box(UiModifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (g.dark) Color(0x26FFFFFF) else Color(0xB3FFFFFF)).padding(14.dp)) {
                Text(notes.ifBlank { "No notes for $game yet — write them in the full page (Full page)." },
                    color = if (notes.isBlank()) g.textTertiary else g.textPrimary, fontSize = 16.sp,
                    modifier = UiModifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuideWeb(pkg: String, game: String, pinned: String?, modifier: UiModifier, onWeb: (WebView) -> Unit) {
    val start = remember(pkg) { pinned ?: CompanionStore.searchUrl(game) }
    // a page that can't load (no connection, a Wi-Fi login page breaking HTTPS…) left a blank box
    var failed by remember(pkg) { mutableStateOf(false) }
    var webRef by remember(pkg) { mutableStateOf<WebView?>(null) }
    val g = LocalGlass.current
    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // the pinned page's offline copy, fallbacks and "can't load" (GuideCache)
                    app.wayfinder.GuideCache.setup(this, c, pkg, pinned, start) { failed = it }
                    webRef = this
                    onWeb(this)
                }
            },
            onRelease = { it.destroy() },
            modifier = UiModifier.fillMaxSize(),
        )
        if (failed) Column(
            UiModifier.fillMaxSize().background(if (g.dark) Color(0xF00A0C14) else Color(0xF0E9ECF4)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("The guide page couldn't load", color = g.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Check the Thor's Wi-Fi — some networks need you to sign in on a login page first.",
                color = g.textSecondary, fontSize = 15.sp)
            Box(UiModifier.height(44.dp)) { Chip("Retry", selected = true) { failed = false; webRef?.reload() } }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        UiModifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) Brush.linearGradient(listOf(g.accent, g.accent2))
                else Brush.linearGradient(listOf(if (g.dark) Color(0x1FFFFFFF) else Color(0x66FFFFFF), if (g.dark) Color(0x1FFFFFFF) else Color(0x66FFFFFF)))
            )
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else g.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
