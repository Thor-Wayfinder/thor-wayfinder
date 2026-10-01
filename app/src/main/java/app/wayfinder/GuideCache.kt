package app.wayfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        web.webViewClient = object : WebViewClient() {   // links stay in the guide
            // 1.4 (Reddit): no ads — an ad network's request gets an empty answer
            override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): android.webkit.WebResourceResponse? =
                if (req != null && !req.isForMainFrame && isAd(req.url?.host)) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                else null
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) { onFailed(false) }
            override fun onPageFinished(v: WebView?, url: String?) {
                // the pinned page loaded live → refresh its offline copy
                if (pinned != null && url == pinned) save(ctx, key, web)
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
