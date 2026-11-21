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
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Framework-only device tests: run connectedDebugAndroidTest on an awake TV with HDMI and tuner inputs. */
class ActivityTest : Instrumentation() {
    private var current = 0
    private var failures = 0
    private var previewOnly = false
    private var transferOnly = false
    private var muteOnly = false
    private var picturesOnly = false
    private var homeOnly = false
    private var homeSequence = 0
    private var protectedLaunchOnly = false
    private var cueOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        previewOnly = arguments?.getString("class") == GalleryPreviewTest::class.java.name
        transferOnly = arguments?.getString("class") == SettingsTransferCheck::class.java.name
        muteOnly = arguments?.getString("class") == MuteCheck::class.java.name
        picturesOnly = arguments?.getString("class") == AppPicturesCheck::class.java.name
        homeOnly = arguments?.getString("class") == "dev.plume.ActivityTest#homePopupHomeSequence"
        protectedLaunchOnly = arguments?.getString("class") == "dev.plume.ActivityTest#protectedAppLaunch"
        cueOnly = arguments?.getString("class") == CueSettingsCheck::class.java.name
        start()
    }

    override fun onStart() {
        if (cueOnly) {
            test("cueSettingsRequestsNavigationAndExpiry") { CueSettingsCheck(this).run() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        if (protectedLaunchOnly) {
            test("protectedAppLaunch") { checkProtectedLaunch() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        if (picturesOnly) {
            test("appPictureUploadSizingTransparencyAndReset") { AppPicturesCheck(this).run() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        if (muteOnly) {
            test("remoteMuteTogglesAndPreservesVolume") { MuteCheck(this).run() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
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
        if (homeOnly) {
            var activity: MainActivity? = null
            try {
                test("normalDoubleHome") {
                    targetContext.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                        .putString("theme", "light").putBoolean("setup_shown", true)
                        .putString("seen_notes", targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName)
                        .putString("top_bar", "wifi,sources,apps,system,plume").remove("return_dream").commit()
                    val monitor = addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        android.util.Log.i("HomeSequenceCheck", "STARTUP REQUEST")
                        targetContext.startActivity(homeIntent())
                        activity = monitor.waitForActivityWithTimeout(5_000) as? MainActivity
                        val started = checkNotNull(activity) { "Home Activity did not start within 5 seconds" }
                        await("Startup Home did not reach the Activity") {
                            started.intent.getIntExtra("test_home_sequence", 0) == homeSequence
                        }
                        android.util.Log.i("HomeSequenceCheck", "STARTUP DELIVERED activity=${System.identityHashCode(started)}")
                    } finally {
                        removeMonitor(monitor)
                    }
                    checkHomeSequence(checkNotNull(activity))
                }
                test("homeMainDpadHome") { checkHomeSequence(checkNotNull(activity), KeyEvent.KEYCODE_DPAD_RIGHT) }
                test("homePopupHomeSequence") { checkHomeSequence(checkNotNull(activity), KeyEvent.KEYCODE_DPAD_DOWN, popup = true) }
                test("homeOtherKeyHome") { checkHomeSequence(checkNotNull(activity), KeyEvent.KEYCODE_F1) }
            } finally {
                runOnMainSync { activity?.finish() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
            return
        }
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "pm grant ${targetContext.packageName} android.permission.READ_TV_LISTINGS")).use { it.readBytes() }
        test("protectedAppLaunch") { checkProtectedLaunch() }
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
        test("remoteMuteTogglesAndPreservesVolume") { MuteCheck(this).run() }
        test("appPictureUploadSizingTransparencyAndReset") { AppPicturesCheck(this).run() }
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
    }

    private fun test(name: String, action: () -> Unit) {
        val result = Bundle().apply {
            putString("class", this@ActivityTest.javaClass.name)
            putString("test", name)
            putInt("numtests", if (previewOnly || transferOnly || muteOnly || picturesOnly || protectedLaunchOnly) 1 else 13)
            putInt("current", ++current)
            if (homeOnly) putInt("numtests", 4)
        }
        if (cueOnly) result.putInt("numtests", 1)
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

    private fun checkProtectedLaunch() {
        targetContext.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
            .putString("glass_style", "frosted").putString("theme", "light")
            .putBoolean("setup_shown", true).commit()
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitForIdleSync()
            val startApp = MainActivity::class.java.getDeclaredMethod("startApp", Intent::class.java, View::class.java)
                .apply { isAccessible = true }
            val returnTo = MainActivity::class.java.getDeclaredField("returnTo").apply { isAccessible = true }
            var attempts = 0
            val monitor = object : ActivityMonitor() {
                override fun onStartActivity(intent: Intent): ActivityResult? {
                    attempts++
                    throw SecurityException("Activity requires a permission that Plume does not have")
                }
            }
            addMonitor(monitor)
            try {
                runOnMainSync {
                    returnTo.set(activity, "previous")
                    startApp.invoke(activity, Intent("dev.plume.test.PROTECTED"), null)
                    check(returnTo.get(activity) == "previous") { "Failed launch changed return focus" }
                    check(targetContext.getString(R.string.cant_open_app) in texts(activity.window.decorView)) {
                        "Missing app launch error"
                    }
                }
                check(attempts == 1) { "Denied launch was retried" }
                check(!activity.isFinishing && !activity.isDestroyed) { "Denied launch closed Plume" }
            } finally {
                removeMonitor(monitor)
            }
        } finally {
            runOnMainSync { activity.finish() }
            waitForIdleSync()
        }
    }

    private fun homeIntent() = Intent(targetContext, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("test_home_sequence", ++homeSequence)

    private fun checkHomeSequence(activity: MainActivity, keyCode: Int? = null, popup: Boolean = false) {
        android.util.Log.i("HomeSequenceCheck", "START popup=$popup key=$keyCode activity=${System.identityHashCode(activity)}")
        val homePress = MainActivity::class.java.getDeclaredField("systemHomePress").apply { isAccessible = true }
        val previous = HomePress::class.java.getDeclaredField("previous").apply { isAccessible = true }
        fun armedAt(): Long? = previous.get(homePress.get(activity)) as Long?
        fun home() {
            val intent = homeIntent()
            val expected = intent.getIntExtra("test_home_sequence", 0)
            android.util.Log.i("HomeSequenceCheck", "REQUEST Home $expected")
            targetContext.startActivity(intent)
            await("Home $expected did not reach the existing Activity") {
                activity.intent.getIntExtra("test_home_sequence", 0) == expected && !activity.isDestroyed
            }
            android.util.Log.i("HomeSequenceCheck", "DELIVERED Home $expected")
        }
        check(HomeOverride.running == null) { "Home service bypasses the Activity Home pair" }
        runOnMainSync { activity.setupDialog?.dismiss() }
        await("Home screen did not gain focus") { activity.hasWindowFocus() }
        var drawerOpen = false
        runOnMainSync { drawerOpen = activity.findViewById<View>(R.id.allApps).isShown }
        if (drawerOpen) {
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back did not close Recent apps") { !activity.findViewById<View>(R.id.allApps).isShown }
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_F1)
        runOnMainSync {
            check(armedAt() == null) { "Case started with an armed Home pair" }
            if (popup) activity.homePopup(0, "Test", null, null, "First" to {}, "Second" to {})
        }
        val window = if (popup) checkNotNull(activity.setupDialog?.window) else activity.window
        val callback = window.callback
        try {
            await("Target window did not gain focus") { window.decorView.hasWindowFocus() }
            val delivered = mutableListOf<Int>()
            runOnMainSync {
                check(if (popup) callback === activity.setupDialog else callback === activity) { "Unexpected key route" }
                window.callback = object : Window.Callback by callback {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        val used = callback.dispatchKeyEvent(event)
                        if (event.keyCode == keyCode) delivered += event.action
                        return used
                    }
                }
            }
            home()
            var first = 0L
            runOnMainSync {
                first = checkNotNull(armedAt()) { "First Home did not arm the Activity pair" }
                check(!activity.findViewById<View>(R.id.allApps).isShown) { "First Home opened Recent apps" }
                if (!popup && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    check(activity.findViewById<View>(R.id.wifi).requestFocus())
            }
            if (keyCode != null) {
                await("Target window lost focus after Home") { window.decorView.hasWindowFocus() }
                var before: View? = null
                runOnMainSync { before = window.currentFocus }
                sendKeyDownUpSync(keyCode)
                runOnMainSync {
                    check(delivered == listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) { "Key did not reach the target window: $delivered" }
                    check(armedAt() == null) { "Delivered key did not reset the Activity Home pair" }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                        check(window.currentFocus != null && window.currentFocus !== before) { "D-pad did not move focus" }
                }
            }
            home()
            runOnMainSync {
                val elapsed = SystemClock.uptimeMillis() - first
                // A slow negative test would pass without the key reset.
                check(elapsed <= 350) { "Delivered Home pair exceeded 350 ms: $elapsed ms" }
                val drawer = activity.findViewById<View>(R.id.allApps).isShown
                check(drawer == (keyCode == null)) { "Wrong Recent apps state after Home pair: $drawer" }
                if (keyCode == null) {
                    check(armedAt() == null)
                    check(activity.findViewById<TextView>(R.id.allAppsTitle).text == targetContext.getString(R.string.recent_apps))
                } else {
                    check(checkNotNull(armedAt()) > first) { "Second Home did not start a fresh pair" }
                    if (popup) check(activity.setupDialog?.isShowing == true) { "Home closed the popup" }
                }
                android.util.Log.i("HomeSequenceCheck", "PASS popup=$popup key=$keyCode delivered=$delivered pairMs=$elapsed")
            }
        } finally {
            runOnMainSync { window.callback = callback; activity.setupDialog?.dismiss() }
            android.util.Log.i("HomeSequenceCheck", "END popup=$popup key=$keyCode")
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
