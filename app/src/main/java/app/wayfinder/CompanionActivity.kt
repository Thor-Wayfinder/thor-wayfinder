package app.wayfinder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.wayfinder.ui.FocusableGlass
import app.wayfinder.ui.GlassPanel
import app.wayfinder.ui.GlassScreen
import app.wayfinder.ui.LocalGlass
import app.wayfinder.ui.ThorGlassTheme
import kotlinx.coroutines.delay

/**
 * Game companion: a page for the bottom screen while a game runs on top.
 *  - Guide: a browser that starts on a search for "<game> map guide"; 📌 keeps the page
 *    you land on for that game (it opens there next time).
 *  - Notes: a notepad per game, saved as you type.
 * Opened automatically by the service when the game opens on top (App profiles →
 * "Bottom-screen companion"), or by hand from App profiles.
 */
class CompanionActivity : ComponentActivity() {

    private val pkg = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Volume keys here = the media volume (else Android picks the ring volume when nothing plays).
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        AppSettings.init(this)
        pkg.value = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        setContent {
            val dark = when (AppSettings.themeMode) {
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            ThorGlassTheme(dark = dark) { CompanionScreen(pkg.value) { finish() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PKG)?.let { pkg.value = it }
    }

    companion object {
        const val EXTRA_PKG = "pkg"

        fun intent(ctx: Context, pkg: String): Intent = Intent(ctx, CompanionActivity::class.java)
            .putExtra(EXTRA_PKG, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

/** Per-game pinned page + notes (prefs "thor_companion"). The key is the app — or, inside an
 *  emulator, the running game ("pkg#game", from [GameProfiles]): each game its own page and notes
 *  (1.2; before, every game of one emulator shared them). */
object CompanionStore {
    /** Where [pkg]'s page and notes live right now: the detected game inside it, else the app. */
    fun keyFor(pkg: String): String = GameProfiles.runningIn(pkg)?.let { GameProfiles.key(pkg, it.game) } ?: pkg
    /** What to search for: the detected game's title, else the app's name. */
    fun titleFor(ctx: Context, pkg: String): String =
        GameProfiles.runningIn(pkg)?.title?.takeIf { it.isNotBlank() } ?: label(ctx, pkg)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thor_companion", Context.MODE_PRIVATE)
    fun url(ctx: Context, pkg: String): String? = prefs(ctx).getString("url:$pkg", null)
    fun pin(ctx: Context, pkg: String, url: String?) = prefs(ctx).edit().putString("url:$pkg", url).apply()
    fun notes(ctx: Context, pkg: String): String = prefs(ctx).getString("notes:$pkg", "").orEmpty()
    fun saveNotes(ctx: Context, pkg: String, text: String) = prefs(ctx).edit().putString("notes:$pkg", text).apply()

    fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun searchUrl(game: String): String = "https://duckduckgo.com/?q=" + Uri.encode("$game map guide")
}

@Composable
private fun CompanionScreen(pkg: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = LocalGlass.current
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
    var tab by remember { mutableStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pinned by remember(key) { mutableStateOf(CompanionStore.url(ctx, key)) }
    GlassScreen(span = app.wayfinder.ui.AuroraSpan.BOTTOM) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(game, color = g.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                BarButton(if (tab == 0) "● Guide" else "Guide") { tab = 0 }
                BarButton(if (tab == 1) "● Notes" else "Notes") { tab = 1 }
                if (tab == 0) {
                    BarButton("‹") { web?.let { if (it.canGoBack()) it.goBack() } }
                    val offline = GuideCache.isOffline(web)
                    BarButton(if (pinned != null && (pinned == web?.url || offline)) "📌 Pinned" else "📌 Pin") {
                        val w = web ?: return@BarButton
                        if (offline) return@BarButton   // the saved copy is showing: it IS the pinned page
                        val u = w.url ?: return@BarButton
                        val next = if (pinned == u) null else u
                        CompanionStore.pin(ctx, key, next); pinned = next
                        // pinned → an offline copy now (1.2); unpinned → the copy goes
                        if (next != null) GuideCache.save(ctx, key, w) else GuideCache.drop(ctx, key)
                    }
                }
                BarButton("✕") { onClose() }
            }
            // a new WebView per game: the companion is reused when the game changes, and the old
            // one kept showing (and pinning) the previous game's page (review 2026-09-25)
            if (tab == 0) androidx.compose.runtime.key(key) { GuidePage(key, game, pinned) { web = it } }
            else NotesPage(key)
        }
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    FocusableGlass(onClick = onClick, radius = 14.dp) {
        Text(text, color = LocalGlass.current.textPrimary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuidePage(pkg: String, game: String, pinned: String?, onWeb: (WebView) -> Unit) {
    val start = remember(pkg) { pinned ?: CompanionStore.searchUrl(game) }
    var failed by remember(pkg) { mutableStateOf(false) }
    var webRef by remember(pkg) { mutableStateOf<WebView?>(null) }
    val g = LocalGlass.current
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // the pinned page's offline copy, fallbacks and "can't load" (1.2, GuideCache)
                    GuideCache.setup(this, c, pkg, pinned, start) { failed = it }
                    webRef = this
                    onWeb(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) Column(Modifier.fillMaxSize().background(if (g.dark) Color(0xF00A0C14) else Color(0xF0E9ECF4)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("The guide page couldn't load", color = g.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text("Check the Thor's Wi-Fi — some networks need you to sign in on a login page first. A pinned page opens offline once it has loaded here.",
                color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            BarButton("Retry") { failed = false; webRef?.reload() }
        }
    }
}

@Composable
private fun NotesPage(pkg: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = LocalGlass.current
    var text by remember(pkg) { mutableStateOf(CompanionStore.notes(ctx, pkg)) }
    // saved as typed (apply() writes in the background): a 400 ms delay lost the last keystrokes
    // when the companion closed or the tab changed (review 2026-09-25)
    GlassPanel(Modifier.fillMaxSize(), radius = 16.dp) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            if (text.isEmpty()) Text("Notes for this game — quests, codes, where you left off…",
                color = g.textTertiary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = text, onValueChange = { text = it; CompanionStore.saveNotes(ctx, pkg, it) },
                textStyle = TextStyle(color = g.textPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(g.accent),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
