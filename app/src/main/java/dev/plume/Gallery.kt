package dev.plume

import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Public-domain museum scans, not bundled: downloaded on demand from the repo's `gallery-1` release (the
 * files live in screensaver/) into [paintingsDir], so the APK stays under 1 MB for people who never use it. only paintings whose canvas is (within ~2-4%) 16:9, cropped
 * to exactly 1920×1080 so they fill the screen with nothing cut off and no bars. Bright ones for light mode,
 * dusk and storm ones for dark (wide night paintings are rare).
 */
private class Painting(val file: String, val title: String, val artist: String)

/** One picture in the show: a painting with its caption, or one of your photos (no caption). */
private class Picture(val title: String, val artist: String, val load: () -> Bitmap?)

private val LIGHT = listOf(
    Painting("andes", "The Heart of the Andes", "Frederic Edwin Church, 1859"),
    Painting("icebergs", "The Icebergs", "Frederic Edwin Church, 1861"),
    Painting("rye", "Rye", "Ivan Shishkin, 1878"),
    Painting("chimborazo", "Chimborazo", "Frederic Edwin Church, 1864"),
    Painting("hudson", "Autumn on the Hudson River", "Jasper Francis Cropsey, 1860"),
)
private val DARK = listOf(
    Painting("cotopaxi", "Cotopaxi", "Frederic Edwin Church, 1862"),
    Painting("thunderstorm", "Thunder Storm on Narragansett Bay", "Martin Johnson Heade, 1868"),
)

internal const val PAINTINGS_URL = "https://github.com/aradhyas8/Plume_Launcher/releases/download/gallery-1/"
internal const val PAINTINGS_MB = "4.7 MB"
internal val PAINTING_FILES = (LIGHT + DARK).map { "${it.file}.jpg" }
internal fun paintingsDir(c: Context) = File(c.filesDir, "paintings")
internal fun paintingsReady(c: Context) = PAINTING_FILES.all { File(paintingsDir(c), it).exists() }

/**
 * The Gallery screensaver. Picked in launcher settings, which also restore the previous one when it's off.
 * Shows the paintings, a folder of your photos when one is chosen there ("ss_folder"), or the downloaded
 * Aerial Views scenes when "ss_folder" is [AERIAL_SCREENSAVER].
 * ◀ ▶ step through the pictures; any other key leaves to the launcher's home screen.
 */
class GalleryDream : DreamService() {
    private lateinit var playback: GalleryPlayback
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = Runnable { tick() }

