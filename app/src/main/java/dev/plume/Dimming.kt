package dev.plume

/** How far a wallpaper picture is darkened in dark mode, from its brightness. Pure numbers, so the unit tests need no Android framework. */
internal object Dimming {
    private const val TARGET = 0.30f // the mean brightness a picture is brought to in dark mode
    private const val MOST = 0.6f // the most black Auto lays over a picture

    /** Mean brightness, 0 to 1, of ARGB [pixels]. */
    fun meanLuma(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0.0
        for (p in pixels) sum += 0.2126 * (p shr 16 and 255) + 0.7152 * (p shr 8 and 255) + 0.0722 * (p and 255)
        return (sum / pixels.size / 255.0).toFloat()
    }

    /** The alpha (0 to 255) of black that brings a picture of mean brightness [mean] down to [TARGET]: none for a picture that is darker already. */
    fun autoDim(mean: Float): Int = ((1f - TARGET / mean.coerceAtLeast(0.01f)).coerceIn(0f, MOST) * 255).toInt()

    private const val FLOOR = 0.22f // a picture darker than this is lifted towards it
    private const val LIFT_MAX = 0.2f // the most grey the lift adds to the shadows

    /** The grey (0 to 255) screened over a very dark picture of mean brightness [mean], so that its shadows are not lost: none from [FLOOR] up. */
    fun autoLift(mean: Float): Int = (((FLOOR - mean) / (1f - mean)).coerceIn(0f, LIFT_MAX) * 255).toInt()
}
