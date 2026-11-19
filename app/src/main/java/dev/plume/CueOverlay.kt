package dev.plume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.LinearLayout

/** The one shell line that gives Cue both accesses: notification access and drawing over other apps. */
internal fun cueGrant(pkg: String) =
    "cmd notification allow_listener $pkg/$pkg.MediaListener; appops set $pkg SYSTEM_ALERT_WINDOW allow"

/** What Cue shows over other apps at [now]: only alerts, calls and a finished timer, never music or scores. */
internal fun cueOverlayPick(items: List<CueItem>, now: Long): CueItem? =
    cuePeek(items.filter { it.kind == CueKind.CALL || it.kind == CueKind.ALERT || it.kind == CueKind.TIMER_DONE }, now)

/**
 * Cue over other apps ("Alerts over apps"): the same body as on Home (cue_body, drawn by [cueBind]), on a tint in the
 * theme's colours, as there is no wallpaper for glass. The pill never takes focus, so the remote keeps driving the
 * app. While it shows, Up opens it: Plume's Home service sees the key (Android lets an accessibility service filter
 * keys) and keeps it from the app; the window then takes focus and Left, Right and OK work its buttons as on Home.
 * Back closes it. Without the service (off, or Fire OS, which passes it no keys) the pill only shows.
 */
internal object CueOverlay {
    private const val OPEN_IDLE_MS = 20_000L // an open card with no key pressed this long closes

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var ctx: Context? = null
    private var root: LinearLayout? = null
    private var item: CueItem? = null
    private var open = false
    private var picked = 0
    private var home = false
    private var swallowUp = false
    private var closed: String? = null // the item just closed, so it doesn't come back while it still peeks
    private val recheck = Runnable { update() }
    private val idle = Runnable { close() }

    /** The pill is on screen and Up can open it; the Home service filters keys then, if it doesn't anyway. */
    val peeking get() = root != null && !open

    fun attach(c: Context) {
        ctx = c.applicationContext
    }

    /** Plume's home screen is in front ([front]) or not: there Cue itself shows the item. */
    fun home(front: Boolean) {
        home = front
        if (front) close() else update()
    }

    /** From the Home service, on the main thread. True: the key is Cue's, and the app never sees it. */
    fun key(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        if (e.action == KeyEvent.ACTION_UP && swallowUp) { swallowUp = false; return true }
        if (e.action != KeyEvent.ACTION_DOWN || !peeking) return false
        swallowUp = true
        main.post { expand() }
        return true
    }

    /** Main thread. Shows, changes or hides Cue over the app to match the current items. */
    fun update() {
        val c = ctx ?: return
        main.removeCallbacks(recheck)
        val now = System.currentTimeMillis()
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        val allowed = !home && prefs.getBoolean("isl_on", true) && prefs.getBoolean("isl_overlay", false) && Settings.canDrawOverlays(c)
        val items = if (allowed) CueAlerts.items(now, prefs) + CueTimers.items(now) else emptyList()
        if (open) { // the card stays while its item lasts (an open alert is held), with its latest text
            item = items.firstOrNull { it.key == item?.key } ?: return close()
            return draw(c)
        }
        val next = cueOverlayPick(items, now)?.takeIf { it.shown != closed } ?: return hide()
        if (next.peekUntil != Long.MAX_VALUE) main.postDelayed(recheck, next.peekUntil - now)
        if (next.shown == item?.shown) return
        item = next
        if (root == null) show(c) else draw(c)
    }

    @SuppressLint("InflateParams") // a window's root has no parent
    private fun show(c: Context) {
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            setOnKeyListener { _, code, e -> card(code, e) }
        }
        LayoutInflater.from(c).inflate(R.layout.cue_body, v, true)
        root = v
        draw(c)
        try {
            c.getSystemService(WindowManager::class.java).addView(v, params(c))
        } catch (e: RuntimeException) { // access taken away between the check and here
            root = null
            return
        }
        HomeOverride.running?.applyKeyFilter()
        v.alpha = 0f
        v.translationY = -dp(c, 16).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun draw(c: Context) {
        val v = root ?: return
        val it = item ?: return
        val dark = prefersDark(c.getSharedPreferences("home", Context.MODE_PRIVATE))
        val ink = if (dark) MainActivity.DARK_TEXT else 0xFF111214.toInt()
        v.background = GradientDrawable().apply {
            cornerRadius = dp(c, CUE_ROUND).toFloat()
            setColor(if (dark) 0xE61B1C20.toInt() else 0xF2F5F5F7.toInt())
        }
        cueShape(v, open)
        cueBind(v, it, open, ink, { t -> t.setShadowLayer(0f, 0f, 0f, 0); t.setTextColor(ink) }, it.image, null,
            if (open) picked else -1, ink and 0xFFFFFF or 0x2E000000)
    }

    private fun expand() {
        val c = ctx ?: return
        val v = root ?: return
        val it = item ?: return
        if (open) return
        open = true
        picked = 0
        CueAlerts.held = it.key
        main.removeCallbacks(recheck)
        draw(c)
        try { c.getSystemService(WindowManager::class.java).updateViewLayout(v, params(c)) } catch (_: RuntimeException) {}
        v.requestFocus()
        HomeOverride.running?.applyKeyFilter()
        main.postDelayed(idle, OPEN_IDLE_MS)
    }

    /** The open card's keys: Left and Right pick a button, OK presses it, Back closes. */
    private fun card(code: Int, e: KeyEvent): Boolean {
        val c = ctx ?: return false
        val actions = item?.actions ?: return false
        main.removeCallbacks(idle)
        main.postDelayed(idle, OPEN_IDLE_MS)
        if (code == KeyEvent.KEYCODE_HOME) return false.also { if (e.action == KeyEvent.ACTION_UP) close() }
        if (e.action != KeyEvent.ACTION_UP) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> picked = (picked - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> picked = (picked + 1).coerceAtMost(actions.size - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { actions.getOrNull(picked)?.run?.invoke(); return true.also { close() } }
            KeyEvent.KEYCODE_BACK -> return true.also { close() }
            else -> return true
        }
        draw(c)
        return true
    }

    private fun close() {
        main.removeCallbacks(idle)
        val was = open
        closed = item?.shown
        open = false
        hide()
        if (was) {
            CueAlerts.held = null
            Cue.changed() // the alert let go may be over by now
        }
    }

    private fun hide() {
        val v = root ?: return
        val c = ctx ?: return
        root = null
        item = null
        HomeOverride.running?.applyKeyFilter()
        v.animate().alpha(0f).translationY(-dp(c, 16).toFloat()).setDuration(180).withEndAction {
            try { c.getSystemService(WindowManager::class.java).removeView(v) } catch (_: RuntimeException) {}
        }.start()
    }

    private fun params(c: Context) = WindowManager.LayoutParams(
        if (open) dp(c, 400) else WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (open) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(c, 24)
        title = "Cue"
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
