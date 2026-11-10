package dev.plume

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/** Focus changes center windows explicitly instead of letting the scroller jump to their edges. */
class RecentWindowStrip(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

/** Recent launches, not running tasks. The installed app list removes entries for apps that are gone. */
internal fun <T> recentApps(items: List<T>, opened: (T) -> Long): List<T> =
    items.map { it to opened(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(12).map { it.first }

/** The first Home press stays immediate; a second release opens the switcher without delaying Home. */
internal class HomePress {
    private var previous: Long? = null

    fun released(now: Long): Boolean {
        val twice = previous?.let { now >= it && now - it <= 350L } == true
        previous = if (twice) null else now
        return twice
    }

    fun reset() { previous = null }
}
