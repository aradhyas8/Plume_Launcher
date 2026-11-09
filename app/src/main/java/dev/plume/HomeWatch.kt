package dev.plume

/** One activity that answers the Home intent, as the system lists it. */
class HomeEntry(val pkg: String, val cls: String, val target: String?)

/**
 * The classes that the Google TV and Android TV launchers declare for Home: their home screens. Each launcher draws
 * other windows too (notices, sign-in, the dashboard). An alias is listed under its own name but its window
 * carries the real class, so both count.
 */
fun stockHomes(found: List<HomeEntry>): Map<String, Set<String>> =
    found.filter { it.pkg == HomeWatch.GOOGLE_TV || it.pkg == HomeWatch.ANDROID_TV }
        .groupBy { it.pkg }.mapValues { (_, entries) -> entries.flatMap { listOfNotNull(it.cls, it.target) }.toSet() }

/**
 * Decides, for each window that opens, whether Plume should cover the TV's own home screen. Fire OS never
 * hands the Home key to an accessibility service, so there this is the only way Home reaches Plume.
 * [homes] gives each stock launcher's home screens (see [stockHomes]). With no Google TV list, its windows
 * all count except the dashboard. Kept free of Android classes so it can be tested off the TV.
 */
class HomeWatch(private val fire: Boolean, private val homes: () -> Map<String, Set<String>>) {
    private var settingsMayFollow = false
    private var lastJump = -JUMP_GAP_MS
    private var waitingPkg: String? = null

    /**
     * [KEEP]: leave any jump still waiting. Null: drop it. Otherwise: go home after this many ms.
     */
    fun opened(pkg: String, cls: String?, now: Long): Long? {
        // System UI events do not show that the user left Home; a different launcher screen does.
        if (waitingPkg != null && ((pkg == waitingPkg && isHome(pkg, cls)) ||
                pkg == PLUME || pkg == "android" || pkg == "com.android.systemui")) return KEEP
        waitingPkg = null
        if (!isHome(pkg, cls)) {
            // Fire OS shows its home on the way into (and out of) Amazon Settings, then Settings on top.
            settingsMayFollow = pkg == PLUME || pkg.startsWith(AMAZON_SETTINGS)
            return null
        }
        if (now - lastJump < JUMP_GAP_MS) return KEEP // one Home press sends several of these
        lastJump = now
        if (!settingsMayFollow || !fire) return 0
        waitingPkg = pkg
        return SETTINGS_WAIT_MS
    }

    fun jumped() { waitingPkg = null }

    private fun isHome(pkg: String, cls: String?): Boolean = cls != null && when (pkg) {
        GOOGLE_TV -> homes()[pkg].orEmpty().let { home -> if (home.isEmpty()) !cls.contains("dashboard", ignoreCase = true) else cls in home } // holding Home opens the dashboard
        ANDROID_TV -> cls in homes()[pkg].orEmpty()
        AMAZON -> cls.startsWith("$AMAZON.ui.HomeActivity") // HomeActivity_vNext on current builds
        OLD_AMAZON -> cls == "$OLD_AMAZON.Launcher"
        else -> false
    }

    companion object {
        private const val PLUME = "dev.plume"
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        const val ANDROID_TV = "com.google.android.tvlauncher"
        private const val AMAZON = "com.amazon.tv.launcher"
        private const val OLD_AMAZON = "com.amazon.firelauncher"
        private const val AMAZON_SETTINGS = "com.amazon.tv.settings"
        private const val JUMP_GAP_MS = 600L
        const val SETTINGS_WAIT_MS = 550L
        const val KEEP = -1L
    }
}
