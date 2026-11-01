package dev.plume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.lang.ref.WeakReference

/** The blurred wallpaper is this many pixels across: 128x72 is 16:9 and ~36 KB. */
internal const val GLASS_W = 128
internal const val GLASS_H = 72

/** A blur of [px] (opaque ARGB, [w]x[h]): [passes] box blurs of radius [radius], horizontal then vertical each time. */
internal fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 2): IntArray {
    var src = px.copyOf()
    var dst = IntArray(px.size)
    repeat(passes) {
        for (horizontal in booleanArrayOf(true, false)) {
            val n = if (horizontal) w else h // pixels along a line
            val lines = if (horizontal) h else w
            val step = if (horizontal) 1 else w // from one pixel of a line to the next
            val lineStep = if (horizontal) w else 1 // from one line to the next
            for (l in 0 until lines) {
                val base = l * lineStep
                for (i in 0 until n) {
                    var r = 0
                    var g = 0
                    var b = 0
                    for (k in -radius..radius) {
                        val p = src[base + (i + k).coerceIn(0, n - 1) * step]
                        r += p shr 16 and 0xFF
                        g += p shr 8 and 0xFF
                        b += p and 0xFF
                    }
                    val c = 2 * radius + 1
                    dst[base + i * step] = (0xFF shl 24) or (r / c shl 16) or (g / c shl 8) or (b / c)
                }
            }
            val t = src
            src = dst
            dst = t
        }
    }
    return src
}

