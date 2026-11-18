package dev.plume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Cue's progress: a rounded track with the played part filled, both in [color]. */
internal class CueBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var color = 0xFFFFFFFF.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; invalidate() } }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        paint.color = color and 0xFFFFFF or 0x38000000
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, r, r, paint)
        paint.color = color
        rect.right = maxOf(h, width * fraction)
        c.drawRoundRect(rect, r, r, paint)
    }
}

/**
 * Three still bars: tall and uneven while music plays, low while paused. They do not move, because a moving
 * pill made the launcher redraw about 30 times a second on a slow TV.
 */
internal class EqBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var color = 0xFFFFFFFF.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }
    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        paint.color = color
        val w = width / 6f
        val gap = (width - 3 * w) / 2
        val heights = if (playing) PLAYING else PAUSED
        for (i in 0..2) {
            // Paused bars stay uneven: three equal dots would read as a "more" menu.
            val h = maxOf(w, height * heights[i])
            val x = i * (w + gap)
            rect.set(x, (height - h) / 2, x + w, (height + h) / 2)
            c.drawRoundRect(rect, w / 2, w / 2, paint)
        }
    }

    private companion object {
        val PLAYING = floatArrayOf(0.6f, 1f, 0.75f)
        val PAUSED = floatArrayOf(0.3f, 0.5f, 0.4f)
    }
}
