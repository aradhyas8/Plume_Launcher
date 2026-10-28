package dev.plume

/**
 * The shows row, when it is split by source: which sources there are, in what order, and where Left and Right (By title)
 * or Down and Up (Per channel) lead. Plain values only, so the unit tests need no Android framework.
 */
internal object ShowRows {
    const val MIXED = "mixed" // every source in one row
    const val BY_TITLE = "title" // one row at a time, switched with its title
    const val PER_CHANNEL = "stack" // one row for each source

    private val MODES = setOf(MIXED, BY_TITLE, PER_CHANNEL)

    fun mode(saved: String?) = saved?.takeIf { it in MODES } ?: MIXED

    /** What a source is, for the order: Android TV's Watch Next, a channel named "Continue Watching", or any other channel. */
    enum class Kind { WATCH_NEXT, CONTINUE, OTHER }

    class Source(val key: String, val title: String, val kind: Kind)

    /** Watch Next first, then the Continue Watching channels, then the others. Each group keeps the order it came in. */
    fun ordered(sources: List<Source>) = sources.sortedBy { it.kind.ordinal }

    /** The source on show: the saved one if it is still there, else the first. Null with no source. */
    fun current(keys: List<String>, saved: String?) = keys.firstOrNull { it == saved } ?: keys.firstOrNull()

    /** By title: the source [d] places to the right (+1) or left (-1) of [current], round the list. Null with fewer than two. */
    fun around(keys: List<String>, current: String?, d: Int): String? {
        val at = keys.indexOf(current)
        if (at < 0 || keys.size < 2) return null
        return keys[((at + d) % keys.size + keys.size) % keys.size]
    }

    /** Per channel: the source [d] rows down (+1) or up (-1) from [current]. Null at either end. */
    fun beside(keys: List<String>, current: String?, d: Int): String? {
        val at = keys.indexOf(current)
        return if (at < 0) null else keys.getOrNull(at + d)
    }
}
