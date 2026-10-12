package app.wayfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.security.MessageDigest

/**
 * 1.2 — a pinned guide page works offline: a copy of it (a single-file web archive, .mht) is saved
 * in the app's private storage when it's pinned, and refreshed every time it loads online. With no
 * connection — or when the page fails to load (a Wi-Fi login page, a dead site) — the copy opens.
 * Used by both the Guide page and the Home + Y deck's Guide tab.
 */
object GuideCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "guides").apply { mkdirs() }
    private fun name(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32) + ".mht"
    fun file(ctx: Context, key: String) = File(dir(ctx), name(key))
    fun has(ctx: Context, key: String) = file(ctx, key).length() > 0
    fun drop(ctx: Context, key: String) { file(ctx, key).delete() }

    /** Save what [web] shows as [key]'s offline copy (not a copy of a copy). */
    fun save(ctx: Context, key: String, web: WebView) {
        if (web.url?.startsWith("http") != true) return
        runCatching { web.saveWebArchive(file(ctx, key).absolutePath, false) { } }
    }

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

    /** 1.4 (Reddit): ad and tracking networks (a host or any of its subdomains) — the guide doesn't load them. */
    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "amazon-adsystem.com", "adnxs.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com", "criteo.net", "casalemedia.com", "indexww.com",
        "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com", "quantcount.com", "moatads.com",
        "adsafeprotected.com", "doubleverify.com", "3lift.com", "triplelift.com", "sharethrough.com", "teads.tv",
        "33across.com", "smartadserver.com", "adform.net", "bidswitch.net", "lijit.com", "sovrn.com", "media.net",
        "contextweb.com", "yieldmo.com", "gumgum.com", "kargo.com", "springserve.com", "connatix.com", "primis.tech",
        "revcontent.com", "mgid.com", "zemanta.com", "nitropay.com", "ezoic.net", "ezojs.com", "mediavine.com",
        "adthrive.com", "freestar.io", "pub.network", "venatusmedia.com", "playwire.com", "adsco.re",
        "id5-sync.com", "rlcdn.com", "crwdcntrl.net", "bluekai.com", "demdex.net", "everesttech.net", "adroll.com",
        "yahoo-ads.com", "advertising.com", "unrulymedia.com", "sonobi.com", "district-m.com", "e-planning.net",
        "undertone.com", "adkernel.com", "onetag-sys.com", "richaudience.com", "seedtag.com", "improvedigital.com",
    )
    fun isAd(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in AD_HOSTS) return true
            h = h.substringAfter('.', "").takeIf { it.contains('.') } ?: return false
        }
    }

    /** A page showing the saved copy, not the live one. */
    fun isOffline(web: WebView?) = web?.url?.startsWith("file:") == true

    /**
     * b18p (GitHub #108): the page that just finished refreshes the pinned page's offline copy — the pinned page itself,
     * live, unless the site answered it with an error ([failedUrl]: the main frame's last HTTP status ≥ 400). A site check
     * (StrategyWiki's Cloudflare "Just a moment…" comes as a 403 at the page's own address) or a dead page no longer
     * replaces a good copy.
     */
    fun refreshesCopy(pinned: String?, url: String?, failedUrl: String?): Boolean =
        pinned != null && url == pinned && url != failedUrl

    /**
     * b18p (GitHub #108): the guide's cookies, said here rather than left implicit. Both values ARE Android's defaults for
     * our targetSdk (first-party on, third-party off), so which cookies are kept or sent is the same as in 1.4.1: a site's
     * own cookies — a cookie banner's answer, a site check's pass (StrategyWiki's Cloudflare check sets `cf_clearance` on
     * strategywiki.org itself). What b18p adds is only the timing: they're written to disk when a guide view closes
     * ([release], [keepCookies]) rather than on Android's ~30 s timer, which matters only if the process dies in between.
     * Third-party cookies stay off: the Cloudflare check's only other host, challenges.cloudflare.com, runs its widget
     * (not verified on the Thor). Both guide views (the Guide page and the deck's Guide tab) share the one cookie jar of
     * Wayfinder's process — why the deck's tab asked where the full page didn't (#108) is not known yet.
     */
    private fun cookies(web: WebView) {
        runCatching { CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) } }
    }

    /** b18p (#108): what the guide sites set so far, written to disk now (one write; a guide view closing, the page stopping). */
    fun keepCookies() { runCatching { CookieManager.getInstance().flush() } }

    /** b18p (#108): a guide view goes away — its cookies kept ([keepCookies]), then the view freed. */
    fun release(web: WebView) {
        keepCookies()
        web.destroy()
    }

    /**
     * Wire [web] for the guide of [key]: load [start] (the pinned page when [pinned], else a search),
     * the saved copy when offline, fall back to it on a failed load, keep it fresh when online.
     * [onFailed] = nothing to show (no copy either) → the caller shows a message.
     */
    fun setup(web: WebView, ctx: Context, key: String, pinned: String?, start: String, onFailed: (Boolean) -> Unit) {
        fun loadCopy(): Boolean {
            if (pinned == null || !has(ctx, key)) return false
            web.settings.allowFileAccess = true   // only for our own saved copy
            web.loadUrl("file://" + file(ctx, key).absolutePath)
            return true
        }
        cookies(web)                                       // b18p (GitHub #108)
        web.webViewClient = object : WebViewClient() {   // links stay in the guide
            /** b18p (#108): the main frame's address whose last answer was an HTTP error (a site check, a dead page). */
            private var failedUrl: String? = null
            // 1.4 (Reddit): no ads — an ad network's request gets an empty answer
            override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): android.webkit.WebResourceResponse? =
                if (req != null && !req.isForMainFrame && isAd(req.url?.host)) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                else null
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) { onFailed(false) }
            override fun onReceivedHttpError(v: WebView?, req: WebResourceRequest?, res: android.webkit.WebResourceResponse?) {
                if (req?.isForMainFrame == true && (res?.statusCode ?: 0) >= 400) failedUrl = req.url?.toString()
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                // the pinned page loaded live → refresh its offline copy (b18p, #108: not with an error page)
                val refresh = refreshesCopy(pinned, url, failedUrl)
                failedUrl = null
                if (refresh) save(ctx, key, web)
            }
            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame != true) return
                if (isOffline(web) || !loadCopy()) onFailed(true)
            }
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) {
                h?.cancel()   // never proceed past a bad certificate
                // only the page itself failing counts (a broken ad or image doesn't)
                val main = e?.url?.let { it == v?.url || it == start || it == pinned } ?: false
                if (main && (isOffline(web) || !loadCopy())) onFailed(true)
            }
        }
        if (pinned != null && start == pinned && !online(ctx) && loadCopy()) return
        web.loadUrl(start)
    }
}
