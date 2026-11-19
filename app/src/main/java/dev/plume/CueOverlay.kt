package dev.plume

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The one shell line that gives Cue both accesses: notification access and drawing over other apps. */
internal fun cueGrant(pkg: String) =
    "cmd notification allow_listener $pkg/$pkg.MediaListener; appops set $pkg SYSTEM_ALERT_WINDOW allow"

/** What Cue shows over other apps at [now]: only alerts, calls and a finished timer, never music or scores. */
internal fun cueOverlayPick(items: List<CueItem>, now: Long): CueItem? =
    cuePeek(items.filter { it.kind == CueKind.CALL || it.kind == CueKind.ALERT || it.kind == CueKind.TIMER_DONE }, now)

/**
 * Cue's peek over other apps ("Alerts over apps"). At rest the pill never takes focus, so the remote keeps driving
 * the app underneath. While it shows, Up opens it: Plume's Home service sees the key (Android lets an accessibility
 * service filter keys), keeps it from the app, and the pill becomes a card that has focus, with the alert's buttons,
 * Open and Dismiss. Back or Dismiss removes it. Without the service (off, or Fire OS, which passes it no keys), the
 * pill only shows. On Home, Cue itself shows the same item, so this stays away.
 * Glass needs the wallpaper behind it, which another app's screen is not, so the pill is a dark tint.
 */
internal object CueOverlay {
    private const val OPEN_IDLE_MS = 20_000L // an open card with no key pressed this long closes, so it can't stay forever

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var ctx: Context? = null
    private var view: View? = null
    private var shown: String? = null
    private var item: CueItem? = null
    private var open = false
    private var home = false
    private var swallowUp = false
    private val recheck = Runnable { update() }
    private val idle = Runnable { close() }

    /** The pill is on screen and can be opened with Up; the Home service filters keys only then, if it doesn't anyway. */
    val peeking get() = view != null && !open

    fun attach(c: Context) {
        ctx = c.applicationContext
    }

