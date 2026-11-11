package dev.plume

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * Text that changes with a slide and a fade: the old text moves out by [change]'s offset and fades fast, the new text comes
 * in from the other side. Only the drawn text moves, so the view's own translation, alpha and focus animations stay free.
 */
class CrossfadeLabel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private var previous: Bitmap? = null
    private var blend = 1f
    private var dx = 0f
    private var dy = 0f
    private var animator: ValueAnimator? = null
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** [offsetX] and [offsetY] (px) say where the new text comes from: +x from the right, +y from below. */
    fun change(next: CharSequence, duration: Long, delay: Long = 0, offsetX: Float = 0f, offsetY: Float = 0f) {
        if (text.toString() == next.toString()) {
            text = next // focus can change the spans without restarting the content transition
            return
        }
        val before = if (duration > 0 && isShown && width > 0 && height > 0 && ValueAnimator.areAnimatorsEnabled()) {
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
                    drawText(canvas) // capture the current blend too, if a fast source change interrupts it
                }
            } catch (_: OutOfMemoryError) { null }
        } else null
        animator?.cancel()
        previous = before
        text = next
        if (before == null) { blend = 1f; invalidate(); return }
        dx = offsetX
        dy = offsetY
        blend = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            startDelay = delay
            interpolator = EASE
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

    /** While selected: a glass chip behind the whole label. It changes width smoothly when the text gets longer or shorter. */
    var chip = false
        set(value) { field = value; invalidate() }
    var chipColor = 0x40FFFFFF
    /** Px at the start the chip leaves out: the space of an arrow that is hidden. */
    var chipInset = 0
        set(value) { field = value; invalidate() }
    private var chipW = 0f
    private var chipMove: ValueAnimator? = null
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        chipMove?.cancel()
        val from = if (chipMove != null) chipW else oldw.toFloat()
        if (oldw == 0 || !isShown || !ValueAnimator.areAnimatorsEnabled()) { chipW = w.toFloat(); return }
        // The parent does not clip this view, so a shrinking chip can draw past the new width until it lands.
        chipMove = ValueAnimator.ofFloat(from, w.toFloat()).apply {
            duration = 260
            interpolator = EASE
            addUpdateListener { chipW = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { chipMove = null } })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (chip) {
            val r = 8 * resources.displayMetrics.density
            chipPaint.color = chipColor
            canvas.drawRoundRect((scrollX + chipInset).toFloat(), scrollY.toFloat(), scrollX + chipW, (scrollY + height).toFloat(), r, r, chipPaint)
        }
        drawText(canvas)
    }

    @android.annotation.SuppressLint("WrongCall") // draws the TextView's own text into a moved, faded layer
    private fun drawText(canvas: Canvas) {
        val old = previous
        if (old == null) { super.onDraw(canvas); return }
        // The old text is gone in the first half; the new one fades in over the whole run.
        fadePaint.alpha = ((1f - (blend * 2f).coerceAtMost(1f)) * 255).toInt()
        if (fadePaint.alpha > 0) canvas.drawBitmap(old, scrollX - dx * blend, scrollY - dy * blend, fadePaint)
        val layer = canvas.saveLayerAlpha(scrollX.toFloat(), scrollY.toFloat(),
            (scrollX + width).toFloat(), (scrollY + height).toFloat(), (blend * 255).toInt())
        canvas.translate(dx * (1f - blend), dy * (1f - blend))
        super.onDraw(canvas)
        canvas.restoreToCount(layer)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        chipMove?.cancel()
        previous = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** Fast start, long soft landing. */
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
