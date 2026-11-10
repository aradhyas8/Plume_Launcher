package dev.plume

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Framework-only device tests: run connectedDebugAndroidTest on an awake TV with HDMI and tuner inputs. */
class ActivityTest : Instrumentation() {
    private var current = 0
    private var failures = 0
    private var previewOnly = false
    private var transferOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        previewOnly = arguments?.getString("class") == GalleryPreviewTest::class.java.name
        transferOnly = arguments?.getString("class") == SettingsTransferCheck::class.java.name
        start()
    }

    override fun onStart() {
        if (transferOnly) {
            test("settingsTransferRoundTripAndRejectedFiles") { SettingsTransferCheck(this).run() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        if (previewOnly) {
            test("galleryPreviewControlsAndReturn") { GalleryPreviewTest(this).run() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "pm grant ${targetContext.packageName} android.permission.READ_TV_LISTINGS")).use { it.readBytes() }
        test("missingInputShowsError") { checkInput(null, R.string.input_failed) }
        test("unknownInputShowsError") { checkInput("invalid/.Input/HW5", R.string.input_failed) }
        test("tunerInputShowsError") {
            val tuner = targetContext.getSystemService(TvInputManager::class.java).tvInputList
                .first { it.type == TvInputInfo.TYPE_TUNER }
            checkInput(tuner.id, R.string.input_failed)
        }
        test("hdmiOpensAndBackClosesPlayer") {
            val hdmi = targetContext.getSystemService(TvInputManager::class.java).tvInputList
                .first { it.type == TvInputInfo.TYPE_HDMI }
            checkInput(hdmi.id, null)
        }
        test("remoteActionOpensAllAppsOnFreshLaunch") {
            withAllApps { activity ->
                check(!activity.intent.getBooleanExtra(Remap.OPEN_ALL_APPS, false)) { "Request was not consumed" }
                check(activity.currentFocus?.isDescendantOf(activity.findViewById(R.id.allApps)) == true)
            }
        }
        test("repeatedRemoteActionAndHomeAndBack") {
            withAllApps { activity ->
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back did not close All apps") { !activity.findViewById<View>(R.id.allApps).isShown }
                requestAllApps(activity)
                val focused = activity.currentFocus
                requestAllApps(activity)
                check(activity.currentFocus === focused) { "Repeated request moved focus" }
                val monitor = addMonitor(MainActivity::class.java.name, null, false)
                try {
                    runOnMainSync {
                        targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    val home = checkNotNull(monitor.waitForActivityWithTimeout(3_000)) { "Home did not resume Plume" }
                    check(home === activity) { "Home opened a duplicate activity" }
                    await("Home did not close All apps") { !home.findViewById<View>(R.id.allApps).isShown }
                    if (home !== activity) runOnMainSync { home.finish() }
                } finally {
                    removeMonitor(monitor)
                }
            }
        }
        test("remoteActionClosesLauncherSettings") {
            withAllApps { activity ->
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back did not close All apps") { !activity.findViewById<View>(R.id.allApps).isShown }
                runOnMainSync { activity.findViewById<View>(R.id.launcherSettings).performClick() }
                await("Settings did not open") { activity.findViewById<View>(R.id.settingsScrim).isShown }
                requestAllApps(activity)
                check(!activity.findViewById<View>(R.id.settingsScrim).isShown) { "Settings covers All apps" }
            }
        }
        test("backgroundRequestSurvivesThemeRecreation") {
            withAllApps { activity ->
                val other = startActivitySync(Intent(targetContext, InputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val monitor = addMonitor(MainActivity::class.java.name, null, false)
                var rebuilt: Activity? = null
                try {
                    await("Other activity did not gain focus") { other.hasWindowFocus() }
                    targetContext.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                        .putString("theme", "dark").putInt("return_settings", R.string.theme)
                        .putBoolean("reopen_settings", true).commit()
                    runOnMainSync { targetContext.startActivity(allAppsIntent()) }
                    val until = SystemClock.uptimeMillis() + 8_000
                    do {
                        rebuilt = monitor.waitForActivityWithTimeout(maxOf(1L, until - SystemClock.uptimeMillis()))
                    } while (rebuilt === activity && SystemClock.uptimeMillis() < until)
                    val replacement = checkNotNull(rebuilt) { "Theme did not recreate the activity" }
                    await("Recreated activity did not show All apps") {
                        replacement.hasWindowFocus() && replacement.findViewById<View>(R.id.allApps).isShown
                    }
                    check(activity !== replacement)
                    check(!replacement.findViewById<View>(R.id.settingsScrim).isShown)
                } finally {
                    removeMonitor(monitor)
                    runOnMainSync { rebuilt?.finish(); other.finish() }
                }
            }
        }
        test("galleryPreviewControlsAndReturn") { GalleryPreviewTest(this).run() }
        test("settingsTransferRoundTripAndRejectedFiles") { SettingsTransferCheck(this).run() }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
    }

    private fun test(name: String, action: () -> Unit) {
        val result = Bundle().apply {
            putString("class", this@ActivityTest.javaClass.name)
            putString("test", name)
            putInt("numtests", if (previewOnly || transferOnly) 1 else 10)
            putInt("current", ++current)
        }
        sendStatus(1, result)
        try {
            action()
            sendStatus(0, result)
        } catch (e: Throwable) {
            failures++
            result.putString("stack", e.stackTraceToString())
            sendStatus(-2, result)
        }
    }

    private fun allAppsIntent(): Intent {
        val service = object : AccessibilityService() {
            var launched: Intent? = null
            init { attachBaseContext(targetContext) }
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
            override fun startActivity(intent: Intent) { launched = intent }
        }
        Remap.run(service, "open:allapps")
        return checkNotNull(service.launched).also {
            check(it.action == Intent.ACTION_MAIN && it.hasCategory(Intent.CATEGORY_HOME))
            check(it.getBooleanExtra(Remap.OPEN_ALL_APPS, false))
            check(it.component?.className == MainActivity::class.java.name)
        }
    }

    private fun withAllApps(test: (Activity) -> Unit) {
        targetContext.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
            .putString("theme", "light").putBoolean("setup_shown", true).commit()
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        val activity = startActivitySync(allAppsIntent())
        try {
            try {
                await("All apps did not open") {
                    activity.hasWindowFocus() && activity.findViewById<View>(R.id.allApps).isShown &&
                        activity.findViewById<ViewGroup>(R.id.allAppsGrid).childCount > 0
                }
            } catch (e: IllegalStateException) {
                var details = ""
                runOnMainSync {
                    details = "All apps state: action=${activity.intent.action}, focus=${activity.hasWindowFocus()}, " +
                        "drawer=${activity.findViewById<View>(R.id.allApps).visibility}, settings=${activity.findViewById<View>(R.id.settingsScrim).visibility}"
                }
                error(details)
            }
            test(activity)
        } finally {
            val last = monitor.lastActivity
            removeMonitor(monitor)
            runOnMainSync { last?.finish(); activity.finish() }
            waitForIdleSync()
        }
    }

    private fun requestAllApps(activity: Activity) {
        val previous = activity.intent
        runOnMainSync { targetContext.startActivity(allAppsIntent()) }
        await("Remote request did not show All apps") {
            activity.intent !== previous && !activity.intent.getBooleanExtra(Remap.OPEN_ALL_APPS, false) &&
                activity.findViewById<View>(R.id.allApps).isShown
        }
    }

    private fun View.isDescendantOf(parent: View): Boolean =
        this === parent || (this.parent as? View)?.isDescendantOf(parent) == true

    private fun checkInput(id: String?, error: Int?) {
        val activity = startActivitySync(Intent(targetContext, InputActivity::class.java)
            .putExtra("input", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitForIdleSync()
            await("Player did not gain window focus") { activity.hasWindowFocus() }
            runOnMainSync {
                val texts = texts(activity.window.decorView)
                if (error != null) check(targetContext.getString(error) in texts) { "Missing input error: $texts" }
                else check(targetContext.getString(R.string.input_failed) !in texts) { "HDMI validation failed" }
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back did not close the player") { activity.isFinishing || activity.isDestroyed }
        } finally {
            runOnMainSync { activity.finish() }
            waitForIdleSync()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 3_000
        do {
            var done = false
            runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        error(message)
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
