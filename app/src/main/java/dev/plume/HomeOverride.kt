package dev.plume

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Display
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
        loadHomes()
        if (!listening) {
            listening = true
            registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
        }
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
        pendingHome = null
        handler.removeCallbacks(finishCapture)
        clearRecentPreviews()
        if (listening) {
            listening = false
            unregisterReceiver(packageReceiver)
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("home_off", true).apply()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SUSPENDED != 0) return false
        if (e.keyCode != KeyEvent.KEYCODE_HOME) { homePress.reset(); return remap(e) }
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) {
                if (homePress.released(e.eventTime)) {
                    val capture = pendingHome
                    if (capture == null) goHome(recents = true) else capture.recents = true
                } else requestHome(recents = false)
            } else {
                homePress.reset()
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
        }
        if (e.isCanceled) homePress.reset()
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
        val m = Remap.lookup(Remap.all(this), e.keyCode, e.scanCode) ?: return false
        // Decided on release, like Home: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            val action = if (e.eventTime - e.downTime >= LONG_PRESS_MS && m.long.isNotEmpty()) m.long else m.short
            if (action.isNotEmpty()) Remap.run(this, action)
        }
        return true
    }

    private val handler = Handler(Looper.getMainLooper())
    private val homePress = HomePress()
    private var lastForeground: String? = null
    private var foregroundApp: String? = null
    private val recentPreviews = LruCache<String, Bitmap>(6)
    private class HomeCapture(val pkg: String, var recents: Boolean)
    private var pendingHome: HomeCapture? = null
    private val finishCapture = Runnable { pendingHome?.let(::finishHome) }
    private val watch by lazy { HomeWatch(fireTv(this)) { homes } }
    private var homes = emptyMap<String, Set<String>>()
    private var listening = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadHomes()
    }
    private val jump = Runnable { watch.jumped(); goHome() }

    /** Plume (the home screen or a remote key) starts an activity: see [HomeWatch.launched]. */
    fun launched() = watch.launched(SystemClock.uptimeMillis())

    override fun startActivity(intent: Intent?) { launched(); super.startActivity(intent) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != lastForeground) {
            lastForeground = pkg
            foregroundApp = pkg.takeIf {
                it != packageName && it !in homes && it != "android" && it != "com.android.systemui" &&
                    (packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it)) != null
            }
            if (foregroundApp != null) {
                getSharedPreferences("home", MODE_PRIVATE).edit().putLong("t_$pkg", System.currentTimeMillis()).apply()
            }
        }
        val after = watch.opened(pkg, e.className?.toString(), SystemClock.uptimeMillis())
        if (after == HomeWatch.KEEP) return // the waiting jump stays
        handler.removeCallbacks(jump) // any other window opening means Home wasn't where we were going
        after?.let { handler.postDelayed(jump, it) }
    }

    fun openRecentApps() = requestHome(recents = true)

    fun recentPreview(pkg: String): Bitmap? = if (previewsEnabled()) recentPreviews.get(pkg) else null

    fun clearRecentPreviews() = recentPreviews.evictAll()

    private fun previewsEnabled() = getSharedPreferences("home", MODE_PRIVATE).getBoolean(RECENT_PREVIEWS, false)

    private fun requestHome(recents: Boolean) {
        pendingHome?.let { it.recents = recents; return }
        val pkg = foregroundApp
        if (Build.VERSION.SDK_INT < 30 || pkg == null || !previewsEnabled()) {
            goHome(recents)
            return
        }
        val capture = HomeCapture(pkg, recents)
        pendingHome = capture
        recentPreviews.remove(pkg)
        handler.postDelayed(finishCapture, 220)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        result.hardwareBuffer.use { buffer ->
                            if (pendingHome !== capture || lastForeground != capture.pkg || !previewsEnabled()) return@use
                            val source = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@use
                            val readable = try {
                                source.copy(Bitmap.Config.ARGB_8888, false)
                            } finally { source.recycle() } ?: return@use
                            val width = minOf(640, readable.width)
                            val height = (readable.height * width / readable.width).coerceAtLeast(1)
                            val preview = Bitmap.createScaledBitmap(readable, width, height, true)
                            if (preview !== readable) readable.recycle()
                            if (hasPreviewContent(preview)) recentPreviews.put(capture.pkg, preview) else preview.recycle()
                        }
                    } catch (_: RuntimeException) {
                        // A capture failure must not prevent Home from opening.
                    } catch (_: OutOfMemoryError) {
                        clearRecentPreviews()
                    } finally {
                        finishHome(capture)
                    }
                }

                override fun onFailure(errorCode: Int) = finishHome(capture)
            })
        } catch (_: SecurityException) {
            finishHome(capture)
        }
    }

    private fun finishHome(capture: HomeCapture) {
        if (pendingHome !== capture) return
        pendingHome = null
        handler.removeCallbacks(finishCapture)
        goHome(capture.recents)
    }

    private fun hasPreviewContent(bitmap: Bitmap): Boolean {
        // shortcut: sample the center for blacked-out captures, use window capture if the minimum SDK reaches 34.
        for (y in 2..6) for (x in 2..6) {
            val color = bitmap.getPixel((bitmap.width - 1) * x / 8, (bitmap.height - 1) * y / 8)
            if (maxOf(Color.red(color), Color.green(color), Color.blue(color)) > 16) return true
        }
        return false
    }

    /** The stock launchers' home screens, asked of the system when the service starts and when a package changes. */
    private fun loadHomes() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homes = stockHomes(packageManager.queryIntentActivities(home, 0).map {
            val a = it.activityInfo
            HomeEntry(a.packageName, a.name, a.targetActivity)
        })
    }

    // Marked as Home so the setup check can tell it from Plume being opened from the apps list.
    private fun goHome(recents: Boolean = false) {
        if (packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SUSPENDED != 0) return
        super.startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .putExtra(Remap.OPEN_RECENT_APPS, recents).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Plume's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val LONG_PRESS_MS = 500L
        const val RECENT_PREVIEWS = "recent_window_previews"

        /** Fire OS keeps Amazon's home and ignores the home role; Plume can only cover it (see [HomeWatch]). */
        fun fireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