    // The island's "now" line in the bottom corner; null when the user turned the island or its screensaver line off.
    private var line: LinearLayout? = null
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private var shownKey: String? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // we handle keys ourselves (see dispatchKeyEvent)
        isFullscreen = true
        playback = GalleryPlayback(this)
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        if (!prefs.getBoolean("isl_on", true) || !prefs.getBoolean("isl_dream", true)) {
            setContentView(playback.view)
            return
        }
        IslandTimers.init(this)
        val dp = resources.displayMetrics.density
        // The same white and soft shadow as the painting's caption: the line never paints a box over the picture.
        fun text(sp: Float, a: Float) = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
            setSingleLine()
            alpha = a
        }
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }
        title = text(15f, 1f)
        sub = text(13f, 0.8f)
        val l = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            addView(art, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
            addView(title)
            addView(sub, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
        }
        line = l
        // The caption sits 96 px in from the bottom left of the 1080p picture, so the line keeps the same margin on the right.
        val m = (96 * resources.displayMetrics.heightPixels / 1080f).toInt()
        setContentView(FrameLayout(this).apply {
            addView(playback.view)
            addView(l, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(m, m, m, m) })
        })
        ui.post(refresh)
    }

    private fun tick() {
        render(nowItem())
        ui.postDelayed(refresh, NOW_MS)
    }

    /**
     * What is going on now, ranked as the island ranks it: the song only while it plays (a paused one is not "now"),
     * else a live or pinned game, else a running timer. Sports keep their games only while the home screen polls, so
     * during a long dream they can be empty; the dream does not start polling, because the line must not use the network.
     */
    private fun nowItem(): IslandItem? {
        val now = System.currentTimeMillis()
        val sessions = try {
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager).getActiveSessions(ComponentName(this, MediaListener::class.java))
        } catch (e: SecurityException) { // no notification access: no music
            emptyList<MediaController>()
        }
        fun name(c: MediaController) = c.metadata
            ?.let { it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
            ?.takeIf { it.isNotBlank() }
        fun playing(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val music = islandPick(sessions, ::playing) { name(it) != null }?.takeIf(::playing)?.let { c ->
            val m = c.metadata
            val who = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: appLabel(this, c.packageName)
            val pic = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            IslandItem("media", IslandKind.MEDIA, name(c)!!, who, image = pic, since = now)
        }
        return islandArrange(listOfNotNull(music) + IslandSports.items(now) + IslandTimers.items(now), now).firstOrNull()
    }

    /**
     * Shows [item], or nothing. A new thing fades the old one out and itself in; the same thing (a timer counting
     * down, a new score) only updates its text, so the line does not blink at every refresh.
     */
    private fun render(item: IslandItem?) {
        val l = line ?: return
        val fill = {
            if (item != null) {
                art.setImageBitmap(item.image)
                art.visibility = if (item.image != null) View.VISIBLE else View.GONE
                title.text = item.title
                sub.text = item.sub?.let { "· $it" }
                sub.visibility = if (item.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        }
        if (item?.key == shownKey) return fill()
        shownKey = item?.key
        l.animate().cancel()
        // A line already hidden (the first one) does not wait out a full fade before it shows.
        l.animate().alpha(0f).setDuration((l.alpha * FADE_MS).toLong()).withEndAction {
            fill()
            if (item != null) l.animate().alpha(1f).setDuration(FADE_MS)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN) playback.step(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        if (e.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        return true
    }

    override fun onDreamingStopped() {
        ui.removeCallbacks(refresh)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(refresh)
        line?.animate()?.cancel()
        playback.release()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NOW_MS = 5_000L
        const val FADE_MS = 400L
    }
}

/** Previews Plume's screensaver without a system "dream now" activity. */
@Suppress("DEPRECATION")
class GalleryPreview : Activity() {
    private lateinit var playback: GalleryPlayback

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        playback = GalleryPlayback(this)
        setContentView(playback.view)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN) playback.step(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
        } else if (e.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

/** Both hosts use the same content, controls and cleanup. */
private class GalleryPlayback(context: Context) {
    private val show: GalleryShow?
    private val aerial: AerialShow?
    val view: SurfaceView

    init {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val scenes = if (prefs.getString("ss_folder", null) == AERIAL_SCREENSAVER) aerialFiles(context) else emptyList()
        aerial = if (scenes.isNotEmpty()) AerialShow(context, scenes) else null
        show = if (aerial == null) GalleryShow(context) else null
        view = aerial?.view ?: show!!.view
    }

    fun step(by: Int) { show?.step(by); aerial?.step(by) }
    fun release() { show?.release(); aerial?.release() }
}

/**
 * Paints edge to edge on a SurfaceView at the UI's 1080p. Not 4K: this TV's display hardware shows a 4K app
 * surface as black (screenshots still show it, since they composite on the GPU).
 * Each painting fills the screen exactly, holds, then cross-fades to the next. The title shows for a few seconds per painting.
 * About 6 MB per decoded painting; one in memory, two during a cross-fade, plus the surface's buffers.
 * Outgoing paintings are recycled as soon as they're off screen, and everything when the dream ends.
 */
private class GalleryShow(private val context: Context) : SurfaceHolder.Callback {
    private val paintings = pictures()
    private val io = Executors.newSingleThreadExecutor()
    val view = SurfaceView(context).also { it.holder.addCallback(this) }

    /** A folder's photos in shuffled order if one is chosen and still has photos, else the theme's paintings. */
    private fun pictures(): List<Picture> {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        val photos = try {
            prefs.getString("ss_folder", null)?.takeIf { it != AERIAL_SCREENSAVER }?.let { folderPhotos(context.contentResolver, it) }.orEmpty()
        } catch (e: Exception) { // photo access taken away
            emptyList()
        }
        if (photos.isNotEmpty()) return photos.shuffled().map { id ->
            val uri = ContentUris.withAppendedId(PHOTOS, id)
            Picture("", "") { fitWallpaper { context.contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") } }
        }
        return (if (prefersDark(prefs)) DARK else LIGHT).map { p ->
            Picture(p.title, p.artist) { File(paintingsDir(context), "${p.file}.jpg").inputStream().use { BitmapFactory.decodeStream(it) } }
        }
    }

    private class Slide(val bmp: Bitmap, val painting: Picture, val start: Long, val fadeMs: Long)

    // Shared between the loader and the render thread; swaps and draws hold [lock], so a bitmap is
    // never recycled mid-draw.
    private val lock = Any()
    @Volatile private var current: Slide? = null
    @Volatile private var previous: Slide? = null
    @Volatile private var running = false
    private var index = paintings.indices.random()
    @Volatile private var advanceAt = 0L
    private var render: Thread? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(12f, 0f, 2f, 0x99000000.toInt()) }
    private val artist = Paint(title)

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        load(index)
        render = Thread(::loop, "gallery").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        render?.join(500)
    }

    fun step(by: Int) {
        index = (index + by + paintings.size) % paintings.size
        load(index)
    }

    fun release() {
        running = false // first: the render thread stops asking for the next painting before the pool is shut
        io.shutdownNow()
        synchronized(lock) {
            current?.bmp?.recycle()
            previous?.bmp?.recycle()
            current = null
            previous = null
        }
    }

    private fun load(i: Int) {
        val p = paintings[i]
        io.execute {
            val bmp = try {
                p.load()
            } catch (e: Exception) {
                null
            } ?: return@execute
            synchronized(lock) {
                if (!running) { // dream ended while decoding
                    bmp.recycle()
                    return@execute
                }
                previous?.bmp?.recycle() // stepped again mid-fade: that one won't be drawn any more
                // The first painting fades up from black: keep that short or the screen just looks off.
                val fade = if (current == null) FIRST_FADE_MS else FADE_MS
                previous = current
                current = Slide(bmp, p, System.currentTimeMillis(), fade)
                advanceAt = System.currentTimeMillis() + HOLD_MS
            }
        }
    }

    private fun loop() {
        while (running) {
            val now = System.currentTimeMillis()
            if (advanceAt in 1..now) {
                advanceAt = 0
                step(1)
            }
            val holder = view.holder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                draw(canvas, now)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Thread.sleep(FRAME_MS) // 30 fps is plenty for motion this slow, and spares the GPU
        }
    }

    private fun draw(c: Canvas, now: Long) = synchronized(lock) {
        c.drawColor(Color.BLACK)
        if (!running) return
        val cur = current ?: return
        val fade = ((now - cur.start).toFloat() / cur.fadeMs).coerceIn(0f, 1f)
        previous?.let {
            if (fade < 1f) drawSlide(c, it, ((1f - fade) * 255).toInt()) else {
                it.bmp.recycle() // faded out: free it now rather than whenever GC gets to it
                previous = null
            }
        }
        drawSlide(c, cur, (fade * 255).toInt())

        // Title for the first seconds of each painting, fading in and out.
        val t = now - cur.start
        val a = when {
            t < FADE_MS -> t.toFloat() / FADE_MS
            t < CAPTION_MS -> 1f
            t < CAPTION_MS + FADE_MS -> 1f - (t - CAPTION_MS).toFloat() / FADE_MS
            else -> 0f
        }
        if (a > 0f && cur.painting.title.isNotEmpty()) {
            val u = c.height / 1080f // design in 1080p units, draw at panel resolution
            title.textSize = 30f * u
            artist.textSize = 22f * u
            title.alpha = (a * 235).toInt()
            artist.alpha = (a * 190).toInt()
            c.drawText(cur.painting.title, 96f * u, c.height - 96f * u, title)
            c.drawText(cur.painting.artist, 96f * u, c.height - 60f * u, artist)
        }
    }

    /** The paintings are already the screen's shape, so each one simply covers the whole surface. */
    private fun drawSlide(c: Canvas, s: Slide, alpha: Int) {
        paint.alpha = alpha
        c.drawBitmap(s.bmp, null, RectF(0f, 0f, c.width.toFloat(), c.height.toFloat()), paint)
    }

    private companion object {
        const val HOLD_MS = 2 * 60_000L
        const val FADE_MS = 2_500L
        const val FIRST_FADE_MS = 600L
        const val CAPTION_MS = 8_000L
        const val FRAME_MS = 33L
    }
}
