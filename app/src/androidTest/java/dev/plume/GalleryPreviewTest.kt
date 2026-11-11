package dev.plume

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup

/** Framework device check, selected through ActivityTest with -e class dev.plume.GalleryPreviewTest. */
class GalleryPreviewTest(private val runner: Instrumentation) {
    fun run() = with(runner) {
        val prefs = targetContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        val setup = prefs.all["setup_shown"] as? Boolean
        val previous = Settings.Secure.getString(targetContext.contentResolver, "screensaver_components")
        val secureKeys = listOf("screensaver_enabled", "screensaver_default_component", "contextual_screen_off_timeout")
        val settings = secureKeys.associateWith { Settings.Secure.getString(targetContext.contentResolver, it) }
        val timeout = Settings.System.getString(targetContext.contentResolver, "screen_off_timeout")
        var monitor = addMonitor(GalleryPreview::class.java.name, null, false)
        var home: Activity? = null
        var preview: Activity? = null
        var failure: String? = null
        try {
            prefs.edit().putBoolean("setup_shown", true).commit()
            shell("settings put secure screensaver_components dev.plume/dev.plume.GalleryDream")
            shell("pm grant dev.plume android.permission.READ_TV_LISTINGS")
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val launcher = home
            await { launcher.hasWindowFocus() }
            runOnMainSync { launcher.findViewById<View>(R.id.launcherSettings).performClick() }
            repeat(2) {
                removeMonitor(monitor)
                monitor = addMonitor(GalleryPreview::class.java.name, null, false)
                runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("testScreensaver").apply { isAccessible = true }.invoke(launcher)
                }
                preview = checkNotNull(monitor.waitForActivityWithTimeout(3_000)) { "Plume preview did not start" }
                val shown = preview!!
                await { shown.hasWindowFocus() }
                runOnMainSync { check(hasSurface(shown.window.decorView)) { "Preview has no playback surface" } }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                check(!shown.isFinishing) { "Arrow keys closed preview" }
                if (it == 0) sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                else runOnMainSync { targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                await { shown.isFinishing && launcher.hasWindowFocus() }
                waitForIdleSync()
                if (it == 0) await { launcher.findViewById<View>(R.id.settingsScrim).isShown }
                check(Settings.Secure.getString(targetContext.contentResolver, "screensaver_components") == "dev.plume/dev.plume.GalleryDream")
                check(secureKeys.associateWith { key -> Settings.Secure.getString(targetContext.contentResolver, key) } == settings)
                check(Settings.System.getString(targetContext.contentResolver, "screen_off_timeout") == timeout)
            }
        } catch (e: Throwable) {
            failure = e.stackTraceToString()
        } finally {
            runOnMainSync { preview?.finish(); home?.finish() }
            removeMonitor(monitor)
            if (previous == null) shell("settings delete secure screensaver_components")
            else shell("settings put secure screensaver_components '$previous'")
            prefs.edit().apply { if (setup == null) remove("setup_shown") else putBoolean("setup_shown", setup) }.commit()
        }
        check(failure == null) { failure.orEmpty() }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun hasSurface(view: View): Boolean = view is SurfaceView ||
        (view is ViewGroup && (0 until view.childCount).any { hasSurface(view.getChildAt(it)) })

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        do {
            var done = false
            runner.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        error("Activity did not reach the expected state")
    }
}
