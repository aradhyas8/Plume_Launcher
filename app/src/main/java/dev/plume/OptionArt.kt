package dev.plume

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A small picture of a settings choice, drawn in the panel's [ink]: the apps row at each Size ("size", 8, 6 or 4 tiles
 * across), at each Position ("position", left, centre or right), or a tile over a wallpaper for each Glass ("glass",
 * off, frosted or clear). The picked one ([on]) gets a stronger frame, stronger still while its row has [focused].
 */
internal class OptionArt(private val kind: String, private val index: Int, private val density: Float) : Drawable() {
    var ink = 0xFF000000.toInt()
    var on = false
    var focused = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun ink(a: Float) = (ink and 0xFFFFFF) or ((a * 255).toInt() shl 24)

    override fun draw(c: Canvas) {
        val b = RectF(bounds).apply { inset(density, density) }
        val r = b.height() * 0.16f
        paint.style = Paint.Style.FILL
        paint.color = ink(if (on) .12f else .06f)
        c.drawRoundRect(b, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (on) 1.5f else 1f
        paint.color = ink(if (on) (if (focused) .9f else .5f) else .2f)
        c.drawRoundRect(b, r, r, paint)
        paint.style = Paint.Style.FILL
        val tile = ink(if (on) .85f else .45f)
        when (kind) {
            "size" -> {
                val n = intArrayOf(8, 6, 4)[index]
                val m = b.width() * 0.1f
                val gap = b.width() * 0.025f
                val w = (b.width() - 2 * m - (n - 1) * gap) / n
                val h = minOf(w * 0.75f, b.height() * 0.5f)
                val top = b.centerY() - h / 2
                paint.color = tile
                for (i in 0 until n) c.drawRoundRect(b.left + m + i * (w + gap), top, b.left + m + i * (w + gap) + w, top + h, h * .18f, h * .18f, paint)
            }
            "position" -> {
                val w = b.width() * 0.15f
                val gap = b.width() * 0.03f
                val group = 3 * w + 2 * gap
                val m = b.width() * 0.1f
                val left = when (index) { 0 -> b.left + m; 1 -> b.centerX() - group / 2; else -> b.right - m - group }
                val h = w * 0.75f
                val top = b.centerY() - h / 2
                paint.color = tile
                for (i in 0 until 3) c.drawRoundRect(left + i * (w + gap), top, left + i * (w + gap) + w, top + h, h * .18f, h * .18f, paint)
            }
            "glass" -> {
                // The wallpaper: three slanted hills behind the tile.
                paint.color = ink(.3f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 1.5f
                for (i in 0 until 3) {
                    val x = b.left + b.width() * (0.15f + i * 0.28f)
                    c.drawLine(x, b.bottom - b.height() * .2f, x + b.width() * .18f, b.top + b.height() * .25f, paint)
                }
                val t = RectF(b.centerX() - b.width() * .22f, b.centerY() - b.height() * .28f, b.centerX() + b.width() * .22f, b.centerY() + b.height() * .28f)
                val tr = t.height() * .2f
                when (index) {
                    // Off hides the hills under a solid tile; Frosted lets them show faintly; Clear is only an outline.
                    0 -> { paint.style = Paint.Style.FILL; paint.color = (ink and 0xFFFFFF) or (if (on) 0xFF shl 24 else 0x99 shl 24); c.drawRoundRect(t, tr, tr, paint) }
                    1 -> { paint.style = Paint.Style.FILL; paint.color = ink(if (on) .3f else .16f); c.drawRoundRect(t, tr, tr, paint)
                        paint.style = Paint.Style.STROKE; paint.color = ink(if (on) .6f else .35f); c.drawRoundRect(t, tr, tr, paint) }
                    else -> { paint.style = Paint.Style.STROKE; paint.color = ink(if (on) .85f else .45f); c.drawRoundRect(t, tr, tr, paint) }
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
