package dev.plume

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/**
 * The "Shows from your apps" wallpaper: the picture of a show that an app publishes (Watch Next, or a home channel),
 * one at a time. If the user adds a key, TMDB can supply a backdrop when the app has no suitable picture.
 * Plume keeps one picture on disk. Choosing another wallpaper deletes it.
 */
internal object ShowsWall {
    /** The wallpaper choice, next to "res:…", "file:…", "folder:…" and "bing". */
    const val CHOICE = "shows"

    fun file(c: Context) = File(c.filesDir, "shows-hd.jpg")

    fun migrate(c: Context) {
        val old = File(c.filesDir, "shows.jpg")
        if (!old.isFile) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(old.path, bounds)
        val hd = file(c)
        if (hd.exists() || !fits(bounds.outWidth, bounds.outHeight)) old.delete()
        else old.renameTo(hd)
    }

    fun clear(c: Context) {
        file(c).delete()
        File(c.filesDir, "shows.jpg").delete()
    }

    /**
     * Whether the app's art is wide enough for a wallpaper. A thumbnail is (apps give 16:9 there). Poster art says its
     * shape: 0 is 16:9, 1 is 3:2 and 2 is 4:3; 1:1 (3), 2:3 (4) and a movie poster (5) are not, they would be cropped to a strip.
     */
    fun wide(thumbnail: Boolean, ratio: Int) = thumbnail || ratio in 0..2

    /** The 16:9 crop must fill a 1080p wallpaper. Reject tall art and very wide strips. */
    fun fits(w: Int, h: Int) = w >= 1920 && h >= 1080 && w * 10L >= h * 12L && w * 10L <= h * 25L

    /** [keys] in the order to try them: the one after [current] first, then on, and [current] last. An unknown [current] keeps the order. */
    fun order(keys: List<String>, current: String?): List<String> {
        val at = keys.indexOf(current)
        return if (at < 0) keys else keys.drop(at + 1) + keys.take(at + 1)
    }

    /** How long a picture that can never be used (not found, not wide, too big) is left alone: 6 hours. */
    const val DEAD_MS = 6 * 3_600_000L

    /** How long a picture that failed because of the network is left alone: 5 minutes. */
    const val NETWORK_MS = 5 * 60_000L

    /** Whether [key] is being left alone at [now], given when each failed picture may be tried again. */
    fun skipped(failedUntil: Map<String, Long>, key: String, now: Long) = (failedUntil[key] ?: 0L) > now
}
