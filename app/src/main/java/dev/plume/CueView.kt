package dev.plume

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Cue is never narrower than this (dp): with less room beside the clock and the icons it opens below the bar. */
private const val MIN_OPEN = 300

/**
 * Cue on the home screen: its view, its rows and keys, its menus, and the music it follows. It is written as
 * extensions of the home screen, so it uses the same helpers (the glass tile style, the ink, the settings pages)
 * without copies; the home screen holds one [CueView] and calls the few functions at the end.
 */
internal class CueView(private val a: MainActivity) {
    // ---------- Cue ----------
    // A glass tile at the top centre, the same tile as the app row (it follows its Glass style and theme), for what is
    // going on now: music, timers, alerts, live scores, the weather turning, an update, the network, Bluetooth. Each
    // source turns its state into CueItems (see Cue.kt); cueArrange orders them, the first shows in the pill
    // and the others as small marks beside it. Selected, the tile opens down into rows: the marks (Left and Right
    // pick which thing shows), the music's progress bar (Left and Right seek) and the shown thing's buttons. An alert
    // opens it for a few seconds by itself, without taking focus. Only on the home screen: Plume draws over no app.
    val cue by lazy { a.findViewById<LinearLayout>(R.id.cue) }
    val cueTop by lazy { a.findViewById<View>(R.id.cueTop) }
    val cueMore by lazy { a.findViewById<View>(R.id.cueMore) }
    val cueChips by lazy { a.findViewById<LinearLayout>(R.id.cueChips) }
    val cueArt by lazy { a.findViewById<ImageView>(R.id.cueArt) }
    val cueArt2 by lazy { a.findViewById<ImageView>(R.id.cueArt2) }
    val cueActions by lazy { a.findViewById<LinearLayout>(R.id.cueActions) }
    val cueBar by lazy { a.findViewById<CueBar>(R.id.cueProgress) }

    enum class CueRow { MARKS, BAR, ACTIONS }

    var cueOpen = false // the shape the tile has now; the wanted shape can be ahead of it while content fades
    var cueWant: Boolean? = null // the shape the running fade is heading for
    var cueInk = 0 // the ink last picked, for the progress between full refreshes
    var accent = 0 // the music art's colour (else the ink): its bars, its progress and its picked button
    var cueList: List<CueItem> = emptyList() // everything on Cue, in order, as last shown
    var cueItem: CueItem? = null // the one the tile shows
    var cuePick: String? = null // the key of the thing picked with the marks, while Cue is open
    var cueRowAt = 0 // the cursor in open Cue: which row, and which cell in it
    var cueCol = 0
    var cueRepeat = 0 // how long the key has been held, so seeking speeds up
    var peekKey: String? = null // the item Cue opened for by itself, and the peeks already shown
    val peeksShown = HashSet<String>()
    var seekTo = -1L // a seek still being chosen: shown at once, sent once the keys stop
    private var seekTrack: List<String?>? = null
    var updateDone = -1f // the update's download, 0..1, while one runs; -1: none
    val cueHidden = HashSet<String>() // items hidden with Hold OK › Hide, until Plume restarts
    private val loading = HashSet<String>() // pictures on their way, and addresses with none (ESPN has no logo for many teams)
    private val failed = HashSet<String>()

    // Music: the session Cue follows.
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
    val cueTick = Runnable { a.showCue() }
    val seekClear = Runnable { seekTo = -1L; seekTrack = null; a.showProgress() }
    val seekSend = Runnable {
        val player = media
        if (seekTo >= 0 && player?.track() == seekTrack) player?.transportControls?.seekTo(seekTo)
        a.ui.postDelayed(seekClear, 1500)
    }

