package dev.plume

/**
 * Which icons the top bar shows, and in what order. Plain lists of keys, so the rules need no Android to be tested:
 * the home screen turns the keys into its views.
 */
internal object TopBar {
    /** The saved list ("wifi,apps,plume") without unknown or repeated keys, or [default]. [required] is always on the bar. */
    fun parse(saved: String?, known: List<String>, default: List<String>, required: String): List<String> {
        val order = saved?.split(',')?.filter { it in known }?.distinct() ?: default
        return if (required in order) order else order + required
    }

    /** [key] one place to [dir] (-1 or 1) among the icons that [shown]; the others keep their places in [order]. */
    fun slide(order: List<String>, shown: List<String>, key: String, dir: Int): List<String> {
        val to = shown.indexOf(key) + dir
        if (key !in shown || to !in shown.indices) return order
        val other = shown[to]
        return order.map { if (it == key) other else if (it == other) key else it }
    }

    /** [key] right after [after], or at the end when [after] is not on the bar. */
    fun addAfter(order: List<String>, key: String, after: String): List<String> {
        val rest = order - key
        val at = rest.indexOf(after)
        return if (at < 0) rest + key else rest.take(at + 1) + key + rest.drop(at + 1)
    }
}
