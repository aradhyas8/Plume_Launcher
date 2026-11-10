package dev.plume

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Uses the real audio service and restores its original mute state. Run on a device with adjustable volume. */
class MuteCheck(private val runner: Instrumentation) {
    fun run() = with(runner) {
        val audio = targetContext.getSystemService(AudioManager::class.java)
        check(!audio.isVolumeFixed) { "This device has fixed volume" }
        val originalMute = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val service = object : AccessibilityService() {
            init { attachBaseContext(targetContext) }
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }
        try {
            check(Remap.describe(targetContext, "media:mute") == targetContext.getString(R.string.act_mute))
            repeat(2) { press ->
                runOnMainSync { Remap.run(service, "media:mute") }
                val expected = if (press == 0) !originalMute else originalMute
                val until = SystemClock.uptimeMillis() + 3_000
                while (audio.isStreamMute(AudioManager.STREAM_MUSIC) != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                check(audio.isStreamMute(AudioManager.STREAM_MUSIC) == expected) { "Mute action did not toggle the audio stream" }
            }
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume) { "Mute changed the saved volume" }
        } finally {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != originalMute) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            }
        }
    }
}