    private fun MediaController.track() = metadata?.let {
        listOf(it.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), it.getString(MediaMetadata.METADATA_KEY_TITLE),
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), it.getString(MediaMetadata.METADATA_KEY_ARTIST))
    }

    private fun MainActivity.clearSeek() {
        ui.removeCallbacks(seekSend)
        ui.removeCallbacks(seekClear)
        seekTo = -1L
        seekTrack = null
    }

    fun on(key: String, default: Boolean = true) = a.prefs.getBoolean(key, default)

    fun MainActivity.setUpCue() {
        // The art is a cover: a soft square, a little rounder open.
        cueArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(if (v.width < dp(40)) 8 else 12).toFloat())
        }
        cueArt.clipToOutline = true
        styleCue()
        cue.setOnClickListener { cueOk() }
    }

    /** A glass tile like the app row's, open or not: the same Glass style, theme, tint and focus light. */
    fun MainActivity.styleCue() {
        style(cue, dp(CUE_ROUND), card = true, lift = 1f, rise = false, sheen = false) { focused ->
            // An alert selected stays while Cue is selected, then goes if its time is over.
            CueAlerts.held = if (focused) cueItem?.key else null
            if (focused) {
                lastTop = cue
                peekKey?.let { peeksShown += it } // selected while it peeked: it has been seen
                cuePick = null
                cueRowAt = Int.MAX_VALUE // showCue puts the cursor on the buttons
            }
            showCue()
        }
        showCue()
    }

    /** Sources start with the home screen and stop with it: Cue only shows there, so nothing runs for nothing. */
    fun MainActivity.cueStart() {
        Cue.listener = { showCue() }
        CueTimers.init(this)
        CueWeather.start(this)
        CueSports.start(this)
        try {
            mediaSessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaAccess, ui)
        } catch (e: SecurityException) {
            // No notification access: refreshMedia finds no music.
        }
        refreshMedia()
    }

    fun MainActivity.cueStop() {
        Cue.listener = null
        CueWeather.stop()
        CueSports.stop()
        mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        media?.unregisterCallback(mediaChanged)
        media = null
        clearSeek()
        ui.removeCallbacks(cueTick)
    }

    fun MainActivity.refreshMedia() = pickMedia(try { mediaSessions.getActiveSessions(mediaAccess) } catch (e: SecurityException) { emptyList() })

    fun MainActivity.pickMedia(list: List<MediaController>) {
        val pick = cuePick(list, { a.mediaPlaying(it) }) { mediaTitle(it.metadata) != null }
        if (pick?.sessionToken != media?.sessionToken) {
            clearSeek()
            media?.unregisterCallback(mediaChanged)
            media = pick
            pausedAt = 0L
            pick?.registerCallback(mediaChanged, ui)
        } else if (seekTo >= 0 && media?.track() != seekTrack) clearSeek()
        showCue()
    }

    fun MainActivity.mediaPlaying(c: MediaController?) = c?.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    fun MainActivity.mediaTitle(m: MediaMetadata?) = (m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }

    /** The app can take a seek, and the track has a length to seek in. */
    fun MainActivity.mediaSeekable(c: MediaController?) = c != null && (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L &&
        (c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L

    /** The picture to show: the full-size art when the app gives its address (loaded once), else the session's bitmap. */
    fun MainActivity.cueArtwork(m: MediaMetadata): Bitmap? {
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { m.getString(it)?.takeIf { u -> u.startsWith("http") || u.startsWith("content") || u.startsWith("android.resource") } }
        if (uri != artUri) {
            artUri = uri
            artSharp = uri?.let { picture(it, dp(160)) { b -> if (artUri == uri) { artSharp = b; showCue() } } }
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

    /** The music as a Cue item: playing, or paused for less than the time the settings allow. */
    fun MainActivity.mediaItem(now: Long): CueItem? {
        if (!on("isl_music")) return null
        val c = media ?: return null
        val m = c.metadata ?: return null
        val title = mediaTitle(m) ?: return null
        val playing = mediaPlaying(c)
        val clock = android.os.SystemClock.elapsedRealtime()
        pausedAt = if (playing) 0L else pausedAt.takeIf { it > 0 } ?: clock
        val idle = prefs.getLong("isl_idle", CUE_IDLE_MS)
        if (idle > 0 && cueIdle(pausedAt, clock, idle, cue.isFocused)) return null
        val who = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = cueArtwork(m)
        val app = appLabel(this, c.packageName)
        val can = c.playbackState?.actions ?: 0L
        val t = c.transportControls
        // An app that names no actions gets all three; one that names them gets only what it can do.
        val prev = can == 0L || can and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        val next = can == 0L || can and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        return CueItem("media", if (playing) CueKind.MEDIA else CueKind.MEDIA_PAUSED, title, who,
            icon = R.drawable.ic_music_note, mono = art == null, image = art, since = now,
            until = if (!playing && !cue.isFocused && idle > 0L) now + (idle - (clock - pausedAt)) else Long.MAX_VALUE,
            actions = listOfNotNull(
                CueAction(R.drawable.ic_skip_previous, description = getString(R.string.media_previous)) { t.skipToPrevious() }.takeIf { prev },
                CueAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, description = getString(R.string.media_play_pause)) { if (mediaPlaying(media)) t.pause() else t.play() },
                CueAction(R.drawable.ic_skip_next, description = getString(R.string.media_next)) { t.skipToNext() }.takeIf { next },
            ),
            description = listOfNotNull(title, who, app).joinToString(", "))
    }

    /** Plume's own update, which the home screen knows itself. The network is not here: the Wi-Fi icon shows it. */
    fun MainActivity.systemItems(now: Long): List<CueItem> = listOfNotNull(
        if (on("isl_update") && updateDone >= 0f)
            CueItem("update", CueKind.UPDATE, getString(R.string.isl_update_title),
                if (updateDone >= 1f) getString(R.string.isl_update_installing) else getString(R.string.isl_update_progress, availableUpdate() ?: "", (updateDone * 100).toInt()),
                R.drawable.ms_system_update, progress = updateDone, since = now) else null,
    )

    /** Everything on Cue now, in order. */
    /** What "Hide this" hides: the item, or for music the track, so the next song shows again. */
    fun MainActivity.hideKey(i: CueItem) = if (i.kind == CueKind.MEDIA || i.kind == CueKind.MEDIA_PAUSED) "media:${i.title}" else i.key

    fun MainActivity.cueAll(now: Long): List<CueItem> {
        if (!on("isl_on")) return emptyList()
        val all = ArrayList<CueItem>()
        mediaItem(now)?.let { all += it }
        all += CueTimers.items(now)
        all += CueAlerts.items(now, prefs)
        if (on("isl_sports")) all += CueSports.items(now)
        all += CueWeather.items(now)
        all += systemItems(now)
        return cueArrange(all.filter { hideKey(it) !in cueHidden }, now)
    }

    /** The home screen is what's in front: no panel, no All apps over it. */
    fun MainActivity.homeInFront() = resumed && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE

    fun MainActivity.cueRows(item: CueItem?): List<CueRow> = listOfNotNull(
        CueRow.MARKS.takeIf { cueList.size > 1 },
        CueRow.BAR.takeIf { item?.kind.let { it == CueKind.MEDIA || it == CueKind.MEDIA_PAUSED } && mediaSeekable(media) },
        CueRow.ACTIONS.takeIf { item != null && item.actions.isNotEmpty() },
    )

    /** Builds everything again from the sources: the items, which one shows, open or not, and when to look again. */
    fun MainActivity.showCue() {
        if (isDestroyed || !glassReady()) return // a delayed refresh after a theme change rebuilt the screen
        val now = System.currentTimeMillis()
        cueList = cueAll(now)
        ui.removeCallbacks(cueTick)
        if (cueList.isEmpty()) {
            if (cue.isFocused) findViewById<View?>(shownTop().first())?.requestFocus()
            if (lastTop === cue) lastTop = null
            cue.visibility = View.GONE
            cueOpen = false
            cueWant = null
            return
        }
        // An alert takes the pill for its moment (Alert time), only while nothing else is going on on the screen.
        val peek = if (!cue.isFocused && homeInFront()) cuePeek(cueList, now)?.takeIf { "${it.key}@${it.peekUntil}" !in peeksShown } else null
        val newPeek = peek?.let { "${it.key}@${it.peekUntil}" }
        // A screen reader says what opened Cue by itself; nobody looked at it on purpose.
        if (newPeek != null && newPeek != peekKey) cue.announceForAccessibility(peek.description ?: peek.title)
        peekKey = newPeek
        val focused = cue.isFocused
        val item = peek ?: (if (focused) cueList.firstOrNull { it.key == cuePick } else null) ?: cueList[0]
        cueItem = item
        val open = focused
        val rows = cueRows(item)
        if (focused) {
            if (cueRowAt >= rows.size) { // just opened: the buttons, on the middle one (Play for music)
                cueRowAt = rows.indexOf(CueRow.ACTIONS).takeIf { it >= 0 } ?: (rows.size - 1).coerceAtLeast(0)
                cueCol = if (rows.getOrNull(cueRowAt) == CueRow.ACTIONS) item.actions.size / 2 else 0
            }
            if (rows.getOrNull(cueRowAt) == CueRow.MARKS) cueCol = cueList.indexOf(item).coerceAtLeast(0)
        }
        cue.visibility = View.VISIBLE
        if (cueWant == null && open == cueOpen) bindCue(item, open) else shapeCue(open)
        // Look again when something changes by itself: a second passes on a timer, or an item or a peek runs out.
        val next = cueList.flatMap { listOf(it.until, it.peekUntil) }.filter { it > now }.minOrNull()
        // Each item says how often its text changes; open, the music's bar and times move every second.
        val period = (cueList.mapNotNull { it.tick.takeIf { t -> t > 0 } } + listOfNotNull(1000L.takeIf { open && (media != null || item.progress != null) })).minOrNull()
        val wait = minOf(period ?: Long.MAX_VALUE, next?.let { it - now + 50 } ?: Long.MAX_VALUE)
        if (wait != Long.MAX_VALUE) ui.postDelayed(cueTick, wait)
    }

    /**
     * The content fades out, the tile changes shape, the new content fades in: the shape moves, the content never
     * stretches. A quick Up and Down lands on the last state asked for.
     */
    fun MainActivity.shapeCue(open: Boolean) {
        if (cueWant == open) return
        cueWant = open
        for (v in listOf(cueTop, cueMore, cueChips)) v.animate().cancel()
        cueChips.animate().alpha(0f).setStartDelay(0).setDuration(70).start()
        cueMore.animate().alpha(0f).setStartDelay(0).setDuration(70).start()
        cueTop.animate().alpha(0f).setStartDelay(0).setDuration(70).withEndAction {
            val want = cueWant ?: return@withEndAction
            cueWant = null
            reshapeCue(want)
        }.start()
    }

    fun MainActivity.reshapeCue(open: Boolean) {
        val item = cueItem ?: return
        cueOpen = open
        android.transition.TransitionManager.beginDelayedTransition(cue.parent as ViewGroup,
            android.transition.ChangeBounds().setDuration(240).setInterpolator(CUE_EASE))
        val fit = if (open) cueOpenWidth() else 0
        cue.layoutParams.width = if (open) fit.coerceAtLeast(dp(MIN_OPEN)) else ViewGroup.LayoutParams.WRAP_CONTENT
        // Too little room beside the clock and the icons: the open tile comes down below the top bar, not over it.
        val below = open && fit < dp(MIN_OPEN)
        // At rest the pill is one line, as high as the top bar's icons (36 dp), on their line.
        (cue.layoutParams as FrameLayout.LayoutParams).topMargin = if (below) (cue.parent as View).height else 0
        cueShape(cue, open)
        bindCue(item, open)
        // The tile hangs over the screen: its extra height is taken back by the margin, so the rows below never move.
        cue.measure(View.MeasureSpec.makeMeasureSpec(cue.layoutParams.width.coerceAtLeast(0),
            if (open) View.MeasureSpec.EXACTLY else View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.UNSPECIFIED)
        cueHang()
        cue.requestLayout()
        glass.kick(500) // the glass under it follows the new size
        cueChips.animate().alpha(1f).setStartDelay(140).setDuration(150).start()
        cueTop.animate().alpha(1f).setStartDelay(140).setDuration(150).withEndAction(null).start()
        cueMore.animate().alpha(1f).setStartDelay(170).setDuration(150).start()
        ui.postDelayed({ if (!isDestroyed) cueItem?.let { bindCue(it, cueOpen) } }, 320) // the ink again, over the new size
    }

    /** The tile hangs over the screen: its extra height (and any drop below the bar) is taken back, so nothing moves. */
    fun MainActivity.cueHang() {
        val lp = cue.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = -(cue.measuredHeight + lp.topMargin - dp(36)).coerceAtLeast(0)
    }

    /**
     * The widest the open tile can be, centred, without covering the clock column or the first top bar icon: at most
     * 400 dp. Measured on the screen, as the clock's text, the date and the icons the user picked all change it.
     */
    fun MainActivity.cueOpenWidth(): Int {
        val bar = cue.parent as View
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

    /**
     * An item's pictures. A game shows its two logos only when both have come (ESPN's 500 px logos); with one missing
     * it shows neither, and the item's icon stands in. (Sports send the league's logo instead when a team has none.)
     */
    fun MainActivity.cuePictures(o: CueItem): Pair<Bitmap?, Bitmap?> {
        val first = o.image ?: cueImage(o.imageUri)
        if (o.image2Uri == null) return first to null
        val second = cueImage(o.image2Uri)
        return if (first != null && second != null) first to second else null to null
    }

    /** A team logo, twice the open art's size so it stays sharp; null until it has come. */
    fun MainActivity.cueImage(uri: String?): Bitmap? = uri?.let { picture(it, dp(136)) { cueItem?.let { i -> bindCue(i, cueOpen) } } }

    /**
     * A picture from an address, [px] wide, from the home screen's poster cache (so it is bounded) or fetched once;
     * null until it comes, then [done] runs on the main thread. An address with no picture is asked once only.
     */
    fun MainActivity.picture(uri: String, px: Int, done: (Bitmap) -> Unit): Bitmap? {
        val key = "$uri@$px"
        posters.get(key)?.let { return it }
        if (key in failed || !loading.add(key)) return null
        posterIo.execute {
            val b = try { decode(uri, px, Bitmap.Config.ARGB_8888) } catch (e: Exception) { null }
            ui.post {
                loading.remove(key)
                if (b == null) failed += key else if (!isDestroyed) { posters.put(key, b); done(b) }
            }
        }
        return null
    }


    /** Puts [item] on the tile in its [open] or resting form. */
    fun MainActivity.bindCue(item: CueItem, open: Boolean) {
        // Cue is glass over the picture, open or not, so its text, bars and buttons take the ink fitted to the
        // wallpaper behind it, with the top bar's shadow. A solid tile (Glass off) keeps the theme's colour.
        val glassy = glassActive()
        val ink = if (!glassy) fg else if (cue.isShown && cue.width > 0) inkBehind(listOf(cue)) else iconInk
        cueInk = ink
        val music = item.kind == CueKind.MEDIA || item.kind == CueKind.MEDIA_PAUSED
        val rows = cueRows(item)
        val selRow = if (cue.isFocused) rows.getOrNull(cueRowAt) else null
        // The bars move with the music, in the art's colour: pale on a light ink and deep on a dark one.
        accent = item.image?.takeIf { music }?.let { artTone(it) }?.let { cueAccent(it, Color.luminance(ink) > 0.5f) } ?: ink
        val (picture, picture2) = cuePictures(item)
        cueBind(cue, item, open, ink, { if (glassy) ink(it, ink) else { it.setShadowLayer(0f, 0f, 0f, 0); it.setTextColor(fg) } },
            picture, picture2, if (selRow == CueRow.ACTIONS) cueCol else -1,
            if (music) accent and 0xFFFFFF or 0x59000000 else ink and 0xFFFFFF or 0x2E000000)
        // The music's mark as the Shows row shows it: a plain mark in the ink, or its brand colour with Colour on.
        val pkg = media?.packageName
        val logo = pkg?.let { MainActivity.LOGOS[it] }?.takeIf { !it.second }
        findViewById<ImageView>(R.id.cueApp).apply {
            visibility = if (open && music) View.VISIBLE else View.GONE
            setImageDrawable(if (!music || pkg == null) null else if (logo != null) getDrawable(logo.first)!!.mutate().apply { setTint(MainActivity.BRAND_COLOURS[pkg]?.takeIf { cwColour() } ?: ink) } else sourceIcon(pkg))
        }
        findViewById<EqBars>(R.id.cueEq).apply {
            visibility = if (!open && music) View.VISIBLE else View.GONE
            color = accent
            playing = item.kind == CueKind.MEDIA
        }
        bindMarks(open, ink)
        val bar = if (music) (media?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L else item.progress != null
        // Open: the bar with its times and the buttons. The pill at rest has none of them.
        if (open && bar) cueMore.visibility = View.VISIBLE
        findViewById<View>(R.id.cueTimes).visibility = if (bar) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cueElapsed).visibility = if (music && open) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cueLeft).visibility = if (music && open) View.VISIBLE else View.GONE
        cueBar.layoutParams = (cueBar.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(if (!open) 2 else if (selRow == CueRow.BAR) 8 else 4)
            marginStart = if (open) dp(10) else 0
            marginEnd = if (open) dp(10) else 0
        }
        if (music) showProgress() else item.progress?.let { cueBar.color = ink; cueBar.fraction = it }
    }

    /** A row of items to choose from while Cue is open. */
    fun MainActivity.bindMarks(open: Boolean, ink: Int) {
        val item = cueItem
        val scroll = cueChips.parent as android.widget.HorizontalScrollView
        scroll.visibility = if (open && cueList.size > 1) View.VISIBLE else View.GONE
        cueChips.removeAllViews()
        if (open && cueList.size > 1) {
            val sel = cue.isFocused && cueRows(item).getOrNull(cueRowAt) == CueRow.MARKS
            cueList.forEachIndexed { i, o ->
                val active = o.key == item?.key
                val chip = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(cueMark(o, ink, dp(24)), LinearLayout.LayoutParams(dp(24), dp(24)))
                    if (active || cueList.size <= 3) {
                        val name = when (o.kind) {
                            CueKind.CALL, CueKind.ALERT -> R.string.isl_alerts
                            CueKind.TIMER, CueKind.TIMER_DONE -> R.string.isl_timer
                            CueKind.STOPWATCH -> R.string.isl_stopwatch
                            CueKind.MEDIA, CueKind.MEDIA_PAUSED -> R.string.isl_music
                            CueKind.SPORTS, CueKind.SPORTS_LIVE -> R.string.isl_games
                            CueKind.UPDATE -> R.string.isl_update_row
                            CueKind.WEATHER -> R.string.isl_wx_rain_row
                        }
                        addView(TextView(context).apply {
                            setText(name)
                            textSize = 12f
                            maxLines = 1
                            maxWidth = dp(72)
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(4), 0, 0, 0)
                            setTextColor(ink)
                        })
                    }
                    setPadding(dp(6), 0, dp(6), 0)
                    alpha = if (active) 1f else 0.72f
                    if (active) background = GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        // No ring (see PR 191): the picked mark only gets a stronger disc.
                        setColor(ink and 0xFFFFFF or if (sel && i == cueCol) 0x40000000 else 0x22000000)
                    }
                }
                cueChips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    if (i > 0) marginStart = dp(4)
                })
                if (active) chip.post { if (chip.parent != null) chip.requestRectangleOnScreen(Rect(0, 0, chip.width, chip.height), true) }
            }
        }
    }

    /** One item's mark: its picture in a circle, or its icon in the ink. */
    fun MainActivity.cueMark(o: CueItem, ink: Int, size: Int) = ImageView(this).apply {
        val picture = cuePictures(o).first
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
        contentDescription = o.description ?: o.title
    }

    fun MainActivity.showProgress() {
        val c = media ?: return
        val s = c.playbackState
        val length = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (s == null || length <= 0L) return
        val at = if (seekTo >= 0) seekTo
            else cuePosition(s.position, s.lastPositionUpdateTime, android.os.SystemClock.elapsedRealtime(), s.playbackSpeed, mediaPlaying(c), length)
        cueBar.color = accent
        cueBar.fraction = at.toFloat() / length
        findViewById<TextView>(R.id.cueElapsed).apply { text = cueTime(at); if (glassActive()) ink(this, cueInk) else setTextColor(cueInk) }
        findViewById<TextView>(R.id.cueLeft).apply { text = "-" + cueTime(length - at); if (glassActive()) ink(this, cueInk) else setTextColor(cueInk) }
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
            else cuePosition(s.position, s.lastPositionUpdateTime, android.os.SystemClock.elapsedRealtime(), s.playbackSpeed, mediaPlaying(c), length)
        seekTrack = c.track()
        ui.removeCallbacks(seekClear)
        seekTo = (from + dir * (if (cueRepeat > 4) 30_000L else 10_000L)).coerceIn(0L, length)
        showProgress()
        ui.removeCallbacks(seekSend)
        ui.postDelayed(seekSend, 450)
    }

    /** The remote's arrows while Cue is selected: rows with Up and Down, cells with Left and Right. */
    fun MainActivity.cueKey(key: Int, below: View?): View? {
        val item = cueItem ?: return null
        val rows = cueRows(item)
        val row = rows.getOrNull(cueRowAt)
        val size = when (row) { CueRow.MARKS -> cueList.size; CueRow.BAR -> 1; CueRow.ACTIONS -> item.actions.size; null -> 0 }
        when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> if (cueRowAt > 0) moveCueRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cueRowAt < rows.size - 1) moveCueRow(1) else return below ?: cue
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                row == CueRow.BAR -> seekBy(-1)
                cueCol > 0 -> { cueCol--; cueMoved(row) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                row == CueRow.BAR -> seekBy(1)
                cueCol < size - 1 -> { cueCol++; cueMoved(row) }
                else -> return findViewById<View>(shownTop().first())
            }
            else -> return null
        }
        return cue
    }

    fun MainActivity.moveCueRow(d: Int) {
        val item = cueItem ?: return
        cueRowAt += d
        cueCol = when (cueRows(item).getOrNull(cueRowAt)) {
            CueRow.MARKS -> cueList.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
            CueRow.ACTIONS -> item.actions.size / 2
            else -> 0
        }
        bindCue(item, cueOpen)
    }

    /** On the marks, moving shows that thing at once, the way tabs do. */
    fun MainActivity.cueMoved(row: CueRow?) {
        if (row == CueRow.MARKS) {
            cuePick = cueList.getOrNull(cueCol)?.key
            val rows = cueRows(cueList.getOrNull(cueCol))
            cueRowAt = rows.indexOf(CueRow.MARKS).coerceAtLeast(0)
            showCue()
            // The new thing has its own size: let the tile follow it without the fade.
            cue.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            cueHang()
            cue.requestLayout()
            glass.kick(300)
        } else cueItem?.let { bindCue(it, cueOpen) }
    }

    /** OK: on the marks, go to that thing's buttons; on the bar, play or pause; on a button, press it. */
    fun MainActivity.cueOk() {
        val item = cueItem ?: return
        when (cueRows(item).getOrNull(cueRowAt)) {
            CueRow.MARKS -> cueRows(item).indexOf(CueRow.ACTIONS).takeIf { it >= 0 }?.let { cueRowAt = it; cueCol = item.actions.size / 2; bindCue(item, cueOpen) }
            CueRow.BAR -> media?.transportControls?.let { if (mediaPlaying(media)) it.pause() else it.play() }
            CueRow.ACTIONS -> item.actions.getOrNull(cueCol)?.run?.invoke()
            null -> {}
        }
        ui.postDelayed({ showCue() }, 150) // the source has changed its state by now
    }

    /**
     * The top bar's Cue icon: the stopwatch, the last timer lengths used (hold OK on one to take it off), any other
     * length, and today's games, one click from Home. Nothing to set up: the list follows what people start.
     */
    fun MainActivity.timerMenu(anchor: View) {
        val mins = { m: Int -> resources.getQuantityString(R.plurals.isl_minutes, m, m) }
        val recent = recentTimers()
        holdMenu(anchor, listOf(getString(R.string.isl_stopwatch) to { CueTimers.startStopwatch(this); cueStarted("stopwatch") }) +
            recent.map { m -> mins(m) to { startTimer(m) } } +
            listOf(
                getString(R.string.isl_custom) to { showPanel(); editTimer() },
                getString(R.string.isl_games_now) to { showPanel(); pickSport(teams = false) },
            ), below = true,
            holds = listOf(null) + recent.map { m -> { saveRecent(recentTimers() - m); timerMenu(anchor) } })
    }

    private val RECENT_PREF = "isl_recent_t" // the timer icon's lengths, newest first; "" when all were taken off

    fun MainActivity.recentTimers(): List<Int> =
        prefs.getString(RECENT_PREF, null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: CUE_RECENT

    private fun MainActivity.saveRecent(list: List<Int>) = prefs.edit().putString(RECENT_PREF, list.joinToString(",")).apply()

    /** Starts a timer of [m] minutes from any menu, and puts it first in the timer icon's list. */
    fun MainActivity.startTimer(m: Int) {
        saveRecent(islRecent(recentTimers(), m))
        CueTimers.startTimer(this, m * 60_000L)
        cueStarted("timer")
    }

    /** Hold OK on Cue: hide what it shows, or stop what sent it, right where it is seen. */
    fun MainActivity.cueHoldMenu() {
        val item = cueItem ?: return
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows += getString(R.string.isl_menu_hide) to { cueHidden += hideKey(item); showCue() }
        when (item.kind) {
            CueKind.ALERT, CueKind.CALL -> item.key.split('|').getOrNull(1)?.let { pkg -> // a notification's key is "user|package|id|tag|uid"
                rows += getString(R.string.isl_menu_mute, appLabel(this, pkg) ?: pkg) to {
                    prefs.edit().putStringSet("isl_alert_off", prefs.getStringSet("isl_alert_off", emptySet())!! + pkg).apply()
                    cueHidden += item.key
                    showCue()
                }
            }
            CueKind.SPORTS, CueKind.SPORTS_LIVE -> rows += getString(R.string.isl_menu_unfollow) to {
                CueSports.unfollowFor(this, item.key)?.let { toast(it) }
                CueSports.start(this)
                showCue()
            }
            CueKind.WEATHER -> mapOf("wx:rain" to "isl_wx_rain")[item.key]?.let { pref ->
                rows += getString(R.string.isl_menu_wx_off) to { prefs.edit().putBoolean(pref, false).apply(); showCue() }
            }
            else -> {}
        }
        rows += getString(R.string.isl_menu_settings) to { openLauncherSettings(R.string.isl_on) }
        holdMenu(cue, rows, below = true)
    }

    /** A timer of any length: minutes typed with the remote's number keys. */
    fun MainActivity.editTimer() {
        fun start(value: String, field: EditText) {
            val m = value.trim().toIntOrNull()?.takeIf { it in 1..999 } ?: return
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
            closeLauncherSettings()
            startTimer(m)
        }
        lateinit var field: EditText
        field = textField("", getString(R.string.isl_custom_hint), InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE) { start(it, field) }
        field.filters = arrayOf(InputFilter.LengthFilter(3))
        showPage(getString(R.string.isl_custom), listOf(page(getString(R.string.isl_start)) { start(field.text.toString(), field) }),
            top = field.parent as LinearLayout, crumb = getString(R.string.isl_timers)) { pickCueTimer(0) }
        val go = settingsList.getChildAt(1)
        go.nextFocusUpId = field.id
        field.nextFocusDownId = go.id
        field.requestFocus()
    }

    /** Once, when Cue could show music and alerts but has no access: say why, and open the screen that gives it. */
    fun MainActivity.askCueAccess() {
        if (!on("isl_on") || !homeSetUp() || setupDialog?.isShowing == true) return
        // A bar set up before Cue has no Cue icon: say once where timers and Games now are.
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

    /** A timer just started: make sure Cue is on to show it. */
    fun MainActivity.cueStarted(key: String) {
        if (!on("isl_on")) prefs.edit().putBoolean("isl_on", true).apply()
        showCue()
        cue.requestFocus()
        cuePick = key
        cueRowAt = Int.MAX_VALUE
        showCue()
    }

    // What the home screen calls. The work is in the MainActivity extensions above, which see the home screen's helpers.
    fun show() = a.showCue()
    fun style() = a.styleCue()
    fun setUp() = a.setUpCue()
    fun start() = a.cueStart()
    fun stop() = a.cueStop()
    fun key(key: Int, below: View?) = a.cueKey(key, below)
    fun ok() = a.cueOk()
    fun menu(anchor: View) = a.timerMenu(anchor)
    fun hold() = a.cueHoldMenu()
    fun started(key: String) = a.cueStarted(key)
    fun askAccess() = a.askCueAccess()
    fun customTimer() = a.editTimer()
    fun startTimer(m: Int) = a.startTimer(m)

    /** A logo from an address into [into], 24 dp, for settings rows. */
    fun logo(url: String, into: ImageView) {
        into.tag = url
        into.scaleType = ImageView.ScaleType.FIT_CENTER
        a.picture(url, a.dp(48)) { if (into.tag == url) into.setImageBitmap(it) }?.let(into::setImageBitmap)
    }
}

/**
 * The music art's colour, strong enough to read as a colour on the glass: the art's mean hue, at least half
 * saturated, bright over a dark wallpaper (light ink) and deep over a light one. A grey cover stays grey.
 */
internal fun cueAccent(mean: Int, lightInk: Boolean): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(mean, hsv)
    if (hsv[1] > 0.12f) hsv[1] = maxOf(hsv[1], 0.55f)
    hsv[2] = if (lightInk) 0.95f else 0.45f
    return Color.HSVToColor(hsv)
}

/** Cue's change of shape: quick to start, a long soft landing. */
private val CUE_EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
