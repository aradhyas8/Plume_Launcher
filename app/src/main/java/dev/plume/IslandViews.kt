package dev.plume

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The media island's progress: a rounded track with the played part filled, both in [color]. */
internal class IslandBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
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
 * Three bars that move while the island's track plays, and rest low while it is paused. They animate only while
 * shown, so a hidden island or a covered home screen costs nothing.
 */
internal class EqBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    var color = 0xFFFFFFFF.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }
    var playing = false
        set(v) { if (field != v) { field = v; update() } }

    private fun update() {
        if (playing && isAttachedToWindow && isShown && windowVisibility == VISIBLE) { if (!anim.isStarted) anim.start() } else anim.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changed: View, v: Int) { super.onVisibilityChanged(changed, v); update() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); update() }

    override fun onDraw(c: Canvas) {
        paint.color = color
        val w = width / 6f
        val gap = (width - 3 * w) / 2
        val t = anim.animatedValue as Float
        for (i in 0..2) {
            // Paused, the bars stand still at three heights: three equal dots would read as a "more" menu.
            val f = if (playing) 0.3f + 0.7f * abs(sin(2 * PI * (t * SPEED[i] + PHASE[i]))).toFloat() else REST[i]
            val h = maxOf(w, height * f)
            val x = i * (w + gap)
            rect.set(x, (height - h) / 2, x + w, (height + h) / 2)
            c.drawRoundRect(rect, w / 2, w / 2, paint)
        }
    }

    private companion object {
        val SPEED = intArrayOf(2, 3, 2) // whole turns per loop, so the loop has no jump
        val PHASE = floatArrayOf(0f, 0.3f, 0.65f)
        val REST = floatArrayOf(0.45f, 0.8f, 0.6f)
    }
}
