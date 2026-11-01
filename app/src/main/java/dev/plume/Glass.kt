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

/**
 * The blurred wallpaper is this many pixels across: 320x180 is 16:9 and ~230 KB. A 128x72 one scaled 15 times to the
 * screen was mush however little it was blurred, so Clear could not show the wallpaper's shapes.
 */
internal const val GLASS_W = 320
internal const val GLASS_H = 180

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

/**
 * The frost keeps the wallpaper's colours, a little richer (15% more saturation), as glass makes what is behind it
 * look; light mode lifts them a tenth of the way to white. The tint on top ([glassHue]) gives the light or dark side.
 */
internal fun glassTone(px: IntArray, dark: Boolean): IntArray = IntArray(px.size) {
    val p = px[it]
    val c = IntArray(3) { k -> p shr (16 - 8 * k) and 0xFF }
    val grey = (c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000
    for (k in 0..2) {
        c[k] = (grey + (c[k] - grey) * 115 / 100).coerceIn(0, 255)
        if (!dark) c[k] += (255 - c[k]) / 10
    }
    (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
}

/** The mean colour of [px] (opaque ARGB), opaque: what the glass is tinted with. */
internal fun meanColour(px: IntArray): Int {
    if (px.isEmpty()) return GLASS_GREY
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in px) {
        r += p shr 16 and 0xFF
        g += p shr 8 and 0xFF
        b += p and 0xFF
    }
    val n = px.size
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

/** The tint before a wallpaper's colour is known. */
internal const val GLASS_GREY = 0xFF808080.toInt()

/**
 * The glass's tint: the wallpaper's own colour [hue], taken most of the way to black ([dark]) or to white, so text on
 * it reads, at [alpha]. A green photo gives green-black or mint glass, not grey.
 */
internal fun glassHue(hue: Int, dark: Boolean, alpha: Int): Int {
    val c = IntArray(3) { k -> hue shr (16 - 8 * k) and 0xFF }
    for (k in 0..2) c[k] = if (dark) c[k] * 30 / 100 else c[k] + (255 - c[k]) * 75 / 100
    return (alpha shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
}

/**
 * The look of glass. [FROSTED]: a heavy blur that keeps the wallpaper's colours, a tint and a fine grain. [CLEAR]: the
 * wallpaper nearly sharp, magnified a little ([lens]), almost no tint. [tintRest] and [tintLit] are the tiles' tint
 * alphas ([glassHue]); [dim] and [scrim] are the settings overlays; [radius] is the blur's.
 */
internal enum class GlassStyle(
    val key: String,
    val tintRest: Int,
    val tintLit: Int,
    val dim: Float,
    val scrim: Int,
    val radius: Int,
    val lens: Float,
    val grain: Boolean,
) {
    FROSTED("frosted", 0x38, 0x6B, 0.35f, 0x55, 5, 1f, true),
    CLEAR("clear", 0x0D, 0x40, 0.25f, 0x40, 2, 1.1f, false);

    companion object {
        /** The style a stored choice means; null is off. Before the choice, Glass was a switch ([oldOn]). */
        fun of(stored: String?, oldOn: Boolean): GlassStyle? = when (stored) {
            null -> if (oldOn) FROSTED else null
            "off" -> null
            else -> entries.firstOrNull { it.key == stored } ?: FROSTED
        }
    }
}

/** What is behind the home screen, as far as glass cares: a plain colour, a picture, or a video (blurred from its first frame). */
internal enum class Backdrop {
    PLAIN, PICTURE, VIDEO;

    companion object {
        fun of(choice: String) = when {
            choice.startsWith("plain:") -> PLAIN
            choice.startsWith("video:") -> VIDEO
            else -> PICTURE
        }
    }
}

/**
 * Everything glass paints follows from these, so two equal looks need no redraw. [style] is the stored choice (null
 * for off); [hue] is the wallpaper's mean colour, which tints the glass.
 */
internal data class GlassLook(val style: GlassStyle?, val dark: Boolean, val backdrop: Backdrop, val hue: Int = GLASS_GREY) {
    /**
     * The style that shows: none on a plain background, which has nothing behind it. Clear needs a still picture: a
     * video moves, so the sharp still under a tile would not match it; there it is Frosted, whose heavy blur of the
     * video's first frame is soft enough not to show that it does not move.
     */
    val active: GlassStyle? get() = when {
        backdrop == Backdrop.PLAIN -> null
        backdrop == Backdrop.VIDEO && style == GlassStyle.CLEAR -> GlassStyle.FROSTED
        else -> style
    }

    val lens: Float get() = active?.lens ?: 1f

    /** A tile's tint, at rest or [lit]: 0 with glass off. */
    fun tile(lit: Boolean): Int = active?.let { glassHue(hue, dark, if (lit) it.tintLit else it.tintRest) } ?: 0

    /** The panel's and menus' tint: as strong in both styles, as they carry text. */
    fun wash(): Int = glassHue(hue, dark, 0xA6)
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
 * The wallpaper as it shows on the screen, as [GLASS_W]x[GLASS_H] pixels, not blurred yet. [ground] fills what the
 * picture does not cover, and [fades] paints the top and bottom fades over it, as the wallpaper view shows them.
 * Done once when a wallpaper loads, off the UI thread; kept (36 KB), so a style change only blurs it again.
 */
internal fun glassScreen(wallpaper: Bitmap, ground: Int, fades: ((Canvas, Float, Float) -> Unit)? = null): IntArray {
    // In two steps, or a 1920 px picture shrunk to 120 px in one would skip most of its pixels.
    val mid = Bitmap.createScaledBitmap(wallpaper, maxOf(1, wallpaper.width / 4), maxOf(1, wallpaper.height / 4), true)
    val screen = Bitmap.createBitmap(GLASS_W, GLASS_H, Bitmap.Config.ARGB_8888)
    val c = Canvas(screen)
    c.drawColor(ground or (0xFF shl 24))
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val at = glassCrop(mid.width, mid.height)
    c.drawBitmap(mid, Rect(0, 0, mid.width, mid.height), RectF(at[0], at[1], at[2], at[3]), paint)
    fades?.invoke(c, GLASS_W.toFloat(), GLASS_H.toFloat())
    if (mid !== wallpaper) mid.recycle()
    val px = IntArray(GLASS_W * GLASS_H)
    screen.getPixels(px, 0, GLASS_W, 0, 0, GLASS_W, GLASS_H)
    screen.recycle()
    return px
}

/**
 * The wallpaper as the screen shows it, sharp, for Clear's lens: three times the frost's size (960x540, 2 MB), kept
 * only while Clear is the style. Done once when a wallpaper loads, off the UI thread.
 */
internal fun glassSharp(wallpaper: Bitmap, ground: Int, fades: ((Canvas, Float, Float) -> Unit)? = null): Bitmap {
    val k = 3
    val sharp = Bitmap.createBitmap(GLASS_W * k, GLASS_H * k, Bitmap.Config.ARGB_8888)
    val c = Canvas(sharp)
    c.drawColor(ground or (0xFF shl 24))
    val at = glassCrop(wallpaper.width, wallpaper.height)
    c.drawBitmap(wallpaper, null, RectF(at[0] * k, at[1] * k, at[2] * k, at[3] * k), Paint(Paint.FILTER_BITMAP_FLAG))
    fades?.invoke(c, sharp.width.toFloat(), sharp.height.toFloat())
    // In graphics memory once, so the lens samples it each frame without an upload, and the process holds no copy.
    return try {
        sharp.copy(Bitmap.Config.HARDWARE, false)?.also { sharp.recycle() } ?: sharp
    } catch (e: Exception) {
        sharp
    }
}

/**
 * Clear's lens on Android 13+, one shader per tile (the way Android's liquid glass libraries do it): the middle
 * magnifies [LENS_MAG] times and toward the rim the picture runs outward, up to [LENS_BEND] beyond the tile's edge,
 * so the rim shows what is just outside, bent in, as the thick edge of real glass does. [box] is the tile.
 */
internal const val LENS_AGSL = """
uniform shader picture;
uniform float4 box;
uniform float mag;
uniform float bend;
half4 main(float2 p) {
    float2 c = (box.xy + box.zw) * 0.5;
    float2 u = (p - c) / ((box.zw - box.xy) * 0.5);
    float e = max(abs(u.x), abs(u.y));
    return picture.eval(c + (p - c) * (1.0 + bend * e * e * e * e) / mag);
}"""
internal const val LENS_MAG = 1.25f
internal const val LENS_BEND = 0.5f

/** The frost for [glassScreen] pixels: about a millisecond, so it is redone on the UI thread when the look changes. */
internal fun glassBlur(screen: IntArray, radius: Int, dark: Boolean) = glassTone(boxBlur(screen, GLASS_W, GLASS_H, radius, passes = 3), dark)

/**
 * The fine grain of frosted glass: a 64 px tile of soft light and dark specks, repeated. Made once; about 6% strong,
 * so it shows as texture, not as noise.
 */
internal fun grainShader(): BitmapShader {
    val n = 64
    val r = java.util.Random(7) // the same grain every time
    val px = IntArray(n * n) { if (r.nextBoolean()) (r.nextInt(0x10) shl 24) or 0xFFFFFF else r.nextInt(0x10) shl 24 }
    return BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
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
    private val onePx = context.resources.displayMetrics.density
    private val visible = Rect()
    private val row = Rect()
    private val rect = RectF()
    private val at = IntArray(2)
    private val scale = FloatArray(2)
    private var shader: BitmapShader? = null
    var blur: Bitmap? = null // the blurred wallpaper, for the surfaces that paint it themselves (GlassBox)
        private set
    var lens = 1f // see GlassStyle.lens
        set(v) {
            if (field != v) invalidate()
            field = v
        }
    // Clear's sharp picture: through the lens shader on Android 13+; before that magnified evenly by [lens], which
    // is what Android 12 and older can do in one draw. ponytail: no bent rim below 13; a mesh draw doubled the jank.
    private var sharpShader: BitmapShader? = null
    private var sharpSize = 1f to 1f
    private val sharpBase = Matrix()
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lens13: android.graphics.RuntimeShader? =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(LENS_AGSL).apply {
            setFloatUniform("mag", LENS_MAG)
            setFloatUniform("bend", LENS_BEND)
        } else null

    /** The sharp wallpaper for Clear ([glassSharp]), or null when Clear is not shown. */
    fun setSharp(b: Bitmap?) {
        sharpShader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        if (b != null) sharpSize = b.width.toFloat() to b.height.toFloat()
        bindSharp()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = bindSharp()

    /** Maps the sharp picture onto this view (the whole screen) and hands it to the lens. */
    private fun bindSharp() {
        val sh = sharpShader
        sharpBase.setScale(width / sharpSize.first, height / sharpSize.second)
        sh?.setLocalMatrix(sharpBase)
        val r = lens13
        if (android.os.Build.VERSION.SDK_INT >= 33 && r != null && sh != null) r.setInputShader("picture", sh)
        lensPaint.shader = if (sh == null) null else r ?: sh
        invalidate()
    }

    var grain = false // see GlassStyle.grain
        set(v) {
            if (field != v) invalidate()
            field = v
        }
    // What makes a pane read as glass without an outline: light caught on its top half, a soft shadow under it, and
    // the fine grain of frosted glass. All drawn with fixed shaders.
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x4DFFFFFF, 0x12FFFFFF, 0), floatArrayOf(0f, 0.38f, 0.62f), Shader.TileMode.CLAMP)
    }
    private val grainPaint = Paint().apply { shader = grainShader() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val unit = Matrix()
    private val pane = android.graphics.Path()
    private var until = 0L // keeps redrawing for a moment after a scroll or a focus change, which move the tiles

    fun add(v: View, radius: Float) {
        tiles.removeAll { it.view.get() == null }
        if (tiles.none { it.view.get() === v }) tiles += Tile(WeakReference(v), radius)
        invalidate()
    }

    fun remove(v: View) {
        tiles.removeAll { it.view.get().let { t -> t == null || t === v } }
        invalidate()
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
        if (tiles.isEmpty()) return
        val s = shader
        paint.shader = s
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
            val rx = t.radius * sx
            val ry = t.radius * sy
            val lit = v.isFocused
            shadow(c, rx, ry, alpha, lit, scroller, ox)
            c.save()
            c.clipRect(visible.left - ox.toFloat(), visible.top - oy.toFloat(), visible.right - ox.toFloat(), visible.bottom - oy.toFloat())
            val sh = sharpShader
            if (sh != null) {
                val r = lens13
                if (android.os.Build.VERSION.SDK_INT >= 33 && r != null) r.setFloatUniform("box", rect.left, rect.top, rect.right, rect.bottom)
                else {
                    matrix.set(sharpBase)
                    matrix.postScale(lens, lens, rect.centerX(), rect.centerY())
                    sh.setLocalMatrix(matrix)
                }
                lensPaint.alpha = alpha
                c.drawRoundRect(rect, rx, ry, lensPaint)
            } else if (s != null) {
                matrix.setScale(width.toFloat() / GLASS_W, height.toFloat() / GLASS_H)
                matrix.postScale(lens, lens, rect.centerX(), rect.centerY())
                s.setLocalMatrix(matrix)
                paint.alpha = alpha
                c.drawRoundRect(rect, rx, ry, paint)
                if (grain) {
                    grainPaint.alpha = alpha
                    c.drawRoundRect(rect, rx, ry, grainPaint)
                }
            }
            light(c, rx, ry, alpha, lit)
            c.restore()
        }
        if (SystemClock.uptimeMillis() < until) postInvalidateOnAnimation()
    }

    /** A soft shadow under the pane at [rect], outside it only: inside, it would darken the glass. */
    private fun shadow(c: Canvas, rx: Float, ry: Float, alpha: Int, lit: Boolean, scroller: View?, ox: Int) {
        pane.reset()
        pane.addRoundRect(rect, rx, ry, android.graphics.Path.Direction.CW)
        c.save()
        c.clipOutPath(pane)
        // In a row, the shadow stops at the row's ends as the tiles do (see the stub note in onDraw).
        if (scroller != null) c.clipRect(row.left - ox.toFloat(), 0f, row.right - ox.toFloat(), height.toFloat())
        shadowPaint.setShadowLayer((if (lit) 22f else 14f) * onePx, 0f, (if (lit) 8f else 5f) * onePx, (if (lit) 0x59 else 0x38) * alpha / 255 shl 24)
        c.drawRoundRect(rect, rx, ry, shadowPaint)
        c.restore()
    }

    /** The light on the pane: the sheen on its top half, brighter on the focused tile. */
    private fun light(c: Canvas, rx: Float, ry: Float, alpha: Int, lit: Boolean) {
        unit.setScale(1f, rect.height())
        unit.postTranslate(0f, rect.top)
        sheenPaint.shader.setLocalMatrix(unit)
        sheenPaint.alpha = if (lit) alpha else alpha * 3 / 4
        c.drawRoundRect(rect, rx, ry, sheenPaint)
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
 * tint (dark or white) and a little light on the top edge, with no outline. The blur is the one the tiles use, so
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
    private val grainPaint = Paint().apply { shader = grainShader() }
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
            c.drawRoundRect(rect, round, round, grainPaint) // a surface is always Frosted (see GlassLook.wash)
        }
        // Without a blur the tint is solid: see-through, the tiles and posters behind would show under the text.
        tintPaint.color = if (shader == null) wash or (0xFF shl 24) else wash
        c.drawRoundRect(rect, round, round, tintPaint)
        if (highlight != 0) {
            tintPaint.shader = gloss
            c.drawRoundRect(rect, round, round, tintPaint)
            tintPaint.shader = null
        }
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
