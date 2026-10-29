package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app). On Fire TV that cover is the only way: Fire OS keeps Home to itself.
 */
class HomeOverride : AccessibilityService() {
    override fun onServiceConnected() {
        running = this
        applyKeyFilter()
        HomeAlarm.engage(this)
    }

    /**
     * Seeing keys costs volume its hold on TVs: Android acts on Volume before any service sees it and the key never
     * repeats. So Smooth volume turns key filtering off, unless remote keys need it (set, or one being learned).
     * Home is then caught by the Google TV window watcher below, which shows Google TV for about a second.
     * Fire OS never passes remote keys to us, so there filtering is always off.
     */
    fun applyKeyFilter() {
        val filter = !fireTv(this) && (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("smooth_volume", false) ||
            Remap.all(this).isNotEmpty() || Remap.learn != null)
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = serviceInfo.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /** Turned off, from Plume's switch or Android's Accessibility settings: the app mustn't turn it back on. */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        handler.removeCallbacks(jump)
        HomeAlarm.release(this)
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return remap(e)
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome() else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    /** Keys the user changed in Plume settings (see [Remap]); any other key goes on to the system untouched. */
    private fun remap(e: KeyEvent): Boolean {
        if (Remap.kept(e)) return false
        val id = Remap.id(e)
        if (id == Remap.pending) { // the key just learned: its repeats and release never reach anyone
            if (e.action == KeyEvent.ACTION_UP) Remap.pending = null
            return true
        }
        Remap.learn?.let { learn ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                Remap.pending = id
                learn(e)
            }
            return true
        }
        val m = Remap.all(this)[id] ?: return false
        // Decided on release, like Home: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val action = if (e.eventTime - e.downTime >= LONG_PRESS_MS && m.long.isNotEmpty()) m.long else m.short
            if (action.isNotEmpty()) Remap.run(this, action)
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watch = HomeWatch()
    private val jump = Runnable { goHome() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        handler.removeCallbacks(jump) // any other window opening means Home wasn't where we were going
        watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())?.let { handler.postDelayed(jump, it) }
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome() {
        val home = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HomeAlarm.start(this, home)) startActivity(home) // Fire TV: the alarm skips Android's 5 s hold after Home
    }

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val LONG_PRESS_MS = 500L

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
