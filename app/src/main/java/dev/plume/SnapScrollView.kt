package dev.plume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView

/**
 * A row that scrolls by whole items: when focus moves past an edge, the focused item lands flush with
 * that edge of the padded area, so the row always shows full tiles and never half-cut ones. The items
 * sit on a fixed grid that exactly fills the area, so aligning one edge aligns them all.
 *
 * The row runs its own glide. HorizontalScrollView jumps with no motion when steps come less than
 * 250 ms apart, which is every step of a held key. Here each step moves the glide's end and the row
 * keeps moving from where it is, so a held key gives one smooth run.
 */
class SnapScrollView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {
    private var gliding = false
    private val glide = ValueAnimator().apply {
        duration = 150
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            gliding = true
            scrollTo(it.animatedValue as Int, scrollY)
            gliding = false
        }
    }
    private var target = 0

    init {
        // Clip to our own bounds. The parent can't do it: it leaves clipping off so other views can lift.
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        clipChildren = false // or the row inside clips the first tile's focus lift to its own edge
    }

    // HorizontalScrollView asks this before every focus scroll. We do the scroll and tell it 0, so its own
    // scroller (and its instant jump) never runs.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val from = if (glide.isRunning) target else scrollX
        val d = snapDelta(rect.left, rect.right, from, paddingLeft, width - paddingLeft - paddingRight)
        if (d != 0) glideTo(from + d)
        return 0
    }

    // HorizontalScrollView moves focus only to an item within this distance of what's on screen, and drops it
    // otherwise. On a held key the glide runs a few items behind focus (Continue watching cards are wide), so
    // allow the whole row: the next item is always one step away.
    override fun getMaxScrollAmount() = maxOf(width, getChildAt(0)?.width ?: 0)

    private fun glideTo(x: Int) {
        val max = maxOf(0, (getChildAt(0)?.width ?: 0) - (width - paddingLeft - paddingRight))
        target = x.coerceIn(0, max)
        if (!isLaidOut) return scrollTo(target, scrollY)
        glide.cancel()
        glide.setIntValues(scrollX, target)
        glide.start()
    }

    // Any other scroll (a reset to the start) wins over the glide. A layout calls scrollTo with the place we're
    // already at (Continue watching adds cards as focus moves on); that must not stop the glide, or the row
    // stalls while focus walks off screen.
    override fun scrollTo(x: Int, y: Int) {
        if (!gliding && glide.isRunning && (x != scrollX || y != scrollY)) glide.cancel()
        super.scrollTo(x, y)
    }

    companion object {
        /** How far a row scrolled to [at] must move so [left]..[right] fits its padded area of width [inner]. */
        fun snapDelta(left: Int, right: Int, at: Int, padLeft: Int, inner: Int): Int {
            val l = at + padLeft
            val r = l + inner
            return when {
                left < l -> left - l
                right > r -> right - r
                else -> 0
            }
        }
    }
}
