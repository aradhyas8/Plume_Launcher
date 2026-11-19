package dev.plume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/*
 * Cue's body (res/layout/cue_body.xml) is drawn by these two functions, on Home (inside the glass @id/cue) and over
 * other apps (CueOverlay, on a tint). One drawing path, so an item looks the same in both places. Home adds its own
 * extras after them: the marks, the music's bar and bars, the app's logo.
 */

private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Sizes and padding for [open] (the tile) or the pill at rest. [root] holds cue_body. */
internal fun cueShape(root: ViewGroup, open: Boolean) = with(root) {
    if (open) setPadding(dp(14), dp(14), dp(16), dp(14)) else setPadding(dp(4), dp(4), dp(14), dp(4))
    findViewById<View>(R.id.cueTop).layoutParams.width = if (open) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    findViewById<View>(R.id.cueArt2).layoutParams.apply { width = dp(if (open) 48 else 28); height = width }
    (findViewById<View>(R.id.cueText).layoutParams as LinearLayout.LayoutParams).apply {
        marginStart = dp(if (open) 14 else 9)
        width = if (open) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        weight = if (open) 1f else 0f
    }
    findViewById<TextView>(R.id.cueTitle).apply {
        maxLines = if (open) 2 else 1
        maxWidth = dp(if (open) 400 else 260)
    }
}

/**
 * [item]'s text, pictures and buttons in [root] (cue_body), [open] or as the pill. [ink] colours everything; [inked]
 * gives a text view that ink (with a shadow on glass). [picked] is the selected button, -1 for none; it sits on a
 * soft pill of [pickInk], the others dim.
 */
internal fun cueBind(
    root: ViewGroup, item: CueItem, open: Boolean, ink: Int, inked: (TextView) -> Unit,
    picture: Bitmap?, picture2: Bitmap?, picked: Int, pickInk: Int,
) = with(root) {
    val sports = item.kind == CueKind.SPORTS || item.kind == CueKind.SPORTS_LIVE
    val music = item.kind == CueKind.MEDIA || item.kind == CueKind.MEDIA_PAUSED
    // At rest a song or an alert is one line: the title, then the artist or the detail a little dimmer.
    val oneLine = !open && !item.sub.isNullOrEmpty() && (music || item.kind == CueKind.ALERT || item.kind == CueKind.CALL || item.kind == CueKind.TIMER_DONE)
    findViewById<TextView>(R.id.cueTitle).apply {
        text = if (oneLine) SpannableString("${item.title}  ·  ${item.sub}").apply {
            setSpan(ForegroundColorSpan(ink and 0xFFFFFF or 0xB3000000.toInt()), item.title.length, length, 0)
        } else item.title
        textSize = if (!open) 13f else if (sports) 22f else 16f
        fontFeatureSettings = if (item.tick > 0 || item.kind == CueKind.SPORTS_LIVE) "tnum" else null // numbers that change keep their width
        inked(this)
    }
    findViewById<TextView>(R.id.cueArtist).apply {
        text = item.sub.orEmpty()
        visibility = if (open && !item.sub.isNullOrEmpty()) View.VISIBLE else View.GONE
        textSize = if (open) 13f else 11f
        maxLines = if (open) 2 else 1 // a match's state ("ABT batting · 28.1 ov · Zimbabwe lead by 291 runs") can take two lines
        inked(this)
    }
    contentDescription = item.description ?: listOfNotNull(item.title, item.sub).joinToString(", ")
    findViewById<ImageView>(R.id.cuePhoto).apply {
        visibility = if (open && item.photo != null) View.VISIBLE else View.GONE
        setImageBitmap(item.photo?.takeIf { open })
        clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
    }
    val art = findViewById<ImageView>(R.id.cueArt)
    val size = dp(if (!open) 28 else if (sports) 40 else 80)
    if (art.layoutParams.width != size) art.layoutParams = art.layoutParams.apply { width = size; height = size }
    when {
        picture != null -> { art.clearColorFilter(); art.setImageBitmap(picture); art.background = null; art.setPadding(0, 0, 0, 0) }
        item.icon != 0 -> {
            art.setImageResource(item.icon)
            if (item.mono) art.setColorFilter(ink) else art.clearColorFilter()
            // An icon sits on a soft disc of the ink, the size of the picture it stands in for.
            art.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink and 0xFFFFFF or 0x26000000) }
            val pad = dp(if (!open) 5 else if (sports) 8 else 20)
            art.setPadding(pad, pad, pad, pad)
        }
        else -> art.setImageDrawable(null)
    }
    art.scaleType = if (sports) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
    findViewById<ImageView>(R.id.cueArt2).apply {
        visibility = if (picture2 != null) View.VISIBLE else View.GONE
        setImageBitmap(picture2)
    }
    // Open: the buttons (Home adds the music's bar above them).
    findViewById<View>(R.id.cueMore).visibility = if (open && item.actions.isNotEmpty()) View.VISIBLE else View.GONE
    findViewById<View>(R.id.cueTimes).visibility = View.GONE
    val actions = findViewById<LinearLayout>(R.id.cueActions)
    actions.visibility = if (item.actions.isEmpty() || !open) View.GONE else View.VISIBLE
    val tag = item.key + item.actions.joinToString { "${it.icon}${it.label}" }
    if (actions.tag != tag) {
        actions.removeAllViews()
        actions.tag = tag
        item.actions.forEachIndexed { i, a ->
            val big = music && (a.icon == R.drawable.ic_pause || a.icon == R.drawable.ic_play)
            val v: View = if (a.icon != 0) ImageView(context).apply {
                setImageResource(a.icon)
                val p = dp(if (big) 9 else 8)
                setPadding(p, p, p, p)
            } else TextView(context).apply {
                text = a.label
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
            }
            v.contentDescription = a.description ?: a.label
            val s = dp(if (big) 44 else 36)
            actions.addView(v, LinearLayout.LayoutParams(if (a.icon != 0) s else ViewGroup.LayoutParams.WRAP_CONTENT, s).apply {
                if (i > 0) marginStart = dp(if (music) 18 else 14)
            })
        }
    }
    for (i in 0 until actions.childCount) {
        val v = actions.getChildAt(i)
        val on = i == picked
        (v as? ImageView)?.setColorFilter(ink)
        (v as? TextView)?.setTextColor(ink)
        // The picked button sits on a soft disc (a pill for a word) of the ink, like a lit top bar icon: no ring, no growth.
        v.background = if (on) GradientDrawable().apply {
            if (v is ImageView) shape = GradientDrawable.OVAL else cornerRadius = dp(22).toFloat()
            setColor(pickInk)
        } else null
        v.alpha = if (on || picked < 0) 1f else 0.75f
    }
}

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
