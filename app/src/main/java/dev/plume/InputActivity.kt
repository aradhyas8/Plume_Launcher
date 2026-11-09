package dev.plume

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Plays a Fire TV input without handing it to Amazon's player or guessing its inputType number. */
@Suppress("DEPRECATION")
class InputActivity : Activity() {
    private lateinit var tv: TvView
    private lateinit var status: TextView
    private var inputId: String? = null
    private var resumed = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { change ->
                tv.setStreamVolume(if (!resumed) 0f else when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> 1f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
                    else -> 0f
                })
            }.build()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // TvView's video surface sits behind the window; an opaque window hides the picture.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        tv = TvView(this)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setText(R.string.input_opening)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        setContentView(FrameLayout(this).apply {
            addView(tv, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        })
        tv.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(id: String) = failed()
            override fun onDisconnected(id: String) = failed()
            override fun onVideoAvailable(id: String) {
                status.visibility = View.GONE
                tv.keepScreenOn = true
            }
            override fun onVideoUnavailable(id: String, reason: Int) {
                tv.keepScreenOn = false
                status.visibility = View.VISIBLE
                status.setText(if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING ||
                    reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING) R.string.input_opening else R.string.no_signal)
            }
        })
        val id = intent.getStringExtra("input")
        try {
            val info = id?.let { getSystemService(TvInputManager::class.java).getTvInputInfo(it) }
            if (info?.type == TvInputInfo.TYPE_HDMI) inputId = id else failed()
        } catch (e: RuntimeException) {
            failed(e)
        }
    }

    override fun onResume() {
        super.onResume()
        val id = inputId ?: return
        resumed = true
        status.visibility = View.VISIBLE
        status.setText(R.string.input_opening)
        try {
            tv.setStreamVolume(if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f)
            tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id))
        } catch (e: RuntimeException) {
            failed(e)
        }
    }

    override fun onPause() {
        resumed = false
        tv.keepScreenOn = false
        tv.reset()
        audio.abandonAudioFocusRequest(focus)
        super.onPause()
    }

    private fun failed(error: RuntimeException? = null) {
        Log.w("Plume", "HDMI input could not open", error)
        status.visibility = View.VISIBLE
        status.setText(R.string.input_failed)
        tv.keepScreenOn = false
        tv.reset()
        audio.abandonAudioFocusRequest(focus)
    }
}
