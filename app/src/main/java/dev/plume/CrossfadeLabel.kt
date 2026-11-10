package dev.plume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Crossfade text only: the focus surface and the view's visibility keep their own animations. */
class CrossfadeLabel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private var previous: Bitmap? = null
    private var blend = 1f
    private var animator: ValueAnimator? = null
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun change(next: CharSequence, duration: Long) {
        if (text.toString() == next.toString()) {
            text = next // focus can change the arrow spans without restarting the content transition
            return
        }
        val before = if (duration > 0 && isShown && width > 0 && height > 0 && ValueAnimator.areAnimatorsEnabled()) {
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
                    onDraw(canvas) // capture the current blend too, if a fast source change interrupts it
                }
            } catch (_: OutOfMemoryError) { null }
        } else null
        animator?.cancel()
        previous = before
        text = next
        if (before == null) { blend = 1f; invalidate(); return }
        blend = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { blend = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    previous = null
                    blend = 1f
                    animator = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val old = previous
        if (old == null) { super.onDraw(canvas); return }
        fadePaint.alpha = ((1f - blend) * 255).toInt()
        canvas.drawBitmap(old, scrollX.toFloat(), scrollY.toFloat(), fadePaint)
        val layer = canvas.saveLayerAlpha(scrollX.toFloat(), scrollY.toFloat(),
            (scrollX + width).toFloat(), (scrollY + height).toFloat(), (blend * 255).toInt())
        super.onDraw(canvas)
        canvas.restoreToCount(layer)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        previous = null
        super.onDetachedFromWindow()
    }
}