/** The glass keeps the wallpaper's colours: dark mode as they are, light mode a fifth of the way to white. */
internal fun glassTone(px: IntArray, dark: Boolean): IntArray = IntArray(px.size) {
    val p = px[it]
    val c = IntArray(3) { k -> p shr (16 - 8 * k) and 0xFF }
    for (k in 0..2) c[k] = if (dark) c[k] else c[k] + (255 - c[k]) / 5
    (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
}

/**
 * The look of glass. [FROSTED] is the original; [CLEAR] uses lighter tints so more of the wallpaper shows through.
 * Both share the one blur, so a style change needs no decode, no second bitmap and no extra memory.
 * [tileRest] and [tileLit] are the light-mode card colours, [darkTileRest] and [darkTileLit] the dark-mode alphas;
 * [dim] and [scrim] are the settings overlays.
 */
internal enum class GlassStyle(
    val key: String,
    val washDark: Int,
    val washLight: Int,
    val tileRest: IntArray,
    val tileLit: IntArray,
    val darkTileRest: Int,
    val darkTileLit: Int,
    val dim: Float,
    val scrim: Int,
) {
    FROSTED("frosted", 0x8C000000.toInt(), 0xA8FFFFFF.toInt(),
        intArrayOf(0x6EE2E5E9, 0x6ED8DCE1), intArrayOf(0xBEFFFFFF.toInt(), 0xBEFFFFFF.toInt()), 0x40, 0x66, 0.35f, 0x55),
    CLEAR("clear", 0x4D000000.toInt(), 0x66FFFFFF.toInt(),
        intArrayOf(0x3DE2E5E9, 0x3DD8DCE1), intArrayOf(0x8CFFFFFF.toInt(), 0x8CFFFFFF.toInt()), 0x26, 0x4D, 0.25f, 0x40);

    companion object {
        /** The style a stored choice means; null is off. Before the choice, Glass was a switch ([oldOn]). */
        fun of(stored: String?, oldOn: Boolean): GlassStyle? = when (stored) {
            null -> if (oldOn) FROSTED else null
            "off" -> null
            else -> entries.firstOrNull { it.key == stored } ?: FROSTED
        }
    }
}

/**
 * Where a [w]x[h] picture lands on the [GLASS_W]x[GLASS_H] screen, as the wallpaper view's centerCrop puts it: scaled by
 * the larger of the two ratios and centred. Left, top, right, bottom; a photo that is not 16:9 spills over the edges.
 */
internal fun glassCrop(w: Int, h: Int): FloatArray {
    val scale = maxOf(GLASS_W.toFloat() / w, GLASS_H.toFloat() / h)
    val dw = w * scale
    val dh = h * scale
    return floatArrayOf((GLASS_W - dw) / 2, (GLASS_H - dh) / 2, (GLASS_W + dw) / 2, (GLASS_H + dh) / 2)
}

/**
 * The wallpaper as it shows on the screen, blurred, as [GLASS_W]x[GLASS_H] pixels. [ground] fills what the picture
 * does not cover, and [fades] paints the top and bottom fades over it, as the wallpaper view shows them. Done once
 * when a wallpaper loads, off the UI thread.
 */
internal fun glassPixels(wallpaper: Bitmap, ground: Int, dark: Boolean, fades: ((Canvas, Float, Float) -> Unit)? = null): IntArray {
    // In two steps, or a 1920 px picture shrunk to 120 px in one would skip most of its pixels.
    val mid = Bitmap.createScaledBitmap(wallpaper, maxOf(1, wallpaper.width / 4), maxOf(1, wallpaper.height / 4), true)
    val screen = Bitmap.createBitmap(GLASS_W, GLASS_H, Bitmap.Config.ARGB_8888)
    val c = Canvas(screen)
    c.drawColor(ground or (0xFF shl 24))
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val at = glassCrop(mid.width, mid.height)
    c.drawBitmap(mid, Rect(0, 0, mid.width, mid.height), RectF(at[0], at[1], at[2], at[3]), paint)
    fades?.invoke(c, GLASS_W.toFloat(), GLASS_H.toFloat())
    mid.recycle()
    val px = IntArray(GLASS_W * GLASS_H)
    screen.getPixels(px, 0, GLASS_W, 0, 0, GLASS_W, GLASS_H)
    screen.recycle()
    return glassTone(boxBlur(px, GLASS_W, GLASS_H, 2), dark)
}

/**
 * Sits between the wallpaper and the home screen and paints the blurred wallpaper under each glass tile, at the
 * tile's own place, so the tile's see-through colour sits over frosted glass. One shader and a few rounded
 * rectangles: no library, no live blur, nothing for the GPU to redo on each frame.
 */
internal class GlassLayer(context: Context) : View(context) {
    private class Tile(val view: WeakReference<View>, val radius: Float) {
        var scroller: WeakReference<View>? = null
        var scrollerFound = false // resolved once, when the tile is attached: its row is there by then
    }

    private val tiles = ArrayList<Tile>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val visible = Rect()
    private val row = Rect()
    private val rect = RectF()
    private val at = IntArray(2)
    private val scale = FloatArray(2)
    private var shader: BitmapShader? = null
    var blur: Bitmap? = null // the blurred wallpaper, for the surfaces that paint it themselves (GlassBox)
        private set
    /** A flat colour under each tile when there is no blur (a video wallpaper): 0 for none. */
    var tint = 0
        set(v) {
            field = v
            invalidate()
        }
    private var until = 0L // keeps redrawing for a moment after a scroll or a focus change, which move the tiles

    fun add(v: View, radius: Float) {
        tiles.removeAll { it.view.get() == null }
        if (tiles.none { it.view.get() === v }) tiles += Tile(WeakReference(v), radius)
    }

    fun clearTiles() {
        tiles.clear()
        invalidate()
    }

    /** The blurred wallpaper, or null for none (a plain background has nothing to blur). */
    fun setBlur(px: IntArray?) {
        blur = px?.let { Bitmap.createBitmap(it, GLASS_W, GLASS_H, Bitmap.Config.ARGB_8888) }
        shader = blur?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    // A layout pass can move the tiles with no scroll and no focus change (the cards load and push the row up):
    // draw again then, or the layer keeps frosting the places the tiles have left.
    private val moved = android.view.ViewTreeObserver.OnGlobalLayoutListener { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(moved)
        super.onDetachedFromWindow()
    }

    /** Tiles move on a scroll or a focus lift: draw again for the next [ms]. */
    fun kick(ms: Long) {
        until = maxOf(until, SystemClock.uptimeMillis() + ms)
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val s = shader
        if (s == null && tint == 0) return
        if (s != null) {
            matrix.setScale(width.toFloat() / GLASS_W, height.toFloat() / GLASS_H)
            s.setLocalMatrix(matrix)
        } else paint.color = tint
        paint.shader = s
        val full = if (s != null) 255 else tint ushr 24 // a tint keeps its own alpha, then fades with the tile
        getLocationInWindow(at)
        val ox = at[0]
        val oy = at[1]
        for (t in tiles) {
            val v = t.view.get() ?: continue
            val alpha = alphaOf(v)
            if (alpha == 0 || !v.isShown || !v.getGlobalVisibleRect(visible)) continue
            // A row scroller doesn't clip its children (room for the focus lift), so the visible rect runs past
            // its edge: cut it there, or the next tile's frost shows as a grey stub at the screen edge.
            if (!t.scrollerFound) {
                t.scrollerFound = true
                t.scroller = (generateSequence(v.parent) { it.parent }.firstOrNull { it is SnapScrollView } as? View)
                    ?.let { WeakReference(it) }
            }
            val scroller = t.scroller?.get()
            if (scroller != null && (!scroller.getGlobalVisibleRect(row) || !visible.intersect(row))) continue
            // A row also scales (it settles after an app closes): the frost must grow with the tile, or it slips.
            parentScale(v)
            val sx = v.scaleX * scale[0]
            val sy = v.scaleY * scale[1]
            v.getLocationInWindow(at)
            // Where the tile is drawn, lift and row scale included; what a scroll view cuts off stays cut off.
            rect.set(at[0] - ox.toFloat(), at[1] - oy.toFloat(), at[0] - ox + v.width * sx, at[1] - oy + v.height * sy)
            paint.alpha = alpha * full / 255
            c.save()
            c.clipRect(visible.left - ox.toFloat(), visible.top - oy.toFloat(), visible.right - ox.toFloat(), visible.bottom - oy.toFloat())
            c.drawRoundRect(rect, t.radius * sx, t.radius * sy, paint)
            c.restore()
        }
        if (SystemClock.uptimeMillis() < until) postInvalidateOnAnimation()
    }

    /** The tile's alpha multiplied by every parent's, so a fading row takes its frost with it. */
    private fun alphaOf(v: View): Int {
        var a = 1f
        var c: View? = v
        while (c != null) {
            a *= c.alpha
            if (a <= 0f) return 0
            c = c.parent as? View
        }
        return (a * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** The scale every parent puts on the tile; [scale] gets x, then y. */
    private fun parentScale(v: View) {
        var x = 1f
        var y = 1f
        var p = v.parent
        while (p is View) {
            x *= p.scaleX
            y *= p.scaleY
            p = p.parent
        }
        scale[0] = x
        scale[1] = y
    }
}

/** Where a view at ([x], [y]) on a [screenW]x[screenH] screen finds itself in the blurred wallpaper: scale x, scale y, shift x, shift y. */
internal fun glassMapping(screenW: Int, screenH: Int, x: Int, y: Int) =
    floatArrayOf(screenW.toFloat() / GLASS_W, screenH.toFloat() / GLASS_H, -x.toFloat(), -y.toFloat())

/**
 * The look of a frosted surface: the blurred wallpaper that is under the [host] view, at the host's own place, then a
 * tint (dark or white), a hairline rim and a little light on the top edge. The blur is the one the tiles use, so
 * nothing is blurred again, and what is behind the surface (tiles, cards) does not show through the text.
 * [round] is the corner radius, [flatRight]: the right corners run off the screen (a panel along the right edge).
 * [ref] is the view whose wallpaper the blur shows (the activity root), so a surface in another window (a menu, a
 * dialog) still finds its place in the same blur. Until there is a blur (a card opened at start), the tint is solid.
 */
internal class GlassDrawable(private val host: View) : android.graphics.drawable.Drawable() {
    // Dithered: a dark blur and a faint gradient have only a few levels, which show as bands on a big surface. A menu
    // or a panel is drawn once and then sits still, so the dither costs nothing that shows (the tiles of the row differ).
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val matrix = Matrix()
    private val rect = RectF()
    private val at = IntArray(2)
    private val origin = IntArray(2)
    private var shader: BitmapShader? = null
    private var gloss: android.graphics.LinearGradient? = null
    var round = 0f
    var flatRight = false
    var ref: View? = null // the view the blur was made for; its size and place fix the mapping
    var wash = 0
    var rim = 0
    var highlight = 0 // white at the top edge, fading out: the light of the glass
        set(v) {
            field = v
            updateGloss()
        }

    /** The blur to paint, or null for a plain solid [wash] (a plain background has nothing to blur). */
    fun setBlur(b: Bitmap?) {
        shader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidateSelf()
        host.invalidate()
    }

    override fun onBoundsChange(r: Rect) {
        updateGloss()
    }

    private fun updateGloss() {
        val top = bounds.top.toFloat()
        val h = minOf(bounds.height().toFloat(), 120f * host.resources.displayMetrics.density)
        gloss = if (h > 0f) android.graphics.LinearGradient(0f, top, 0f, top + h, highlight, 0, Shader.TileMode.CLAMP) else null
    }

    override fun draw(c: Canvas) {
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        if (flatRight) rect.right += round
        shader?.let { s ->
            host.getLocationOnScreen(at)
            val x = (at[0] + bounds.left).toFloat()
            val y = (at[1] + bounds.top).toFloat()
            val r = ref
            if (r != null && r.isAttachedToWindow && r.width > 0 && r.height > 0) {
                // The blur covers the activity's wallpaper, not the display: a menu or a panel in another window
                // finds its place in that same picture, and a window inset by system bars still lines up.
                r.getLocationOnScreen(origin)
                val sx = r.width.toFloat() / GLASS_W
                val sy = r.height.toFloat() / GLASS_H
                matrix.setScale(sx, sy)
                matrix.postTranslate(origin[0] - x, origin[1] - y)
            } else {
                val m = glassMapping(host.resources.displayMetrics.widthPixels, host.resources.displayMetrics.heightPixels, x.toInt(), y.toInt())
                matrix.setScale(m[0], m[1])
                matrix.postTranslate(m[2], m[3])
            }
            s.setLocalMatrix(matrix)
            paint.shader = s
            c.drawRoundRect(rect, round, round, paint)
        }
        tintPaint.color = if (shader == null) wash or (0xFF shl 24) else wash
        c.drawRoundRect(rect, round, round, tintPaint)
        if (highlight != 0 && shader != null) {
            tintPaint.shader = gloss
            c.drawRoundRect(rect, round, round, tintPaint)
            tintPaint.shader = null
        }
        rimPaint.color = rim
        rect.inset(0.5f, 0.5f)
        c.drawRoundRect(rect, round, round, rimPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** A menu, a card or a panel in frosted glass (see [GlassDrawable]), with the children on top. */
internal class GlassBox @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : android.widget.LinearLayout(context, attrs) {
    val glass = GlassDrawable(this)

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(c: Canvas) {
        // Glass off: the view still draws for its plain background, and an unstyled glass would paint solid black on it.
        if (willNotDraw()) return
        glass.setBounds(0, 0, width, height)
        glass.draw(c)
    }
}
