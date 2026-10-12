package app.wayfinder

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.3 (GitHub #27) — what the buttons are CALLED on screen. dev build 15 (WP2, D8): one answer per controller — the
 * Thor's face style (AYN's controller style: Nintendo = as printed, A on the right; Xbox = A at the bottom; PlayStation =
 * Xbox's positions with PlayStation's marks, Wayfinder's own part of it) names the Thor's buttons, "Names as printed on
 * the Thor" keeps A on the right whatever it does, and a connected controller has its own vocabulary ([PadLook],
 * [ExtPadsCtl.padLook]). Only names change: a combo stays on the same physical button ([ThorButton.label] is the
 * Thor's name, [ThorButton.printed] the letter on the Thor). The names themselves: [Vocab].
 */
object ButtonNames {
    /** AYN's controller style is Xbox (Settings.System temp_abxy_layout_mode = 0) — the Thor's positions, kept up to date. */
    var aynXbox by mutableStateOf(false)
        private set
    /** The Thor named the PlayStation way at the Xbox positions (Wayfinder's own part of the style, thor_face_ps). */
    var thorPs by mutableStateOf(false)
        private set
    /** D8: "Names as printed on the Thor" — A on the right whatever the style (More options). */
    @set:JvmName("storeAsPrinted")                    // (its JVM setter name is [setAsPrinted]'s)
    var asPrinted by mutableStateOf(false)
        private set
    private var prefs: android.content.SharedPreferences? = null
    private var appCtx: Context? = null

    /** dev build 15 (WP2): the Thor's face style — ONE answer for the Controller page, the quick-panel tile, its names. */
    val thorStyle: FaceStyle get() = Vocab.thorStyle(aynXbox, thorPs)
    /** The Thor's vocabulary: its style's names (as printed when chosen), the positions AYN's style gives its pad. */
    val thorLook: PadLook get() = Vocab.thorLook(aynXbox, thorPs, asPrinted)

    fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        appCtx = app
        val p = app.getSharedPreferences("thor_settings", Context.MODE_PRIVATE)
        prefs = p
        // dev build 15 (D8): "Button names on screen" folded into the face style — PlayStation's marks are a face style, "As
        // printed" a switch. Runs whenever the old key is there (a restored older backup too: thor_settings is backed up)
        runCatching { p.getInt("button_names", -1) }.getOrDefault(-1).takeIf { it >= 0 }?.let { old ->
            val (ps, printed) = Vocab.migrateNames(old)
            p.edit().putBoolean("thor_face_ps", ps).putBoolean("names_printed", printed).remove("button_names").apply()
        }
        thorPs = p.getBoolean("thor_face_ps", false)
        asPrinted = p.getBoolean("names_printed", false)
        aynXbox = readAyn(app)
        runCatching {
            val main = Handler(Looper.getMainLooper())
            app.contentResolver.registerContentObserver(Settings.System.getUriFor("temp_abxy_layout_mode"), false,
                object : ContentObserver(main) {
                    override fun onChange(self: Boolean) {
                        // not Wayfinder's own flip (every input layer start switches AYN's style away and back to rebuild
                        // its pad): the names flickered for 0.5 to 3 s. Read again when it has ended ([refreshAyn]) — or
                        // when its 10 s are up (a helper that died mid-flip never says it ended)
                        if (ExtPadsCtl.aynFlipping()) { main.postDelayed({ if (!ExtPadsCtl.aynFlipping()) aynXbox = readAyn(app) }, 10_500); return }
                        aynXbox = readAyn(app)
                    }
                })
        }
    }

    private fun readAyn(app: Context) =
        runCatching { Settings.System.getInt(app.contentResolver, "temp_abxy_layout_mode", 1) == 0 }.getOrDefault(false)
    /** AYN's style read again — Wayfinder's own flip has ended ([ExtPadsCtl]'s face check). Main thread. */
    fun refreshAyn() { appCtx?.let { aynXbox = readAyn(it) } }

    /** dev build 15 (WP2): the Thor's face style — the ONE setter (the Controller page, the quick-panel tile). Positions are
     *  AYN's style (written as AYN's own tile does; from AYN's "Ban On Use" too — its pad comes back), PlayStation's names
     *  Wayfinder's. AYN's drawer can only flip the positions: [thorStyle] follows it. */
    fun setThorStyle(ctx: Context, s: FaceStyle) {
        init(ctx)
        thorPs = s == FaceStyle.PLAYSTATION
        prefs?.edit()?.putBoolean("thor_face_ps", thorPs)?.apply()
        val ayn = runCatching { Settings.System.getInt(ctx.applicationContext.contentResolver, "temp_abxy_layout_mode", 1) }.getOrDefault(1)
        if ((ayn == 0) != s.xboxPositions || ayn == 2) QuickSettings.setControllerStyle(s.xboxPositions)   // the observer follows
    }

    /** D8: "Names as printed on the Thor" on / off. */
    fun setAsPrinted(on: Boolean) { asPrinted = on; prefs?.edit()?.putBoolean("names_printed", on)?.apply() }

    // ── the controller in hand (dev build 15, WP2): the last controller a key came from — its names on hints ──
    /** 0 the Thor, n a connected controller (an [ExtEngine] source, [ExtPadsCtl.srcOfDevice]). */
    var handSrc by mutableIntStateOf(0)
        private set
    /** A key came from controller [src] (the key paths, main thread). */
    fun inHand(src: Int) { if (handSrc != src) handSrc = src }
    /** The keys that drive the screens now — the page header's Back pill, the legend, hints. Build 18 review (C): while
     *  the controller in hand is the one being set up ([handLearning]) it drives nothing, so the Thor's own keys. */
    val handLook: PadLook get() = if (handLearning) thorOwn() else lookOfSrc(handSrc)
    /** Build 18 review (C): the controller in hand — by its own source, or docked as the Thor's — is learning in the setup
     *  wizard ([ExtPadsCtl.learningKey]): its B can't go back. One rule: [WizardHolds.backOnThor]. */
    val handLearning: Boolean get() {
        @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue          // recomposes when the controllers change
        return WizardHolds.backOnThor(handSrc.takeIf { it > 0 }?.let { ExtPadsCtl.keyOfSrc(it) }, ExtPadsCtl.seatPad()?.key,
            ExtPadsCtl.learningKey)
    }

    /** The vocabulary of source [src]. The Thor: its names, the positions its pad sends now (a game's layout, AYN's style);
     *  docked, the controller playing as the Thor's — its own names, the Thor's positions. */
    fun lookOfSrc(src: Int): PadLook {
        @Suppress("UNUSED_VARIABLE") val v = ExtPadsCtl.version.intValue          // recomposes when the controllers change
        // b18n (N3-ui 3, b18w w2): a controller away (its pad kept 30 minutes) or gone speaks no more — the Thor's names (the
        // header's Back read ○ with both pads away, until the next Thor press); so does a seated one away
        if (src > 0) ExtPadsCtl.liveKeyOfSrc(src)?.let { return ExtPadsCtl.padLook(it) }
        ExtPadsCtl.seatPadHere()?.let { return ExtPadsCtl.padLook(it.key).copy(xbox = thorXbox()) }   // (dev build 18f: by its switch too)
        return thorOwn()
    }
    /** The positions the Thor's pad sends now: a game's layout, else AYN's style. */
    private fun thorXbox(): Boolean = (if (PadLayerCtl.active) PadLayerCtl.layoutOverride else null)?.let { it == 'x' } ?: aynXbox
    /** The Thor's own buttons as they send now — never a docked controller's names. */
    private fun thorOwn(): PadLook = thorLook.copy(xbox = thorXbox())

    /** The face positions [dev] sends now ([src] its source): a connected controller's pad its own ([ExtPadsCtl.outXbox]);
     *  the Thor's copy / AYN's pad a game's layout, else 0x0112 = AYN's Xbox mode (by id, not by name: an external "Xbox
     *  Wireless Controller" re-made by AYN is 2020:0111); docked the rename way (AYN's id, the Thor's face), AYN's style. */
    fun xboxOut(dev: android.view.InputDevice?, src: Int): Boolean {
        if (src > 0) return ExtPadsCtl.keyOfSrc(src)?.let { ExtPadsCtl.outXbox(it) } ?: false
        (if (PadLayerCtl.active) PadLayerCtl.layoutOverride else null)?.let { return it == 'x' }
        if (dev != null && dev.vendorId == 0x2020 && dev.productId == 0x0112) return true
        return ExtPadsCtl.order == "dock" && aynXbox
    }

    // ── names ──
    /** The name of position [b] — the Thor's unless [look] says whose. */
    fun of(b: ThorButton, look: PadLook = thorLook): String = Vocab.name(b, look)
    /** The position that does a menu job (A confirm · B back · X · Y) on [look] — the controller in hand by default. */
    fun menuPos(job: ThorButton, look: PadLook = handLook) = Vocab.menuPos(job, look)
    /** dev build 17 (WP9): the screens ask [Say.menu] (one way to name a menu key; ButtonNames.m is gone). */
    fun menu(job: ThorButton, look: PadLook = handLook): String = Vocab.name(menuPos(job, look), look)
    fun psSymbol(xboxLetter: ThorButton): String? = Vocab.psSymbol(xboxLetter)
    fun faceSwap(b: ThorButton): ThorButton = Vocab.faceSwap(b)
}
