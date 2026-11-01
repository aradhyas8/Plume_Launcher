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
        if (ready && active) start()
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

/** Reject mislabeled files and videos outside the TV wallpaper size limit. */
internal fun validVideo(file: File): Boolean {
    if (!file.isFile || file.length() !in 1..VIDEO_LIMIT) return false
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).any { index ->
            val format = extractor.getTrackFormat(index)
            format.getString(android.media.MediaFormat.KEY_MIME) in setOf("video/avc", "video/hevc") &&
                format.getInteger(android.media.MediaFormat.KEY_WIDTH) <= 1920 &&
                format.getInteger(android.media.MediaFormat.KEY_HEIGHT) <= 1080
        }
    } catch (_: Exception) { false } finally { extractor.release() }
}

internal const val VIDEO_LIMIT = 120L * 1024 * 1024

internal data class VideoScene(val name: String, val source: String, val file: String, val url: String)

internal val VIDEO_SCENES = listOf(
    VideoScene("Whole Earth at night", "NASA Earth", "nasa_wholeEarthNight_1080.mp4", "https://svs.gsfc.nasa.gov/vis/a000000/a004000/a004019/wholeEarthNight_1080.mp4"),
    VideoScene("Whole Earth in daylight", "NASA Earth", "nasa_wholeEarthDay_1080.mp4", "https://svs.gsfc.nasa.gov/vis/a000000/a004000/a004019/wholeEarthDay_1080.mp4"),
    VideoScene("Asia at night", "NASA Earth", "nasa_earthAtNightAsia_1080.mp4", "https://svs.gsfc.nasa.gov/vis/a000000/a004000/a004019/earthAtNightAsia_1080.mp4"),
    VideoScene("South America at night", "NASA Earth", "nasa_earthAtNightSAmerica_1080.mp4", "https://svs.gsfc.nasa.gov/vis/a000000/a004000/a004019/earthAtNightSAmerica_1080.mp4"),
    VideoScene("Antarctica", "Apple Aerial", "apple_antarctica.mp4", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT110_112NC_364D_1054_AURORA_ANTARTICA__COMP_FINAL_v34_PS_SDR_20181107_SDR_2K_AVC.mov"),
    VideoScene("China", "Apple Aerial", "apple_china.mp4", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT329_113NC_396B_1105_CHINA_v04_SDR_FINAL_20180706_F900F2700_SDR_2K_AVC.mov"),
    VideoScene("Italy to Asia", "Apple Aerial", "apple_italy_asia.mp4", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT329_113NC_396B_1105_ITALY_v03_SDR_FINAL_20180706_SDR_2K_AVC.mov"),
    VideoScene("Iran and Afghanistan", "Apple Aerial", "apple_iran_afghanistan.mp4", "https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_A083_C002_1130KZ_v04_SDR_PS_FINAL_20180725_SDR_2K_AVC.mov"),
    VideoScene("Magdalena Bay Sunset", "Amazon Fire TV", "amazon_magdalena_sunset.mp4", "https://aebcs-cdn-prod-na.services.firetv.amazon.dev/content/FHD/content-82f845c1-85ae-4eba-b57a-7860f1e8e998.mp4"),
    VideoScene("Northern Fjords of Norway", "Amazon Fire TV", "amazon_norway_fjords.mp4", "https://aebcs-cdn-prod-na.services.firetv.amazon.dev/content/FHD/content-aa6726c3-9b24-4e1b-8e74-46a061385087.mp4"),
    VideoScene("Morning Mist", "Amazon Fire TV", "amazon_morning_mist.mp4", "https://aebcs-cdn-prod-na.services.firetv.amazon.dev/content/FHD/content-8dd41619-4b4d-4077-aa02-5ad62a658f7d.mp4"),
    VideoScene("Beach from Above", "Amazon Fire TV", "amazon_beach_above.mp4", "https://aebcs-cdn-prod-na.services.firetv.amazon.dev/content/FHD/content-daf38cfa-0974-4c64-b332-8812cf8c831c.mp4"),
)

/** Download on selection only. A partial or oversized file never replaces a saved video. */
internal fun downloadVideoScene(item: VideoScene, destination: File, cancelled: AtomicBoolean, progress: (Int) -> Unit): Boolean {
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
