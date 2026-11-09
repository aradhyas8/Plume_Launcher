package dev.plume

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Framework-only device tests: run connectedDebugAndroidTest on an awake TV with HDMI and tuner inputs. */
class InputActivityTest : Instrumentation() {
    private var current = 0
    private var failures = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
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
        finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$current tests, $failures failures\n") })
    }

    private fun test(name: String, action: () -> Unit) {
        val result = Bundle().apply {
            putString("class", this@InputActivityTest.javaClass.name)
            putString("test", name)
            putInt("numtests", 4)
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
