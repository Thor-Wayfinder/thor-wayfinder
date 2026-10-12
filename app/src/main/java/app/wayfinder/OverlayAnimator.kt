package app.wayfinder

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cross-screen move animation, iOS app-switcher style. The two screens are
 * physically stacked (top over bottom). Each display shows:
 *   - a FROSTED BACKDROP: the screen's own content, blurred and dimmed (never
 *     pure black), which hides the real window reparent happening underneath;
 *   - the app CARDS: rounded, shadowed cards that fly vertically between the
 *     stacked screens — the outgoing app shrinks and slides off toward the other
 *     screen, the incoming app slides in from the facing edge and grows.
 * The screens have different shapes (1920×1080 over 1240×1080), so the incoming
 * card keeps the SOURCE screen's proportions (a stretched card that then snapped to
 * the re-laid-out app was the old glitch). At the end each screen stays covered
 * until what should be there really is — the moved app on its new screen, the home
 * screen on a vacated one — then the whole layer cross-fades to it.
 * (Measured 2026-09-23 with screenrecord: the moved app is up ~at the end of the
 * flight, but a vacated screen's launcher may cold-start — some secondary launchers
 * finish themselves when covered — showing a snapshot, then a white first frame
 * ~1.2 s later. Uncovering before that = the "flash".)
 */
