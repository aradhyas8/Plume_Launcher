package dev.plume

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.TextClock
import android.widget.Toast
import android.widget.VideoView
import android.view.SurfaceView
import java.io.File
import java.lang.ref.WeakReference
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import org.json.JSONObject

/** The open island is never narrower than this (dp): with less room beside the clock and the icons it opens below the bar. */
private const val MIN_OPEN = 300

/**
 * The island on the home screen: its view, its rows and keys, its menus, and the music it follows. It is written as
 * extensions of the home screen, so it uses the same helpers (the glass tile style, the ink, the settings pages)
 * without copies; the home screen holds one [IslandView] and calls the few functions at the end.
 */
internal class IslandView(private val a: MainActivity) {
    // ---------- the island ----------
    // A glass tile at the top centre, the same tile as the app row (it follows its Glass style and theme), for what is
    // going on now: music, timers, alerts, live scores, the weather turning, an update, the network, Bluetooth. Each
    // source turns its state into IslandItems (see Island.kt); islandArrange orders them, the first shows in the pill
    // and the others as small marks beside it. Selected, the tile opens down into rows: the marks (Left and Right
    // pick which thing shows), the music's progress bar (Left and Right seek) and the shown thing's buttons. An alert
    // opens it for a few seconds by itself, without taking focus. Only on the home screen: Plume draws over no app.
    val island by lazy { a.findViewById<LinearLayout>(R.id.island) }
    val islandTop by lazy { a.findViewById<View>(R.id.islandTop) }
    val islandMore by lazy { a.findViewById<View>(R.id.islandMore) }
    val islandChips by lazy { a.findViewById<LinearLayout>(R.id.islandChips) }
    val islandArt by lazy { a.findViewById<ImageView>(R.id.islandArt) }
    val islandArt2 by lazy { a.findViewById<ImageView>(R.id.islandArt2) }
    val islandActions by lazy { a.findViewById<LinearLayout>(R.id.islandActions) }
    val islandBar by lazy { a.findViewById<IslandBar>(R.id.islandProgress) }

    enum class IslandRow { MARKS, BAR, ACTIONS }

    var islandOpen = false // the shape the tile has now; the wanted shape can be ahead of it while content fades
    var islandWant: Boolean? = null // the shape the running fade is heading for
    var islandInk = 0 // the ink last picked, for the progress between full refreshes
    var islandList: List<IslandItem> = emptyList() // everything on the island, in order, as last shown
    var islandItem: IslandItem? = null // the one the tile shows
    var islandPick: String? = null // the key of the thing picked with the marks, while the island is open
    var islandRowAt = 0 // the cursor in the open island: which row, and which cell in it
    var islandCol = 0
    var islandRepeat = 0 // how long the key has been held, so seeking speeds up
    var peekKey: String? = null // the item the island opened for by itself, and the peeks already shown
    val peeksShown = HashSet<String>()
    var seekTo = -1L // a seek still being chosen: shown at once, sent once the keys stop
    var netDownAt = 0L // when the network went away (0: it is up), and when it came back
    var netBackAt = 0L
    var updateDone = -1f // the update's download, 0..1, while one runs; -1: none
    val islandHidden = HashSet<String>() // items hidden with Hold OK › Hide, until Plume restarts
    var netWhy = 0 // the string for what is wrong with the network, 0 when nothing is
    val islandImages = HashMap<String, Bitmap>() // pictures fetched from addresses (logos), by address
    val islandLoading = HashSet<String>()
    val islandFailed = HashSet<String>() // addresses with no picture (ESPN has no logo for many teams): asked once only