    /** Plume's home screen is in front ([front]) or not. */
    fun home(front: Boolean) {
        home = front
        update()
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

    /** Main thread. Shows, changes or hides the peek to match the current items. */
    fun update() {
        val c = ctx ?: return
        main.removeCallbacks(recheck)
        val now = System.currentTimeMillis()
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        val allowed = !home && prefs.getBoolean("isl_on", true) && prefs.getBoolean("isl_overlay", false) && Settings.canDrawOverlays(c)
        val items = if (allowed) CueAlerts.items(now, prefs) + CueTimers.items(now) else emptyList()
        // An open card stays while its item lasts; a closed pill only while it peeks.
        if (open) {
            if (items.none { it.key == item?.key && now < it.until }) close()
            return
        }
        val next = cueOverlayPick(items, now) ?: return hide()
        if (next.peekUntil != Long.MAX_VALUE) main.postDelayed(recheck, next.peekUntil - now)
        if (next.shown == shown) return
        hide(animate = false)
        val v = pill(c, next, HomeOverride.running != null && !HomeOverride.fireTv(c))
        if (!add(c, v, focusable = false)) return
        view = v
        shown = next.shown
        item = next
        HomeOverride.running?.applyKeyFilter()
        v.alpha = 0f
        v.translationY = -dp(c, 16).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun expand() {
        val c = ctx ?: return
        val it = item ?: return
        if (!peeking) return
        main.removeCallbacks(recheck)
        hide(animate = false)
        val v = card(c, it)
        if (!add(c, v, focusable = true)) return
        view = v
        item = it
        open = true
        HomeOverride.running?.applyKeyFilter()
        main.postDelayed(idle, OPEN_IDLE_MS)
        v.post { v.findFocus() ?: v.focusSearch(View.FOCUS_DOWN)?.requestFocus() }
    }

    /** The card goes; the item stays in Cue (Home still lists it) unless [dismiss]. */
    private fun close(dismiss: Boolean = false) {
        val c = ctx
        val it = item
        main.removeCallbacks(idle)
        open = false
        hide()
        if (dismiss && c != null && it != null) {
            if (it.kind == CueKind.TIMER_DONE) CueTimers.cancelTimer(c) else CueAlerts.removed(it.key)
        }
        HomeOverride.running?.applyKeyFilter()
    }

    private fun add(c: Context, v: View, focusable: Boolean) = try {
        c.getSystemService(WindowManager::class.java).addView(v, params(c, focusable))
        true
    } catch (e: RuntimeException) { // access taken away between the check and here
        false
    }

    private fun hide(animate: Boolean = true) {
        val v = view ?: return
        val c = ctx ?: return
        view = null
        shown = null
        if (!open) item = null
        val remove = { try { c.getSystemService(WindowManager::class.java).removeView(v) } catch (_: RuntimeException) {} }
        if (animate) v.animate().alpha(0f).translationY(-dp(c, 16).toFloat()).setDuration(180).withEndAction(remove).start()
        else remove()
    }

    private fun params(c: Context, focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(c, 24)
        title = "Cue"
    }

    private fun surface(c: Context) = GradientDrawable().apply {
        cornerRadius = dp(c, CUE_ROUND).toFloat()
        setColor(0xE61B1C20.toInt())
        setStroke(1, 0x26FFFFFF)
    }

    private fun picture(c: Context, item: CueItem, size: Int) = ImageView(c).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        when {
            item.image != null -> setImageBitmap(item.image)
            item.icon != 0 -> {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                if (item.mono) setColorFilter(Color.WHITE)
            }
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
        }
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    private fun words(c: Context, item: CueItem, lines: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(c, item.title, 15f, 1f, lines).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        item.sub?.let { addView(text(c, it, 12f, 0.7f, lines)) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(c, 12)
        }
    }

    private fun pill(c: Context, item: CueItem, canOpen: Boolean) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 8), dp(c, 8), dp(c, 22), dp(c, 8))
        background = surface(c)
        contentDescription = item.description ?: item.title
        addView(picture(c, item, 36))
        addView(words(c, item, 1))
        if (canOpen) addView(text(c, c.getString(R.string.isl_overlay_hint), 12f, 0.6f, 1), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(c, 16) })
    }

    /** The open card: the whole text, the alert's own buttons, Open and Dismiss. Back closes it and dismisses. */
    private fun card(c: Context, item: CueItem) = object : LinearLayout(c) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            main.removeCallbacks(idle)
            main.postDelayed(idle, OPEN_IDLE_MS)
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) close(dismiss = true)
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        background = surface(c)
        contentDescription = item.description ?: item.title
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(picture(c, item, 48))
            addView(words(c, item, 4))
        })
        val buttons = item.actions.map { a -> (a.label ?: a.description ?: "") to { a.run(); close() } } +
            listOfNotNull(item.open?.let { open -> c.getString(R.string.isl_overlay_open) to { open(); close(dismiss = true) } }) +
            (c.getString(R.string.isl_overlay_dismiss) to { close(dismiss = true) })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(c, 12), 0, 0)
            for ((label, run) in buttons.filter { it.first.isNotEmpty() }) addView(button(c, label, run), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 8) })
        })
    }

    private fun button(c: Context, label: String, run: () -> Unit) = TextView(c).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isFocusable = true
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        fun look(focused: Boolean) {
            setTextColor(if (focused) 0xFF111214.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 18).toFloat()
                setColor(if (focused) Color.WHITE else 0x26FFFFFF)
            }
        }
        look(false)
        setOnFocusChangeListener { _, f -> look(f) }
        setOnClickListener { run() }
    }

    private fun text(c: Context, s: String, size: Float, alpha: Float, lines: Int) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        maxLines = lines
        maxWidth = dp(c, 360)
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(c, 2).toFloat(), 0f, dp(c, 1).toFloat(), 0x66000000)
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
