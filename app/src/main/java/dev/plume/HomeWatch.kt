package dev.plume

/** One activity that answers the Home intent, as the system lists it. */
class HomeEntry(val pkg: String, val cls: String, val target: String?, val system: Boolean, val priority: Int)

/**
 * The (package, class) pairs of the TV's own home screens: the activities of system apps that answer the Home
 * intent, except Plume and the fallback home that Android shows while it starts (its priority is below 0). An app
 * the user installed doesn't count, because the user opens it on purpose. An alias is listed under its own name
 * but its window carries the real class, so both count.
 */
fun stockHomes(found: List<HomeEntry>, self: String): Set<Pair<String, String>> =
    found.filter { it.system && it.pkg != self && it.priority >= 0 }
        .flatMap { listOfNotNull(it.pkg to it.cls, it.target?.let { t -> it.pkg to t }) }.toSet()

/**
 * Decides, for each window that opens, whether Plume should cover the TV's own home screen. Fire OS never
 * hands the Home key to an accessibility service, so there this is the only way Home reaches Plume.
 * [isStockHome] says whether a window is one of the system launcher's home screens (see [stockHomes]). Its other
 * windows, such as a notice or a sign-in screen, are not. Kept free of Android classes so it can be tested off the TV.
 */
class HomeWatch(private val isStockHome: (pkg: String, cls: String) -> Boolean) {
    private var settingsMayFollow = false
    private var lastJump = -JUMP_GAP_MS

    /** Null: leave it (and drop any jump still waiting). Otherwise: go home after this many ms. */
    fun opened(pkg: String, cls: String?, now: Long): Long? {
        if (cls == null || !isStockHome(pkg, cls)) {
            // Fire OS shows its home on the way into (and out of) Amazon Settings, then Settings on top.
            settingsMayFollow = pkg == PLUME || pkg.startsWith(AMAZON_SETTINGS)
            return null
        }
        if (now - lastJump < JUMP_GAP_MS) return null // one Home press sends several of these
        lastJump = now
        return if (settingsMayFollow && pkg != GOOGLE_TV) SETTINGS_WAIT_MS else 0
    }

    companion object {
        private const val PLUME = "dev.plume"
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val AMAZON_SETTINGS = "com.amazon.tv.settings"
        private const val JUMP_GAP_MS = 600L
        const val SETTINGS_WAIT_MS = 550L
    }
}
