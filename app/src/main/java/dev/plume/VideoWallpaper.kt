package dev.plume

import android.media.MediaExtractor
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Videos stay in Plume's wallpaper folder; no player runs after the home screen stops. */
internal class VideoWallpaper(private val surface: SurfaceView, private val onFrame: () -> Unit, private val onError: () -> Unit) : SurfaceHolder.Callback {
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var active = false
    private var ready = false

    init { surface.holder.addCallback(this) }

    fun show(file: File, play: Boolean) {
        if (this.file != file) { release(); this.file = file }
        active = play
        surface.visibility = android.view.View.VISIBLE
        if (ready) start()
    }

    fun pause() {
        active = false
        release()
    }

    fun resume() {
        active = true
        if (ready) start()
    }

    fun clear() {
        pause()
        file = null
        surface.visibility = android.view.View.GONE
    }

    override fun surfaceCreated(holder: SurfaceHolder) { ready = true; if (active) start() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { ready = false; release() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    private fun start() {
        val target = file?.takeIf { it.isFile } ?: return onError()
        if (player != null) return
        try {
            player = MediaPlayer().also { p ->
                p.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); it.start() }
                p.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFrame()
                    false
                }
                p.setOnErrorListener { _, _, _ -> release(); onError(); true }
                p.setDataSource(target.path)
                p.setDisplay(surface.holder)
                p.prepareAsync()
            }
        } catch (_: Exception) {
            release()
            onError()
        }
    }

    private fun release() { player?.release(); player = null }
}

/** Reject mislabeled files and codecs that the first version does not support. */
internal fun validVideo(file: File): Boolean {
    if (!file.isFile || file.length() !in 1..VIDEO_LIMIT) return false
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).any { index ->
            val format = extractor.getTrackFormat(index)
            format.getString(android.media.MediaFormat.KEY_MIME) == "video/avc" &&
                format.getInteger(android.media.MediaFormat.KEY_WIDTH) <= 1920 &&
                format.getInteger(android.media.MediaFormat.KEY_HEIGHT) <= 1080
        }
    } catch (_: Exception) { false } finally { extractor.release() }
}

internal const val VIDEO_LIMIT = 120L * 1024 * 1024

internal data class EarthVideo(val name: String, val file: String) {
    val url: String get() = "https://svs.gsfc.nasa.gov/vis/a000000/a004000/a004019/$file"
}

internal val EARTH_VIDEOS = listOf(
    EarthVideo("Whole Earth at night", "wholeEarthNight_1080.mp4"),
    EarthVideo("Whole Earth in daylight", "wholeEarthDay_1080.mp4"),
    EarthVideo("Asia at night", "earthAtNightAsia_1080.mp4"),
    EarthVideo("South America at night", "earthAtNightSAmerica_1080.mp4"),
)

/** Download on selection only. A partial or oversized file never replaces a saved video. */
internal fun downloadEarthVideo(item: EarthVideo, destination: File, cancelled: AtomicBoolean, progress: (Int) -> Unit): Boolean {
    val part = File(destination.parentFile, destination.name + ".part")
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        if (connection.responseCode != 200 || connection.contentLengthLong !in 1..VIDEO_LIMIT) return false
        var count = 0L
        var lastPercent = -1
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) return false
                    val n = input.read(bytes)
                    if (n < 0) break
                    count += n
                    if (count > VIDEO_LIMIT) return false
                    output.write(bytes, 0, n)
                    val percent = (count * 100 / connection.contentLengthLong).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            }
        }
        if (cancelled.get() || !validVideo(part)) false else part.renameTo(destination)
    } catch (_: IOException) { false } finally { connection?.disconnect(); part.delete() }
}
