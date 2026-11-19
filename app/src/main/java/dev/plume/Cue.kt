package dev.plume

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cue's shared model. Each source (music, timers, alerts, sports, weather, system) turns what it knows into
 * [CueItem]s; the home screen asks every source for its items, [cueArrange] orders them, and Cue shows
 * the first in its pill and the rest in the open tile. Plain Kotlin apart from the picture types, so the
 * rules can be tested without Android.
 */

/** What an item is. A lower [rank] wins the pill; items of the same rank go newest first. */
internal enum class CueKind(val rank: Int) {
    CALL(0), TIMER_DONE(1), ALERT(2), MEDIA(4), SPORTS_LIVE(5),
    TIMER(6), STOPWATCH(6), UPDATE(7), SPORTS(8), WEATHER(9), MEDIA_PAUSED(11),
}

/** A button in open Cue: a drawable [icon], or a short [label] when there is no fitting icon. */
internal class CueAction(val icon: Int = 0, val label: String? = null, val description: String? = null, val run: () -> Unit)

/**
 * One thing to show. Times are wall-clock milliseconds (System.currentTimeMillis()).
 * [key] is stable while the thing lasts: the same key is the same item, updated. [icon] is a drawable for the round
 * picture when there is no [image] or [imageUri]; [mono] tints it with Cue's ink. [image2Uri] is a second
 * picture shown at the end of the pill (the other team's logo). [progress] (0..1) draws the bar. [tick]: how often the
 * text changes, in ms (a timer: 1000; "Rain in 20 min": 60000; 0: never), so Cue asks again that often while it
 * is shown, and no more. Before [peekUntil] Cue opens by itself to show the item, without taking focus. After
 * [until] the item is gone.
 */
internal class CueItem(
    val key: String,
    val kind: CueKind,
    val title: String,
    val sub: String? = null,
    val icon: Int = 0,
    val mono: Boolean = true,
    val image: Bitmap? = null,
    val imageUri: String? = null,
    val image2Uri: String? = null,
    val progress: Float? = null,
    val tick: Long = 0L,
    val since: Long = 0L,
    val until: Long = Long.MAX_VALUE,
    val peekUntil: Long = 0L,
    val actions: List<CueAction> = emptyList(),
    val description: String? = null,
    val open: (() -> Unit)? = null, // opens the app at this item (a notification's own tap action)
) {
    /** The same item with another second line (a battery level that lands after the connect). */
    fun withSub(sub: String?) = CueItem(key, kind, title, sub, icon, mono, image, imageUri, image2Uri, progress, tick, since, until,
        peekUntil, actions, description, open)

    /** What Cue shows of it: the same text and times mean nothing to redraw. */
    val shown get() = "$key|$kind|$title|$sub|$progress|$since|$until|$peekUntil"
}

/** The items still current at [now], the pill's first: lowest rank, then newest. */
internal fun cueArrange(items: List<CueItem>, now: Long): List<CueItem> =
    items.filter { now < it.until }.sortedWith(compareBy<CueItem> { it.kind.rank }.thenByDescending { it.since })

/** The item Cue opens by itself for at [now], if any: the newest one still peeking. */
internal fun cuePeek(items: List<CueItem>, now: Long): CueItem? =
    items.filter { now < it.peekUntil && now < it.until }.maxByOrNull { it.since }

/** "4:05", "1:02:03", whole seconds. A [countdown] rounds up, so it shows 0:01 until it is really over; a stopwatch rounds down. */
internal fun cueClock(ms: Long, countdown: Boolean = true): String {
    val s = (ms.coerceAtLeast(0L) + if (countdown) 999 else 0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Cue's sizes and choices, shared by its view and its settings. */
internal const val CUE_ROUND = 24 // dp; the pill is 36 high, so it stays a full pill, and the open card is softly round
internal const val CUE_IDLE_MS = 5 * 60_000L // paused this long, the pill steps aside
internal val CUE_TIMERS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120) // minutes Plume settings offers
internal val CUE_RECENT = listOf(1, 5, 10, 30) // the timer icon's lengths before people start their own

/** The timer icon's lengths after a [used] timer: it comes first, a repeat is not listed twice, at most 4. */
internal fun islRecent(list: List<Int>, used: Int) = (listOf(used) + (list - used)).take(4)
internal const val CUE_OVERLAY_MS = 2_000L // how long an alert's pill shows over another app
internal val CUE_OVERLAY_TIMES = listOf(2_000L, 4_000L, 6_000L, 10_000L) // "Alert time over apps"
internal val CUE_IDLES = listOf(60_000L, CUE_IDLE_MS, 0L) // "Hide music when paused"; 0 is Never

/** An app's name, or null when it is gone or hidden from Plume. */
internal fun appLabel(ctx: Context, pkg: String): String? = try {
    ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
} catch (e: Exception) {
    null
}

/**
 * JSON from Cue's web sources (scores, weather), on the calling thread. Only a generic User-Agent goes with
 * the request: no account, no device id.
 */
internal fun cueJson(url: String): JSONObject {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        setRequestProperty("User-Agent", "tvhome-cue")
    }
    return try {
        JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
    } finally {
        c.disconnect()
    }
}

/**
 * Where sources tell the home screen that their items changed. Sources call [changed] from any thread; the home
 * screen sets [listener] while it is shown and gets the call on the main thread. [CueOverlay] hears every change too.
 */
internal object Cue {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null

    fun changed() {
        main.post {
            listener?.invoke()
            CueOverlay.update()
        }
    }
}
