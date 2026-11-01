package dev.plume

/**
 * The strength of the fades that Plume draws over a wallpaper, 0 to 255. Both are eased, so the picture dissolves
 * into the ground instead of stopping at a line. Pure numbers, so the unit tests need no Android framework.
 */
internal object Fades {
    private const val TOP_PEAK = 0.55f // of the ground colour at the very top, for the clock and the top-bar icons
    private const val TOP_END = 0.28f // where the top veil has gone
    private const val BOTTOM_START = 0.40f // where the picture starts to give way to the ground
    private const val BOTTOM_END = 0.86f // where the ground is solid, under the Shows row and its labels

    /** Alpha of the top veil at row [y] of a picture [h] rows tall. */
    fun topAlpha(y: Int, h: Int): Int {
        if (h <= 0) return 0
        val t = y.toFloat() / (h * TOP_END)
        if (t >= 1f) return 0
        return ((1f - ease(t)) * TOP_PEAK * 255f).toInt()
    }

    /** Alpha of the bottom fade at row [y] of a picture [h] rows tall. */
    fun bottomAlpha(y: Int, h: Int): Int {
        if (h <= 0) return 0
        val t = (y.toFloat() / h - BOTTOM_START) / (BOTTOM_END - BOTTOM_START)
        if (t <= 0f) return 0
        if (t >= 1f) return 255
        return (ease(t) * 255f).toInt()
    }

    /** Smoothstep: flat at both ends, so neither fade begins or ends on a visible edge. */
    private fun ease(t: Float) = t * t * (3f - 2f * t)
}