    // Music: the session the island follows.
    var pausedAt = 0L // when the shown track paused (elapsedRealtime), 0 while it plays
    var artUri: String? = null // the full-size art asked for, and what came back
    var artSharp: Bitmap? = null
    var artTone: Pair<Bitmap, Int>? = null // the art's mean colour, worked out once per picture
    var media: MediaController? = null
    val mediaSessions by lazy { a.getSystemService(MediaSessionManager::class.java) }
    val mediaAccess by lazy { ComponentName(a, MediaListener::class.java) }
    val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { a.pickMedia(it.orEmpty()) }
    val mediaChanged = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = a.refreshMedia()
        override fun onPlaybackStateChanged(s: PlaybackState?) = a.refreshMedia()
        override fun onSessionDestroyed() = a.refreshMedia()
    }
    val islandTick = Runnable { a.showIsland() }
    val seekSend = Runnable {
        if (seekTo >= 0) media?.transportControls?.seekTo(seekTo)
        a.ui.postDelayed({ seekTo = -1L; a.showProgress() }, 1500) // until the app reports the new place
    }

    fun on(key: String, default: Boolean = true) = a.prefs.getBoolean(key, default)

    fun MainActivity.setUpIsland() {
        // Corners that follow the tile's: a circle in the pill, a soft square in the open tile.
        islandArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, if (v.width < dp(40)) v.width / 2f else dp(10).toFloat())
        }
        islandArt.clipToOutline = true
        styleIsland()
        island.setOnClickListener { islandOk() }
    }

    /** A glass tile like the app row's, open or not: the same Glass style, theme, tint and focus light. */
    fun MainActivity.styleIsland() {
        style(island, dp(ISLAND_ROUND), card = true, lift = 1f, rise = false) { focused ->
            if (focused) {
                lastTop = island
                peekKey?.let { peeksShown += it } // selected while it peeked: it has been seen
                islandPick = null
                islandRowAt = Int.MAX_VALUE // showIsland puts the cursor on the buttons
            }
            showIsland()
        }
        showIsland()
    }

    /** Sources start with the home screen and stop with it: the island only shows there, so nothing runs for nothing. */
    fun MainActivity.islandStart() {
        Island.listener = { showIsland() }
        IslandTimers.init(this)
        IslandWeather.start(this)
        IslandSports.start(this)
        IslandBluetooth.start(this)
        try {
            mediaSessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaAccess, ui)
        } catch (e: SecurityException) {
            // No notification access: refreshMedia finds no music.
        }
        refreshMedia()
    }

    fun MainActivity.islandStop() {
        Island.listener = null
        IslandWeather.stop()
        IslandSports.stop()
        IslandBluetooth.stop(this)
        mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        media?.unregisterCallback(mediaChanged)
        media = null
        ui.removeCallbacks(islandTick)
    }

    fun MainActivity.refreshMedia() = pickMedia(try { mediaSessions.getActiveSessions(mediaAccess) } catch (e: SecurityException) { emptyList() })

    fun MainActivity.pickMedia(list: List<MediaController>) {
        val pick = islandPick(list, { a.mediaPlaying(it) }) { mediaTitle(it.metadata) != null }
        if (pick?.sessionToken != media?.sessionToken) {
            media?.unregisterCallback(mediaChanged)
            media = pick
            pausedAt = 0L
            pick?.registerCallback(mediaChanged, ui)
        }
        showIsland()
    }

    fun MainActivity.mediaPlaying(c: MediaController?) = c?.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    fun MainActivity.mediaTitle(m: MediaMetadata?) = (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }

    /** The app can take a seek, and the track has a length to seek in. */
    fun MainActivity.mediaSeekable(c: MediaController?) = c != null && (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L &&
        (c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L

    /** The picture to show: the full-size art when the app gives its address (loaded once), else the session's bitmap. */
    fun MainActivity.islandArtwork(m: MediaMetadata): Bitmap? {
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { m.getString(it)?.takeIf { u -> u.startsWith("http") || u.startsWith("content") || u.startsWith("android.resource") } }
        if (uri != artUri) {
            artUri = uri
            artSharp = uri?.let { posters.get(it) }
            if (uri != null && artSharp == null) posterIo.execute {
                val b = try { decode(uri, dp(68), Bitmap.Config.ARGB_8888) } catch (e: Exception) { null } ?: return@execute
                posters.put(uri, b)
                ui.post { if (artUri == uri && !isDestroyed) { artSharp = b; showIsland() } }
            }
        }
        return artSharp ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    /** The art's mean colour, or null when its pixels can't be read (a hardware bitmap). */
    fun MainActivity.artTone(b: Bitmap): Int? {
        artTone?.takeIf { it.first === b }?.let { return it.second }
        return try {
            val small = Bitmap.createScaledBitmap(b, 8, 8, true)
            val px = IntArray(64).also { small.getPixels(it, 0, 8, 0, 0, 8, 8) }
            meanColour(px).also { artTone = b to it }
        } catch (e: Exception) {
            null
        }
    }

    /** The music as an island item: playing, or paused for less than the time the settings allow. */
    fun MainActivity.mediaItem(now: Long): IslandItem? {
        if (!on("isl_music")) return null
        val c = media ?: return null
        val m = c.metadata ?: return null
        val title = mediaTitle(m) ?: return null
        val playing = mediaPlaying(c)
        val clock = android.os.SystemClock.elapsedRealtime()
        pausedAt = if (playing) 0L else pausedAt.takeIf { it > 0 } ?: clock
        val idle = prefs.getLong("isl_idle", ISLAND_IDLE_MS)
        if (idle > 0 && islandIdle(pausedAt, clock, idle, island.isFocused)) return null
        val who = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = islandArtwork(m)
        val app = appLabel(this, c.packageName)
        val can = c.playbackState?.actions ?: 0L
        val t = c.transportControls
        // An app that names no actions gets all three; one that names them gets only what it can do.
        val prev = can == 0L || can and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        val next = can == 0L || can and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        return IslandItem("media", if (playing) IslandKind.MEDIA else IslandKind.MEDIA_PAUSED, title, who,
            icon = R.drawable.ic_music_note, mono = art == null, image = art, since = now,
            actions = listOfNotNull(
                IslandAction(R.drawable.ic_skip_previous, description = getString(R.string.media_previous)) { t.skipToPrevious() }.takeIf { prev },
                IslandAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, description = getString(R.string.media_play_pause)) { if (mediaPlaying(media)) t.pause() else t.play() },
                IslandAction(R.drawable.ic_skip_next, description = getString(R.string.media_next)) { t.skipToNext() }.takeIf { next },
            ),
            description = listOfNotNull(title, who, app).joinToString(", "))
    }

    /** The network and Plume's own update, which the home screen knows itself. */
    fun MainActivity.systemItems(now: Long): List<IslandItem> = listOfNotNull(
        // Gone for 10 s first: Wi-Fi coming up after a wake would flash it for nothing.
        if (on("isl_net") && netDownAt > 0 && now - netDownAt > 10_000)
            IslandItem("net", IslandKind.NETWORK, getString(netWhy.takeIf { it != 0 } ?: R.string.isl_net_down),
                getString(if (netWhy == R.string.isl_net_captive) R.string.isl_net_captive_sub else R.string.isl_net_down_sub), R.drawable.ic_isl_wifi_off,
                since = netDownAt, peekUntil = netDownAt + 16_000,
                actions = listOf(IslandAction(label = getString(R.string.isl_net_wifi)) { openSettings(Settings.ACTION_WIFI_SETTINGS) })) else null,
        if (on("isl_net") && netBackAt > 0 && now - netBackAt < 5_000)
            IslandItem("net", IslandKind.NETWORK, getString(R.string.isl_net_back), null, R.drawable.ic_wifi, since = netBackAt, until = netBackAt + 5_000) else null,
        if (on("isl_update") && updateDone >= 0f)
            IslandItem("update", IslandKind.UPDATE, getString(R.string.isl_update_title),
                if (updateDone >= 1f) getString(R.string.isl_update_installing) else getString(R.string.isl_update_progress, availableUpdate() ?: "", (updateDone * 100).toInt()),
                R.drawable.ms_system_update, progress = updateDone, since = now) else null,
    )

    /** Everything on the island now, in order. */
    /** What "Hide this" hides: the item, or for music the track, so the next song shows again. */
    fun MainActivity.hideKey(i: IslandItem) = if (i.kind == IslandKind.MEDIA || i.kind == IslandKind.MEDIA_PAUSED) "media:${i.title}" else i.key

    fun MainActivity.islandAll(now: Long): List<IslandItem> {
        if (!on("isl_on")) return emptyList()
        val all = ArrayList<IslandItem>()
        mediaItem(now)?.let { all += it }
        all += IslandTimers.items(now)
        if (on("isl_alerts")) all += IslandAlerts.items(now)
        if (on("isl_sports")) all += IslandSports.items(now)
        all += IslandWeather.items(now)
        if (on("isl_bluetooth", false)) all += IslandBluetooth.items(now)
        all += systemItems(now)
        return islandArrange(all.filter { hideKey(it) !in islandHidden }, now)
    }

    /** The home screen is what's in front: no panel, no All apps over it. */
    fun MainActivity.homeInFront() = resumed && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE

    fun MainActivity.islandRows(item: IslandItem?): List<IslandRow> = listOfNotNull(
        IslandRow.MARKS.takeIf { islandList.size > 1 },
        IslandRow.BAR.takeIf { item?.kind.let { it == IslandKind.MEDIA || it == IslandKind.MEDIA_PAUSED } && mediaSeekable(media) },
        IslandRow.ACTIONS.takeIf { item != null && item.actions.isNotEmpty() },
    )

    /** Builds everything again from the sources: the items, which one shows, open or not, and when to look again. */
    fun MainActivity.showIsland() {
        if (isDestroyed || !glassReady()) return // a delayed refresh after a theme change rebuilt the screen
        val now = System.currentTimeMillis()
        islandList = islandAll(now)
        ui.removeCallbacks(islandTick)
        if (islandList.isEmpty()) {
            if (island.isFocused) findViewById<View?>(shownTop().first())?.requestFocus()
            if (lastTop === island) lastTop = null
            island.visibility = View.GONE
            islandOpen = false
            islandWant = null
            return
        }
        // An alert opens the island by itself for a moment, only while nothing else is going on on the screen.
        val peek = if (!island.isFocused && homeInFront()) islandPeek(islandList, now)?.takeIf { "${it.key}@${it.peekUntil}" !in peeksShown } else null
        val newPeek = peek?.let { "${it.key}@${it.peekUntil}" }
        // A screen reader says what opened the island by itself; nobody looked at it on purpose.
        if (newPeek != null && newPeek != peekKey) island.announceForAccessibility(peek.description ?: peek.title)
        peekKey = newPeek
        val focused = island.isFocused
        val item = peek ?: (if (focused) islandList.firstOrNull { it.key == islandPick } else null) ?: islandList[0]
        islandItem = item
        val open = focused || peek != null
        val rows = islandRows(item)
        if (focused) {
            if (islandRowAt >= rows.size) { // just opened: the buttons, on the middle one (Play for music)
                islandRowAt = rows.indexOf(IslandRow.ACTIONS).takeIf { it >= 0 } ?: (rows.size - 1).coerceAtLeast(0)
                islandCol = if (rows.getOrNull(islandRowAt) == IslandRow.ACTIONS) item.actions.size / 2 else 0
            }
            if (rows.getOrNull(islandRowAt) == IslandRow.MARKS) islandCol = islandList.indexOf(item).coerceAtLeast(0)
        }
        island.visibility = View.VISIBLE
        if (islandWant == null && open == islandOpen) bindIsland(item, open) else shapeIsland(open)
        // Look again when something changes by itself: a second passes on a timer, or an item or a peek runs out.
        val next = (islandList.flatMap { listOf(it.until, it.peekUntil) } + listOfNotNull(peek?.peekUntil)).filter { it > now }.minOrNull()
        // Each item says how often its text changes; open, the music's bar and times move every second.
        val period = (islandList.mapNotNull { it.tick.takeIf { t -> t > 0 } } + listOfNotNull(1000L.takeIf { open && (media != null || item.progress != null) })).minOrNull()
        val wait = minOf(period ?: Long.MAX_VALUE, next?.let { it - now + 50 } ?: Long.MAX_VALUE)
        if (wait != Long.MAX_VALUE) ui.postDelayed(islandTick, wait)
    }

    /**
     * The content fades out, the tile changes shape, the new content fades in: the shape moves, the content never
     * stretches. A quick Up and Down lands on the last state asked for.
     */
    fun MainActivity.shapeIsland(open: Boolean) {
        if (islandWant == open) return
        islandWant = open
        for (v in listOf(islandTop, islandMore, islandChips)) v.animate().cancel()
        islandChips.animate().alpha(0f).setStartDelay(0).setDuration(90).start()
        islandMore.animate().alpha(0f).setStartDelay(0).setDuration(90).start()
        islandTop.animate().alpha(0f).setStartDelay(0).setDuration(90).withEndAction {
            val want = islandWant ?: return@withEndAction
            islandWant = null
            reshapeIsland(want)
        }.start()
    }

    fun MainActivity.reshapeIsland(open: Boolean) {
        val item = islandItem ?: return
        islandOpen = open
        android.transition.TransitionManager.beginDelayedTransition(island.parent as ViewGroup,
            android.transition.ChangeBounds().setDuration(280).setInterpolator(MainActivity.DECELERATE))
        val fit = if (open) islandOpenWidth() else 0
        island.layoutParams.width = if (open) fit.coerceAtLeast(dp(MIN_OPEN)) else ViewGroup.LayoutParams.WRAP_CONTENT
        // Too little room beside the clock and the icons: the open tile comes down below the top bar, not over it.
        val below = open && fit < dp(MIN_OPEN)
        (island.layoutParams as FrameLayout.LayoutParams).topMargin = if (below) (island.parent as View).height else 0
        if (open) island.setPadding(dp(18), dp(18), dp(18), dp(14)) else island.setPadding(dp(5), dp(5), dp(14), dp(5))
        islandTop.layoutParams.width = if (open) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        val size = dp(if (open) 68 else 26)
        islandArt.layoutParams = islandArt.layoutParams.apply { width = size; height = size }
        islandArt2.layoutParams = islandArt2.layoutParams.apply { width = dp(if (open) 48 else 26); height = dp(if (open) 48 else 26) }
        findViewById<View>(R.id.islandText).layoutParams = (findViewById<View>(R.id.islandText).layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = dp(if (open) 16 else 10)
            width = if (open) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            weight = if (open) 1f else 0f
        }
        findViewById<TextView>(R.id.islandTitle).apply {
            textSize = if (open) 18f else 13f
            maxLines = if (open) 2 else 1
            maxWidth = dp(if (open) 400 else 170)
        }
        bindIsland(item, open)
        // The tile hangs over the screen: its extra height is taken back by the margin, so the rows below never move.
        island.measure(View.MeasureSpec.makeMeasureSpec(island.layoutParams.width.coerceAtLeast(0),
            if (open) View.MeasureSpec.EXACTLY else View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.UNSPECIFIED)
        islandHang()
        island.requestLayout()
        glass.kick(500) // the glass under it follows the new size
        islandChips.animate().alpha(1f).setStartDelay(170).setDuration(180).start()
        islandTop.animate().alpha(1f).setStartDelay(170).setDuration(180).withEndAction(null).start()
        islandMore.animate().alpha(1f).setStartDelay(210).setDuration(180).start()
        ui.postDelayed({ if (!isDestroyed) islandItem?.let { bindIsland(it, islandOpen) } }, 320) // the ink again, over the new size
    }

    /** The tile hangs over the screen: its extra height (and any drop below the bar) is taken back, so nothing moves. */
    fun MainActivity.islandHang() {
        val lp = island.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = -(island.measuredHeight + lp.topMargin - dp(36)).coerceAtLeast(0)
    }

    /**
     * The widest the open tile can be, centred, without covering the clock column or the first top bar icon: at most
     * 400 dp. Measured on the screen, as the clock's text, the date and the icons the user picked all change it.
     */
    fun MainActivity.islandOpenWidth(): Int {
        val bar = island.parent as View
        if (bar.width == 0) return dp(400)
        val at = IntArray(2)
        bar.getLocationInWindow(at)
        val mid = at[0] + bar.width / 2
        val column = findViewById<View>(R.id.clock).parent as ViewGroup
        var left = 0
        for (i in 0 until column.childCount) column.getChildAt(i).takeIf { it.isShown }?.let { v ->
            // A wrap-content child's right edge is where its text ends; the date row's last child ends the weather.
            val end = if (v is ViewGroup && v.childCount > 0) v.getChildAt(v.childCount - 1) else v
            end.getLocationInWindow(at)
            left = maxOf(left, at[0] + end.width)
        }
        var right = Int.MAX_VALUE
        for (id in shownTop()) findViewById<View?>(id)?.takeIf { it.isShown && it.width > 0 }?.let { v ->
            v.getLocationInWindow(at)
            right = minOf(right, at[0])
        }
        if (right == Int.MAX_VALUE) right = at[0] + bar.width
        val half = minOf(mid - left, right - mid) - dp(16)
        return minOf(dp(400), half * 2)
    }

    /** A picture fetched from an address (a team logo), kept once it has come; null until then. */
    fun MainActivity.islandImage(uri: String?): Bitmap? {
        if (uri == null) return null
        islandImages[uri]?.let { return it }
        if (uri in islandFailed) return null
        if (islandLoading.add(uri)) posterIo.execute {
            val b = try { decode(uri, dp(96), Bitmap.Config.ARGB_8888) } catch (e: Exception) { null }
            ui.post {
                islandLoading.remove(uri)
                if (b == null) islandFailed += uri
                else if (!isDestroyed) { islandImages[uri] = b; islandItem?.let { bindIsland(it, islandOpen) } }
            }
        }
        return null
    }

    /** A team's picture: its logo once it has come, else a badge with its letters on its colour ("ABT|3f8108"). */
    fun MainActivity.teamImage(uri: String?, badge: String?): Bitmap? = islandImage(uri) ?: badge?.let { spec ->
        islandImages.getOrPut("badge:$spec") {
            val (letters, hex) = spec.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val fill = hex.takeIf { it.length == 6 }?.let { Color.parseColor("#$it") } ?: 0xFF5F6B7A.toInt()
            val size = dp(96)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
                val text = badgeLetters(letters)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (Color.luminance(fill) > 0.6f) 0xFF1A1A1A.toInt() else Color.WHITE
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    textSize = size * (if (text.length <= 2) 0.42f else if (text.length == 3) 0.34f else 0.27f)
                }
                c.drawText(text, size / 2f, size / 2f - (ink.descent() + ink.ascent()) / 2, ink)
            }
        }
    }

    /** Puts [item] on the tile in its [open] or resting form. */
    fun MainActivity.bindIsland(item: IslandItem, open: Boolean) {
        // The island is glass over the picture, open or not, so its text, bars and buttons take the ink fitted to the
        // wallpaper behind it, with the top bar's shadow. A solid tile (Glass off) keeps the theme's colour.
        val glassy = glassActive()
        val ink = if (!glassy) fg else if (island.isShown && island.width > 0) inkBehind(listOf(island)) else iconInk
        islandInk = ink
        fun TextView.inked() = if (glassy) ink(this, ink) else { setShadowLayer(0f, 0f, 0f, 0); setTextColor(fg) }
        val music = item.kind == IslandKind.MEDIA || item.kind == IslandKind.MEDIA_PAUSED
        findViewById<TextView>(R.id.islandTitle).apply {
            text = item.title
            fontFeatureSettings = if (item.tick > 0 || item.kind == IslandKind.SPORTS_LIVE) "tnum" else null // numbers that change keep their width
            inked()
        }
        findViewById<TextView>(R.id.islandArtist).apply {
            text = item.sub.orEmpty()
            visibility = if (open && !item.sub.isNullOrEmpty()) View.VISIBLE else View.GONE
            maxLines = 2 // a match's state ("ABT batting · 28.1 ov · Zimbabwe lead by 291 runs") can take two lines
            inked()
        }
        island.contentDescription = item.description ?: listOfNotNull(item.title, item.sub).joinToString(", ")
        val picture = item.image ?: teamImage(item.imageUri, item.badge)
        when {
            picture != null -> { islandArt.clearColorFilter(); islandArt.setImageBitmap(picture); islandArt.background = null; islandArt.setPadding(0, 0, 0, 0) }
            item.icon != 0 -> {
                islandArt.setImageResource(item.icon)
                if (item.mono) islandArt.setColorFilter(ink) else islandArt.clearColorFilter()
                // An icon sits on a soft disc of the ink, the size of the picture it stands in for.
                islandArt.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink and 0xFFFFFF or 0x26000000) }
                val pad = dp(if (open) 16 else 5)
                islandArt.setPadding(pad, pad, pad, pad)
            }
            else -> islandArt.setImageDrawable(null)
        }
        val picture2 = teamImage(item.image2Uri, item.badge2)
        islandArt2.visibility = if (picture2 != null) View.VISIBLE else View.GONE
        islandArt2.setImageBitmap(picture2)
        // The music's mark as the Shows row shows it: a plain mark in the ink, or its brand colour with Colour on.
        val pkg = media?.packageName
        val logo = pkg?.let { MainActivity.LOGOS[it] }?.takeIf { !it.second }
        findViewById<ImageView>(R.id.islandApp).apply {
            visibility = if (open && music) View.VISIBLE else View.GONE
            setImageDrawable(if (!music || pkg == null) null else if (logo != null) getDrawable(logo.first)!!.mutate().apply { setTint(MainActivity.BRAND_COLOURS[pkg]?.takeIf { cwColour() } ?: ink) } else sourceIcon(pkg))
        }
        // The bars move with the music, in the art's colour: pale on a light ink and deep on a dark one.
        val light = Color.luminance(ink) > 0.5f
        findViewById<EqBars>(R.id.islandEq).apply {
            visibility = if (!open && music) View.VISIBLE else View.GONE
            color = item.image?.let { artTone(it) }?.let { vivid(it, dark = !light) } ?: ink
            playing = item.kind == IslandKind.MEDIA
        }
        bindMarks(open, ink)
        // Open: the bar (music: with its times; a timer or an update: on its own), then the buttons.
        val rows = islandRows(item)
        val selRow = if (island.isFocused) rows.getOrNull(islandRowAt) else null
        val bar = if (music) (media?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L else item.progress != null
        islandMore.visibility = if (open && (bar || item.actions.isNotEmpty())) View.VISIBLE else View.GONE
        findViewById<View>(R.id.islandTimes).visibility = if (bar) View.VISIBLE else View.GONE
        findViewById<View>(R.id.islandElapsed).visibility = if (music) View.VISIBLE else View.GONE
        findViewById<View>(R.id.islandLeft).visibility = if (music) View.VISIBLE else View.GONE
        islandBar.layoutParams = islandBar.layoutParams.apply { height = dp(if (selRow == IslandRow.BAR) 8 else 4) }
        if (music) showProgress() else item.progress?.let { islandBar.color = ink; islandBar.fraction = it }
        islandActions.visibility = if (item.actions.isEmpty()) View.GONE else View.VISIBLE
        if (islandActions.childCount != item.actions.size || islandActions.tag != item.key + item.actions.joinToString { "${it.icon}${it.label}" }) {
            islandActions.removeAllViews()
            islandActions.tag = item.key + item.actions.joinToString { "${it.icon}${it.label}" }
            item.actions.forEachIndexed { i, a ->
                val big = music && a.icon == R.drawable.ic_pause || music && a.icon == R.drawable.ic_play
                val v: View = if (a.icon != 0) ImageView(this).apply {
                    setImageResource(a.icon)
                    val p = dp(if (big) 10 else 9)
                    setPadding(p, p, p, p)
                } else TextView(this).apply {
                    text = a.label
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                }
                v.contentDescription = a.description ?: a.label
                val s = dp(if (big) 56 else 44)
                islandActions.addView(v, LinearLayout.LayoutParams(if (a.icon != 0) s else ViewGroup.LayoutParams.WRAP_CONTENT, s).apply {
                    if (i > 0) marginStart = dp(if (music) 26 else 18)
                })
            }
        }
        for (i in 0 until islandActions.childCount) {
            val v = islandActions.getChildAt(i)
            val on = selRow == IslandRow.ACTIONS && i == islandCol
            (v as? ImageView)?.setColorFilter(ink)
            (v as? TextView)?.let { it.setTextColor(ink) }
            // The picked button sits on a soft disc (a pill for a word) of the ink, like a lit top bar icon: no ring, no growth.
            v.background = if (on) GradientDrawable().apply {
                if (v is ImageView) shape = GradientDrawable.OVAL else cornerRadius = dp(22).toFloat()
                setColor(ink and 0xFFFFFF or 0x2E000000)
            } else null
            v.alpha = if (on || selRow != IslandRow.ACTIONS) 1f else 0.75f
        }
    }

    /** The other things on the island: small marks after the pill's text at rest, a row to pick from when open. */
    fun MainActivity.bindMarks(open: Boolean, ink: Int) {
        val item = islandItem
        val others = islandList.filter { it.key != item?.key }.take(2)
        val marks = findViewById<LinearLayout>(R.id.islandMarks)
        marks.visibility = if (!open && others.isNotEmpty()) View.VISIBLE else View.GONE
        marks.removeAllViews()
        // A running timer or stopwatch shows its time, not just its icon: that is what anyone glancing at it wants.
        if (!open) for (o in others) marks.addView(
            if (o.kind == IslandKind.TIMER || o.kind == IslandKind.STOPWATCH) TextView(this).apply {
                text = o.title
                textSize = 11f
                fontFeatureSettings = "tnum"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (glassActive()) ink(this, ink) else setTextColor(fg)
            } else if (o.mark != null) TextView(this).apply {
                // An icon alone says nothing ("a cloud?"): the mark carries its few characters ("2°/9°").
                text = o.mark
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (glassActive()) ink(this, ink) else setTextColor(fg)
                compoundDrawablePadding = dp(3)
                setCompoundDrawablesRelative(getDrawable(o.icon)?.mutate()?.apply {
                    setBounds(0, 0, dp(16), dp(16))
                    if (o.mono) setTint(if (glassActive()) ink else fg)
                }, null, null, null)
            } else islandMark(o, ink, dp(18), false),
            LinearLayout.LayoutParams(if (o.kind == IslandKind.TIMER || o.kind == IslandKind.STOPWATCH || o.mark != null) ViewGroup.LayoutParams.WRAP_CONTENT else dp(18), dp(18)).apply {
                marginStart = dp(6)
                gravity = Gravity.CENTER_VERTICAL
            })
        islandChips.visibility = if (open && islandList.size > 1) View.VISIBLE else View.GONE
        islandChips.removeAllViews()
        if (open && islandList.size > 1) {
            val sel = island.isFocused && islandRows(item).getOrNull(islandRowAt) == IslandRow.MARKS
            islandList.forEachIndexed { i, o ->
                val chip = islandMark(o, ink, dp(30), sel && i == islandCol)
                chip.alpha = if (o.key == item?.key) 1f else 0.55f
                islandChips.addView(chip, LinearLayout.LayoutParams(dp(30), dp(30)).apply { if (i > 0) marginStart = dp(10) })
            }
        }
    }

    /** One thing's mark: its picture in a circle, or its icon in the ink; [lit]: on a soft disc, as a picked button. */
    fun MainActivity.islandMark(o: IslandItem, ink: Int, size: Int, lit: Boolean) = ImageView(this).apply {
        val picture = o.image ?: teamImage(o.imageUri, o.badge)
        if (picture != null) {
            setImageBitmap(picture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0) }
            clipToOutline = true
        } else {
            setImageResource(o.icon.takeIf { it != 0 } ?: R.drawable.ic_music_note)
            if (o.mono) setColorFilter(ink)
            val p = size / 6
            setPadding(p, p, p, p)
        }
        if (lit) foreground = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(0, 0); setColor(ink and 0xFFFFFF or 0x40000000) }
        contentDescription = o.description ?: o.title
    }

    fun MainActivity.showProgress() {
        val c = media ?: return
        val s = c.playbackState
        val length = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (s == null || length <= 0L) return
        val at = if (seekTo >= 0) seekTo
            else islandPosition(s.position, s.lastPositionUpdateTime, android.os.SystemClock.elapsedRealtime(), s.playbackSpeed, mediaPlaying(c), length)
        islandBar.color = islandInk
        islandBar.fraction = at.toFloat() / length
        findViewById<TextView>(R.id.islandElapsed).apply { text = islandTime(at); setTextColor(islandInk) }
        findViewById<TextView>(R.id.islandLeft).apply { text = "-" + islandTime(length - at); setTextColor(islandInk) }
    }

    /**
     * Left and Right on the bar: 10 s a press, 30 s once the key has been held a while. The place shows at once; it is
     * sent once the keys stop, so a long hold is one seek, not dozens.
     */
    fun MainActivity.seekBy(dir: Int) {
        val c = media ?: return
        val s = c.playbackState ?: return
        val length = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: return
        val from = if (seekTo >= 0) seekTo
            else islandPosition(s.position, s.lastPositionUpdateTime, android.os.SystemClock.elapsedRealtime(), s.playbackSpeed, mediaPlaying(c), length)
        seekTo = (from + dir * (if (islandRepeat > 4) 30_000L else 10_000L)).coerceIn(0L, length)
        showProgress()
        ui.removeCallbacks(seekSend)
        ui.postDelayed(seekSend, 450)
    }

    /** The remote's arrows while the island is selected: rows with Up and Down, cells with Left and Right. */
    fun MainActivity.islandKey(key: Int, below: View?): View? {
        val item = islandItem ?: return null
        val rows = islandRows(item)
        val row = rows.getOrNull(islandRowAt)
        val size = when (row) { IslandRow.MARKS -> islandList.size; IslandRow.BAR -> 1; IslandRow.ACTIONS -> item.actions.size; null -> 0 }
        when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> if (islandRowAt > 0) moveIslandRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (islandRowAt < rows.size - 1) moveIslandRow(1) else return below ?: island
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                row == IslandRow.BAR -> seekBy(-1)
                islandCol > 0 -> { islandCol--; islandMoved(row) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                row == IslandRow.BAR -> seekBy(1)
                islandCol < size - 1 -> { islandCol++; islandMoved(row) }
                else -> return findViewById<View>(shownTop().first())
            }
            else -> return null
        }
        return island
    }

    fun MainActivity.moveIslandRow(d: Int) {
        val item = islandItem ?: return
        islandRowAt += d
        islandCol = when (islandRows(item).getOrNull(islandRowAt)) {
            IslandRow.MARKS -> islandList.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
            IslandRow.ACTIONS -> item.actions.size / 2
            else -> 0
        }
        bindIsland(item, islandOpen)
    }

    /** On the marks, moving shows that thing at once, the way tabs do. */
    fun MainActivity.islandMoved(row: IslandRow?) {
        if (row == IslandRow.MARKS) {
            islandPick = islandList.getOrNull(islandCol)?.key
            val rows = islandRows(islandList.getOrNull(islandCol))
            islandRowAt = rows.indexOf(IslandRow.MARKS).coerceAtLeast(0)
            showIsland()
            // The new thing has its own size: let the tile follow it without the fade.
            island.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            islandHang()
            island.requestLayout()
            glass.kick(300)
        } else islandItem?.let { bindIsland(it, islandOpen) }
    }

    /** OK: on the marks, go to that thing's buttons; on the bar, play or pause; on a button, press it. */
    fun MainActivity.islandOk() {
        val item = islandItem ?: return
        when (islandRows(item).getOrNull(islandRowAt)) {
            IslandRow.MARKS -> islandRows(item).indexOf(IslandRow.ACTIONS).takeIf { it >= 0 }?.let { islandRowAt = it; islandCol = item.actions.size / 2; bindIsland(item, islandOpen) }
            IslandRow.BAR -> media?.transportControls?.let { if (mediaPlaying(media)) it.pause() else it.play() }
            IslandRow.ACTIONS -> item.actions.getOrNull(islandCol)?.run?.invoke()
            null -> {}
        }
        ui.postDelayed({ showIsland() }, 150) // the source has changed its state by now
    }

    /** The top bar's island icon: timers, the stopwatch, the sleep timer and today's games, a few clicks from Home. */
    fun MainActivity.timerMenu(anchor: View) {
        val mins = { m: Int -> resources.getQuantityString(R.plurals.isl_minutes, m, m) }
        holdMenu(anchor, listOf(
            getString(R.string.isl_timer) to {
                holdMenu(anchor, ISLAND_TIMERS.map { m -> mins(m) to { IslandTimers.startTimer(this, m * 60_000L); islandStarted() } } +
                    (getString(R.string.isl_custom) to { showPanel(); editTimer() }), below = true)
            },
            getString(R.string.isl_stopwatch) to { IslandTimers.startStopwatch(this); islandStarted() },
            getString(R.string.isl_sleep_timer) to {
                holdMenu(anchor, ISLAND_SLEEP.map { m -> mins(m) to { IslandTimers.startSleep(this, m * 60_000L); islandStarted() } }, below = true)
            },
            getString(R.string.isl_games_now) to { showPanel(); pickGamesNow() },
        ), below = true)
    }

    /** Hold OK on the island: hide what it shows, or stop what sent it, right where it is seen. */
    fun MainActivity.islandHoldMenu() {
        val item = islandItem ?: return
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows += getString(R.string.isl_menu_hide) to { islandHidden += hideKey(item); showIsland() }
        when (item.kind) {
            IslandKind.ALERT, IslandKind.CALL -> item.key.split('|').getOrNull(1)?.let { pkg -> // a notification's key is "user|package|id|tag|uid"
                rows += getString(R.string.isl_menu_mute, appLabel(this, pkg) ?: pkg) to {
                    prefs.edit().putStringSet("isl_alert_off", prefs.getStringSet("isl_alert_off", emptySet())!! + pkg).apply()
                    islandHidden += item.key
                    showIsland()
                }
            }
            IslandKind.SPORTS, IslandKind.SPORTS_LIVE -> rows += getString(R.string.isl_menu_unfollow) to {
                IslandSports.unfollowFor(this, item.key)?.let { toast(it) }
                IslandSports.start(this)
                showIsland()
            }
            IslandKind.WEATHER -> mapOf("wx:rain" to "isl_wx_rain", "wx:tomorrow" to "isl_wx_tomorrow", "wx:temp" to "isl_wx_temp")[item.key]?.let { pref ->
                rows += getString(R.string.isl_menu_wx_off) to { prefs.edit().putBoolean(pref, false).apply(); showIsland() }
            }
            else -> {}
        }
        rows += getString(R.string.isl_menu_settings) to { openLauncherSettings(R.string.isl_on) }
        holdMenu(island, rows, below = true)
    }

    /** A timer of any length: minutes typed with the remote's number keys. */
    fun MainActivity.editTimer() {
        fun start(value: String, field: EditText) {
            val m = value.trim().toIntOrNull()?.takeIf { it in 1..999 } ?: return
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
            closeLauncherSettings()
            IslandTimers.startTimer(this, m * 60_000L)
            islandStarted()
        }
        lateinit var field: EditText
        field = textField("", getString(R.string.isl_custom_hint), InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE) { start(it, field) }
        field.filters = arrayOf(InputFilter.LengthFilter(3))
        showPage(getString(R.string.isl_custom), listOf(page(getString(R.string.isl_start)) { start(field.text.toString(), field) }),
            top = field.parent as LinearLayout, crumb = getString(R.string.isl_timers)) { pickIslandTimer(0) }
        val go = settingsList.getChildAt(1)
        go.nextFocusUpId = field.id
        field.nextFocusDownId = go.id
        field.requestFocus()
    }

    /** Once, when the island could show music and alerts but has no access: say why, and open the screen that gives it. */
    fun MainActivity.askIslandAccess() {
        if (!on("isl_on") || !homeSetUp() || setupDialog?.isShowing == true) return
        // A bar set up before the island has no island icon: say once where timers and Games now are.
        if (R.id.timerButton !in shownTop() && !prefs.getBoolean("isl_tip", false) && (prefs.getBoolean("isl_access_asked", false) || listenerOn())) {
            prefs.edit().putBoolean("isl_tip", true).apply()
            return toast(getString(R.string.isl_tip), long = true)
        }
        if (prefs.getBoolean("isl_access_asked", false) || listenerOn()) return
        prefs.edit().putBoolean("isl_access_asked", true).apply()
        homePopup(0, getString(R.string.isl_access_card_title), getString(R.string.isl_access_card_body), null,
            getString(R.string.isl_access_give) to { setupDialog?.dismiss(); openListenerAccess() },
            getString(R.string.setup_not_now) to { setupDialog?.dismiss() })
    }

    /** A timer just started: make sure the island is on to show it. */
    fun MainActivity.islandStarted() {
        if (!on("isl_on")) prefs.edit().putBoolean("isl_on", true).apply()
        showIsland()
    }

    // What the home screen calls. The work is in the MainActivity extensions above, which see the home screen's helpers.
    fun show() = a.showIsland()
    fun style() = a.styleIsland()
    fun setUp() = a.setUpIsland()
    fun start() = a.islandStart()
    fun stop() = a.islandStop()
    fun key(key: Int, below: View?) = a.islandKey(key, below)
    fun ok() = a.islandOk()
    fun menu(anchor: View) = a.timerMenu(anchor)
    fun hold() = a.islandHoldMenu()
    fun started() = a.islandStarted()
    fun askAccess() = a.askIslandAccess()
    fun customTimer() = a.editTimer()

    /** A logo from an address into [into], from the island's cache or fetched once; 24 dp, for settings rows. */
    fun logo(url: String, into: ImageView) {
        into.tag = url
        into.scaleType = ImageView.ScaleType.FIT_CENTER
        islandImages[url]?.let { return into.setImageBitmap(it) }
        a.posterIo.execute {
            val b = try { a.decode(url, a.dp(48), Bitmap.Config.ARGB_8888) } catch (e: Exception) { null } ?: return@execute
            a.ui.post { islandImages[url] = b; if (into.tag == url) into.setImageBitmap(b) }
        }
    }
}
