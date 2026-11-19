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
 * Cue's peek over other apps ("Alerts over apps"). The window never takes focus or touches, so the remote keeps
 * driving the app underneath; it only shows, then goes. On Home, Cue itself shows the same item, so this stays away.
 * Glass needs the wallpaper behind it, which another app's screen is not, so the pill is a dark tint.
 */
internal object CueOverlay {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var ctx: Context? = null
    private var view: View? = null
    private var shown: String? = null
    private var home = false
    private val recheck = Runnable { update() }

    fun attach(c: Context) {
        ctx = c.applicationContext
    }

    /** Plume's home screen is in front ([front]) or not. */
    fun home(front: Boolean) {
        home = front
        update()
    }

    /** Main thread. Shows, changes or hides the peek to match the current items. */
    fun update() {
        val c = ctx ?: return
        main.removeCallbacks(recheck)
        val now = System.currentTimeMillis()
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        val item = if (home || !prefs.getBoolean("isl_on", true) || !prefs.getBoolean("isl_overlay", false) ||
            !Settings.canDrawOverlays(c)) null
        else cueOverlayPick(CueAlerts.items(now, prefs) + CueTimers.items(now), now)
        if (item == null) return hide()
        if (item.peekUntil != Long.MAX_VALUE) main.postDelayed(recheck, item.peekUntil - now)
        if (item.shown == shown) return
        hide(animate = false)
        val v = pill(c, item)
        try {
            c.getSystemService(WindowManager::class.java).addView(v, params(c))
        } catch (e: RuntimeException) { // access taken away between the check and here
            return
        }
        view = v
        shown = item.shown
        v.alpha = 0f
        v.translationY = -dp(c, 16).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun hide(animate: Boolean = true) {
        val v = view ?: return
        val c = ctx ?: return
        view = null
        shown = null
        val remove = { try { c.getSystemService(WindowManager::class.java).removeView(v) } catch (_: RuntimeException) {} }
        if (animate) v.animate().alpha(0f).translationY(-dp(c, 16).toFloat()).setDuration(180).withEndAction(remove).start()
        else remove()
    }

    private fun params(c: Context) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(c, 24)
        title = "Cue"
    }

    private fun pill(c: Context, item: CueItem) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 8), dp(c, 8), dp(c, 22), dp(c, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(c, CUE_ROUND).toFloat()
            setColor(0xE61B1C20.toInt())
            setStroke(1, 0x26FFFFFF)
        }
        contentDescription = item.description ?: item.title
        addView(ImageView(c).apply {
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
        }, LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, item.title, 15f, 1f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            item.sub?.let { addView(text(c, it, 12f, 0.7f)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(c, 12)
        })
    }

    private fun text(c: Context, s: String, size: Float, alpha: Float) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        this.alpha = alpha
        maxLines = 1
        maxWidth = dp(c, 360)
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(c, 2).toFloat(), 0f, dp(c, 1).toFloat(), 0x66000000)
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