class OverlayAnimator(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ThorAnim"
        private const val DURATION = 560L        // card flight, ms (morph into a card, then glide)
        private const val EXPAND = 420L          // the landed card grows to fill the screen (blurring)
        private const val OVERLAP = 110L         // …starting this much before the landing ends
        private const val FADE = 320L            // final focus pull: blurred card → the sharp real app
        private const val HOLD_APP = 90L         // after the moved app shows up: let it draw
        private const val HOLD_HOME = 150L       // after a vacated screen's home shows up: let it settle
        private const val MAX_WAIT_APP = 1600L   // never cover a screen longer than this…
        private const val MAX_WAIT_HOME = 2600L  // …or this (a cold-starting launcher), past the flight
        private const val CARD_SCALE = 0.70f     // shrunk card size mid-flight
        private const val BACKDROP_BLUR = 38f     // frosted backdrop blur radius (px)
        private const val TINT_ALPHA = 0.34f     // dark tint over the backdrop
        private const val RADIUS_DP = 24f        // card corner radius at full card size
        private const val ELEVATION_DP = 22f     // card drop-shadow depth
        private const val BASE_DIM = 0xFF14161A.toInt()  // opaque dark base (never black-black)
        private const val MORPH_SCALE = 0.86f    // the app has become a card at this size
        // Choreography (fractions of DURATION): the outgoing app first eases into a rounded
        // card, THEN glides off; the incoming card glides in, THEN grows into place. One
        // shared front-loaded curve made the morph happen in ~80 ms ("too snappy").
        private val SMOOTH = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val LAND = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    /** What a covered screen waits for before fading: [pkg] arriving, or (not [arrives]) leaving. */
    class Expect(val displayId: Int, val pkg: String, val arrives: Boolean)

    // The overlay windows and their animation live on their OWN display-priority thread:
    // on the main thread, the burst of accessibility events a move causes froze the cards
    // for 60–130 ms (seen at 120 fps: a pause, then a jump = "it snaps").
    private val main: Handler by lazy {
        val t = android.os.HandlerThread("ThorAnim", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        Handler(t.looper)
    }
    private val dm get() = service.getSystemService(DisplayManager::class.java)

    /** A rounded clip — the whole view, or [rect] inside it (the incoming card's window). */
    private class RoundOutline(var radius: Float) : ViewOutlineProvider() {
        var rect: android.graphics.RectF? = null
        override fun getOutline(view: View, outline: Outline) {
            val r = rect
            if (r == null) outline.setRoundRect(0, 0, view.width, view.height, radius)
            else outline.setRoundRect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(), radius)
        }
    }

    private class Card(
        val view: View, val outline: RoundOutline, val incoming: Boolean,
        val startY: Float, val endY: Float, val startScale: Float, val endScale: Float,
        val maxRadius: Float, val restRadius: Float,
        // incoming only: screen size, picture size, and its fit (whole picture) / cover (fills) scales
        val w: Float = 0f, val h: Float = 0f, val bw: Float = 0f, val bh: Float = 0f,
        val fit: Float = 1f, val cover: Float = 1f,
        // incoming only: the sharp picture, and a pre-blurred copy (downscaled by [softDiv]) faded in
        val img: ImageView? = null, val soft: ImageView? = null, val softDiv: Float = 1f,
    ) {
        val m = android.graphics.Matrix()
        val ms = android.graphics.Matrix()
    }

    private class Layer(val displayId: Int, val wm: WindowManager, val root: FrameLayout, val backdrop: View, val cards: List<Card>,
                        val w: Int = 0, val h: Int = 0)

    fun animateSlide(
        slides: List<Pair<Int, Int>>,
        topDisplayId: Int,
        captures: Map<Int, Bitmap>,
        expect: List<Expect>,
        showing: (displayId: Int) -> String?,
        bufferSize: ((pkg: String) -> Pair<Int, Int>?)? = null,
        action: () -> Unit,
    ) {
        data class Spec(val displayId: Int, val bmp: Bitmap, val incoming: Boolean, val down: Boolean)
        val specs = ArrayList<Spec>()
        for ((from, to) in slides) {
            val bmp = captures[from] ?: continue
            val down = to != topDisplayId
            specs.add(Spec(from, bmp, incoming = false, down = down))  // leaves 'from' toward 'to'
            specs.add(Spec(to, bmp, incoming = true, down = down))     // arrives on 'to'
        }
        // It waits for the screens to settle, so it must run on the mover's thread.
        if (specs.isEmpty() || Looper.myLooper() == Looper.getMainLooper()) { action(); return }

        val layers = ArrayList<Layer>()
        val built = runOnMainAndWait {
            for (displayId in specs.map { it.displayId }.distinct()) {
                val display = dm.getDisplay(displayId) ?: continue
                try {
                    val dctx = service.createDisplayContext(display)
                    val wm = dctx.getSystemService(WindowManager::class.java)
                    val metrics = dctx.resources.displayMetrics
                    // The WHOLE screen, in its real orientation. Display metrics leave out the system
                    // bars (the card stopped short of full screen) and, from this service,
                    // maximumWindowMetrics answered the TOP screen's size for the bottom one. b18g (GitHub #49): from
                    // ScreenSize, never the process configuration (it follows the focused screen).
                    val real = ScreenSize.real(service, displayId) ?: android.graphics.Point()
                    val cap = captures[displayId]
                    Log.d(TAG, "layer $displayId: capture ${cap?.width}x${cap?.height}, real ${real.x}x${real.y}, metrics ${metrics.widthPixels}x${metrics.heightPixels}")
                    val w = (cap?.width ?: real.x).toFloat()
                    val h = (cap?.height ?: real.y).toFloat()
                    val density = metrics.density
                    val maxRadius = RADIUS_DP * density

                    val root = FrameLayout(dctx).apply { setBackgroundColor(BASE_DIM) }

                    // Frosted backdrop = this display's own content, blurred + dimmed.
                    val backdrop = FrameLayout(dctx)
                    captures[displayId]?.let { own ->
                        val img = ImageView(dctx).apply {
                            setImageBitmap(own); scaleType = ImageView.ScaleType.FIT_XY
                            if (Build.VERSION.SDK_INT >= 31)
                                setRenderEffect(RenderEffect.createBlurEffect(BACKDROP_BLUR, BACKDROP_BLUR, Shader.TileMode.CLAMP))
                        }
                        backdrop.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
                    }
                    backdrop.addView(View(dctx).apply {
                        setBackgroundColor(Color.BLACK); alpha = TINT_ALPHA
                    }, FrameLayout.LayoutParams(MATCH, MATCH))
                    root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

                    // Cards: incoming first (below), outgoing on top (lifting away).
                    val ordered = specs.filter { it.displayId == displayId }.sortedBy { !it.incoming }
                    val cards = ordered.map { s ->
                        val out = RoundOutline(0f)
                        // Incoming: a full-screen frame whose pictures are placed by matrices and shown
                        // through an animated window (card → whole screen). Outgoing: its own capture.
                        val iv: View = if (s.incoming) FrameLayout(dctx) else ImageView(dctx).apply {
                            setImageBitmap(s.bmp); scaleType = ImageView.ScaleType.FIT_XY
                        }
                        iv.clipToOutline = true; iv.outlineProvider = out
                        iv.elevation = ELEVATION_DP * density
                        // The card's own size: the source screen's shape fitted into this one
                        // (the outgoing card is this screen's own capture, so it fills it).
                        val fit = minOf(w / s.bmp.width, h / s.bmp.height)
                        val cw = if (s.incoming) s.bmp.width * fit else w
                        val ch = if (s.incoming) s.bmp.height * fit else h
                        val fills = cw >= w - 2f && ch >= h - 2f
                        val off = h / 2f + ch / 2f   // fully past the facing edge, from centred
                        val startY: Float; val endY: Float; val startScale: Float; val endScale: Float
                        if (s.incoming) {
                            startY = if (s.down) -off else off; endY = 0f
                            startScale = CARD_SCALE; endScale = 1f
                        } else {
                            startY = 0f; endY = if (s.down) off else -off
                            startScale = 1f; endScale = CARD_SCALE
                        }
                        iv.translationY = startY; iv.scaleX = startScale; iv.scaleY = startScale
                        val rest = if (fills) 0f else maxRadius
                        out.radius = radiusFor(startScale, maxRadius, rest)
                        if (s.incoming) {
                            val bw = s.bmp.width.toFloat(); val bh = s.bmp.height.toFloat()
                            val sharp = ImageView(dctx).apply { setImageBitmap(s.bmp); scaleType = ImageView.ScaleType.MATRIX }
                            // The soft copy: made ONCE here (downscale twice, filtered; drawn scaled back
                            // up = a smooth blur) and faded in. Animating a GPU blur's radius instead
                            // compiled new blur programs mid-animation: 75–130 ms stalls, then a jump.
                            // Shrunk 8× (two filtered steps) then box-blurred 3× ≈ a Gaussian of ~35 px
                            // at full size: soft enough that the old layout has no readable detail
                            // (a light or blocky blur ghosted against the re-laid-out app).
                            val div = 8f
                            val small = softCopy(Bitmap.createScaledBitmap(
                                Bitmap.createScaledBitmap(s.bmp, (bw / 2f).toInt().coerceAtLeast(1), (bh / 2f).toInt().coerceAtLeast(1), true),
                                (bw / div).toInt().coerceAtLeast(1), (bh / div).toInt().coerceAtLeast(1), true), radius = 3, passes = 3)
                            val soft = ImageView(dctx).apply { setImageBitmap(small); scaleType = ImageView.ScaleType.MATRIX; alpha = 0f }
                            (iv as FrameLayout).addView(sharp, FrameLayout.LayoutParams(MATCH, MATCH))
                            iv.addView(soft, FrameLayout.LayoutParams(MATCH, MATCH))
                            val card = Card(iv, out, true, startY, endY, startScale, endScale, maxRadius, rest,
                                w, h, bw, bh, fit, maxOf(w / bw, h / bh), sharp, soft, bw / small.width)
                            placeIncoming(card, 0f)
                            root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                            card
                        } else {
                            iv.invalidateOutline()
                            root.addView(iv, FrameLayout.LayoutParams(cw.toInt(), ch.toInt(), Gravity.CENTER))
                            Card(iv, out, false, startY, endY, startScale, endScale, maxRadius, rest)
                        }
                    }

                    val lp = WindowManager.LayoutParams(
                        MATCH, MATCH,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.START }
                    wm.addView(root, lp)
                    layers.add(Layer(displayId, wm, root, backdrop, cards, w.toInt(), h.toInt()))
                } catch (e: Exception) {
                    Log.w(TAG, "overlay build on display $displayId failed: ${e.message}")
                }
            }
        }
        if (!built) {
            // The overlays are still being built (stalled): move without them, and clean up
            // behind the late build — same thread, queued after it — or they'd stay on screen.
            Log.w(TAG, "overlay build timed out — moving without the animation")
            main.post { removeAll(layers) }
            action(); return
        }
        if (layers.isEmpty()) { action(); return }

        // Real reparent, hidden under the full-screen outgoing cards + opaque backdrop.
        try { action() } catch (e: Exception) { Log.e(TAG, "move under animation failed: ${e.message}") }

        val done = CountDownLatch(layers.size)
        val total = DURATION + EXPAND - OVERLAP
        val t0 = android.os.SystemClock.uptimeMillis()
        val flightEnd = t0 + total
        val fadeFrom = t0 + DURATION - OVERLAP + (EXPAND * 0.62f).toLong()
        runOnMainAndWait {
            val allCards = layers.flatMap { it.cards }
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = total; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { va ->
                    val ms = va.animatedFraction * total
                    val t = (ms / DURATION).coerceAtMost(1f)
                    val ex = SMOOTH.getInterpolation(((ms - (DURATION - OVERLAP)) / EXPAND).coerceIn(0f, 1f))
                    for (c in allCards) {
                        val s: Float; val y: Float; val cardness: Float
                        if (!c.incoming) {
                            val morph = SMOOTH.getInterpolation(seg(t, 0f, 0.42f))
                            val travel = SMOOTH.getInterpolation(seg(t, 0.18f, 1f))
                            s = 1f - (1f - MORPH_SCALE) * morph - (MORPH_SCALE - CARD_SCALE) * travel
                            y = c.startY + (c.endY - c.startY) * travel
                            cardness = morph
                        } else {
                            val travel = LAND.getInterpolation(seg(t, 0f, 0.72f))
                            val grow = SMOOTH.getInterpolation(seg(t, 0.35f, 1f))
                            s = CARD_SCALE + (1f - CARD_SCALE) * grow
                            y = c.startY + (c.endY - c.startY) * travel
                            cardness = 1f - grow
                        }
                        c.view.scaleX = s; c.view.scaleY = s; c.view.translationY = y
                        c.outline.radius = c.restRadius + (c.maxRadius - c.restRadius) * cardness
                        if (c.incoming) placeIncoming(c, ex) else c.view.invalidateOutline()
                    }
                }
                start()
            }
        }
        // Each screen uncovers on its own, once what belongs there is really showing. Polled
        // HERE (the mover's thread): the window queries are cross-process, and on the main
        // thread they stalled the card flight (a visible jump near its end).
        class Wait(val l: Layer, val e: Expect?) { var seenAt = 0L; var faded = false }
        val waits = layers.map { l -> Wait(l, expect.firstOrNull { it.displayId == l.displayId }) }
        while (waits.any { !it.faded }) {
            val now = android.os.SystemClock.uptimeMillis()
            for (w in waits.filter { !it.faded }) {
                val e = w.e
                val arriving = e == null || e.arrives
                val pkg = e?.let { runCatching { showing(it.displayId) }.getOrNull() }
                var ok = e == null || (if (e.arrives) pkg == e.pkg else pkg != null && pkg != e.pkg)
                // Shown isn't enough: right after the move the app still presents its OLD-size
                // frame, stretched (seen at 120 fps as a jolt after the reveal). Wait until its
                // surface really has this screen's size.
                if (ok && e != null && e.arrives && bufferSize != null && now - flightEnd < 900) {
                    val b = runCatching { bufferSize(e.pkg) }.getOrNull()
                    val sized = b == null || (b.first == w.l.w && b.second == w.l.h) || (b.first == w.l.h && b.second == w.l.w)
                    if (!sized) { if (w.seenAt == 0L) Log.d(TAG, "display ${w.l.displayId}: ${e.pkg} still at ${b?.first}x${b?.second} (screen ${w.l.w}x${w.l.h})"); ok = false }
                }
                if (ok && w.seenAt == 0L) { w.seenAt = now; Log.d(TAG, "display ${w.l.displayId}: $pkg up at ${now - flightEnd} ms past the flight") }
                val hold = if (arriving) HOLD_APP else HOLD_HOME
                val maxWait = if (arriving) MAX_WAIT_APP else MAX_WAIT_HOME
                // The dissolve may begin while the expansion is still easing out (no pause between them).
                if (now < fadeFrom || !((w.seenAt != 0L && now - w.seenAt >= hold) || now - flightEnd >= maxWait)) continue
                if (w.seenAt == 0L) if (BuildConfig.DEBUG) Log.w(TAG, "display ${w.l.displayId}: gave up waiting (${e?.pkg}, showing $pkg)")
                w.faded = true
                val l = w.l
                main.post {
                    // Focus pull: the full-screen, softly blurred card dissolves into the sharp real app.
                    l.root.animate().alpha(0f).setDuration(FADE).setInterpolator(SMOOTH)
                        .withEndAction { removeAll(listOf(l)); done.countDown() }.start()
                }
            }
            if (waits.any { !it.faded }) Thread.sleep(25)
        }
        done.await(FADE + 800, TimeUnit.MILLISECONDS)
        main.postDelayed({ removeAll(layers) }, 300) // safety net
    }

    /**
     * The incoming card at expansion [e] (0 = the landed card: the whole source picture,
     * fitted, rounded; 1 = the whole screen: the picture covering it, square corners,
     * blurred — the old layout can't match the re-laid-out app, so it goes soft before
     * the hand-off instead of snapping).
     */
    private fun placeIncoming(c: Card, e: Float) {
        val sc = c.fit + (c.cover - c.fit) * e
        val pw = c.bw * sc; val ph = c.bh * sc
        val dx = (c.w - pw) / 2f; val dy = (c.h - ph) / 2f
        c.m.setScale(sc, sc); c.m.postTranslate(dx, dy)
        c.img?.let { it.imageMatrix = c.m; it.invalidate() }
        // The soft copy is smaller by softDiv: same placement, scaled back up.
        c.soft?.let { s ->
            c.ms.setScale(sc * c.softDiv, sc * c.softDiv); c.ms.postTranslate(dx, dy)
            s.imageMatrix = c.ms; s.alpha = e; s.invalidate()
        }
        // The window is the picture's own bounds on screen (fitted card → whole screen), rounded
        // INWARD: the clip is whole pixels, the picture isn't — rounding outward let the frosted
        // backdrop flicker along the edges (a 1 px shimmer while it grows).
        c.outline.rect = android.graphics.RectF(
            kotlin.math.ceil(maxOf(0f, dx)), kotlin.math.ceil(maxOf(0f, dy)),
            kotlin.math.floor(minOf(c.w, dx + pw)), kotlin.math.floor(minOf(c.h, dy + ph)))
        if (e > 0f) c.outline.radius = c.maxRadius * (1f - e)
        c.view.invalidateOutline()
    }

    /** [src] blurred with [passes] box blurs of [radius] px (3 passes ≈ Gaussian). Opaque. */
    private fun softCopy(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width; val h = src.height
        var a = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(w * h)
        fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
            val len = if (horizontal) w else h; val lines = if (horizontal) h else w
            val span = 2 * radius + 1
            for (line in 0 until lines) {
                fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) line * w + c else c * w + line }
                var r = 0; var g = 0; var bl = 0
                for (i in -radius..radius) { val p = from[at(i)]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF }
                for (i in 0 until len) {
                    to[at(i)] = (0xFF shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (bl / span)
                    val out = from[at(i - radius)]; val inn = from[at(i + radius + 1)]
                    r += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bl += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
        repeat(passes) { pass(a, b, true); pass(b, a, false) }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun radiusFor(scale: Float, maxRadius: Float, restRadius: Float): Float {
        val cardness = 1f - ((scale - CARD_SCALE) / (1f - CARD_SCALE)).coerceIn(0f, 1f)
        return restRadius + (maxRadius - restRadius) * cardness
    }

    /** Where [t] sits inside [a, b], clamped to 0..1. */
    private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun removeAll(layers: List<Layer>) {
        for (l in layers) try { l.wm.removeView(l.root) } catch (_: Exception) {}   // no "if attached": a just-added view is not attached yet
    }

    /** Runs [block] on the animation thread and waits up to [timeoutMs]; false = not done yet. */
    private fun runOnMainAndWait(timeoutMs: Long = 4000, block: () -> Unit): Boolean {
        if (Looper.myLooper() == main.looper) { block(); return true }
        val lock = Object(); var f = false
        main.post { try { block() } finally { synchronized(lock) { f = true; lock.notifyAll() } } }
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (!f) {
                val left = end - android.os.SystemClock.uptimeMillis()
                if (left <= 0) break
                try { lock.wait(left) } catch (_: InterruptedException) {}
            }
            return f
        }
    }
}

private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
