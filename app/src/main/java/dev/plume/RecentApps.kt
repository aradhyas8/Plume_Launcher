package dev.plume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.AttributeSet
import android.util.LruCache
import android.widget.HorizontalScrollView
import java.io.File
import java.util.concurrent.Executors

/** Focus changes center windows explicitly instead of letting the scroller jump to their edges. */
class RecentWindowStrip(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

/**
 * Recent launches, not running tasks. The installed app list removes entries for apps that are gone. An app
 * removed from the list (see [removed]) comes back only when it is opened again after that.
 */
internal fun <T> recentApps(items: List<T>, removed: (T) -> Long = { 0L }, opened: (T) -> Long): List<T> =
    items.map { it to opened(it) }.filter { it.second > 0 && it.second > removed(it.first) }
        .sortedByDescending { it.second }.take(12).map { it.first }

/** The [keep] newest files by time; the rest are the ones to delete. */
internal fun <T> overLimit(files: List<T>, keep: Int, time: (T) -> Long): List<T> = files.sortedByDescending(time).drop(keep)

/** A second short release within the Home press interval opens the switcher. */
internal class HomePress {
    private var previous: Long? = null

    fun released(now: Long): Boolean {
        val twice = previous?.let { now >= it && now - it <= 350L } == true
        previous = if (twice) null else now
        return twice
    }

    fun reset() { previous = null }
}

/**
 * The last screen of up to [MAX] apps, captured by the Home service. Kept in memory and as small JPEGs in Plume's
 * no-backup folder, so they survive a restart of Plume but never leave the TV. The service and the home screen
 * run in one process and share this.
 */
internal object RecentPreviews {
    private const val MAX = 6
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val memory = LruCache<String, Bitmap>(MAX)
    private val io = Executors.newSingleThreadExecutor()

    private fun dir(c: Context) = File(c.noBackupFilesDir, "recent-previews")
    private fun file(c: Context, pkg: String) = File(dir(c), "$pkg.jpg").takeIf { packageName.matches(pkg) }

    fun put(c: Context, pkg: String, bmp: Bitmap) {
        val out = file(c, pkg) ?: return
        memory.put(pkg, bmp)
        io.execute {
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            try {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (!tmp.renameTo(out)) tmp.delete()
            } catch (_: java.io.IOException) {
                tmp.delete()
            }
            for (old in overLimit(dir(c).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().toList(), MAX) { it.lastModified() }) old.delete()
        }
    }

    // ponytail: decodes on the caller's thread (~5 ms per 640 px JPEG, at most six); move to io if it ever shows.
    fun get(c: Context, pkg: String): Bitmap? = memory.get(pkg) ?: file(c, pkg)?.takeIf { it.isFile }
        ?.let { BitmapFactory.decodeFile(it.path) }?.also { memory.put(pkg, it) }

    fun remove(c: Context, pkg: String) {
        memory.remove(pkg)
        val f = file(c, pkg) ?: return
        io.execute { f.delete() }
    }

    fun clear(c: Context) {
        memory.evictAll()
        val d = dir(c)
        io.execute { d.deleteRecursively() }
    }

    /** Low memory: drop the pictures in memory; the files stay. */
    fun trim() = memory.evictAll()
}
