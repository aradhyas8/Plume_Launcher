package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchTest {
    private val amazon = "com.amazon.tv.launcher"
    private val amazonHome = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val gtv = "com.google.android.apps.tv.launcherx"
    private val gtvHome = "$gtv.home.HomeActivity"
    private val oldFire = "com.amazon.firelauncher"
    private val stock = setOf(
        amazon to amazonHome,
        gtv to gtvHome,
        gtv to "$gtv.home.VanillaModeHomeActivity",
        oldFire to "$oldFire.Launcher",
    )
    private fun watch() = HomeWatch { pkg, cls -> pkg to cls in stock }

    @Test fun amazonHomeFromAnAppJumpsAtOnce() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun onePressJumpsOnce() {
        val w = watch()
        assertEquals(0L, w.opened(amazon, amazonHome, 1_000))
        assertNull(w.opened(amazon, amazonHome, 1_200))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun otherAmazonLauncherScreensAreLeftAlone() {
        assertNull(watch().opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 1_000))
    }

    @Test fun waitsWhenSettingsMayFollow() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun olderFireLauncher() {
        assertEquals(0L, watch().opened(oldFire, "$oldFire.Launcher", 1_000))
    }

    @Test fun googleTvHomeJumpsAtOnce() {
        val w = watch()
        w.opened("dev.plume", "dev.plume.MainActivity", 2_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 3_000))
        assertEquals(0L, w.opened(gtv, "$gtv.home.VanillaModeHomeActivity", 4_000))
    }

    // #150: the Google TV launcher also draws notices and sign-in screens, and Plume took each for the home screen.
    @Test fun otherGoogleTvLauncherWindowsAreLeftAlone() {
        val w = watch()
        for ((i, cls) in listOf(
            "$gtv.networkreauth.NetworkReauthActivity",
            "$gtv.dashboard.DashboardActivity",
            "$gtv.gemini.chat.ChatActivity",
            "android.widget.FrameLayout", // a notice drawn by the launcher
        ).withIndex()) assertNull(cls, w.opened(gtv, cls, 1_000L + i * 1_000))
    }

    @Test fun aWindowWithoutAClassIsLeftAlone() {
        assertNull(watch().opened(gtv, null, 1_000))
    }

    @Test fun aLauncherThatIsNotInTheListIsLeftAlone() {
        assertNull(watch().opened("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", 1_000))
    }

    private fun entry(pkg: String, cls: String, target: String? = null, system: Boolean = true, priority: Int = 0) =
        HomeEntry(pkg, cls, target, system, priority)

    @Test fun stockHomesAreTheSystemHomesOfOtherApps() {
        val found = listOf(
            entry(amazon, amazonHome, priority = 950),
            entry("com.amazon.firehomestarter", "com.amazon.firehomestarter.HomeStarterActivity", priority = 1),
            entry("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", system = false), // the user's own choice
            entry("dev.plume", "dev.plume.MainActivity"),
            entry("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.system.FallbackHome", priority = -1000),
        )
        assertEquals(
            setOf(amazon to amazonHome, "com.amazon.firehomestarter" to "com.amazon.firehomestarter.HomeStarterActivity"),
            stockHomes(found, "dev.plume"),
        )
    }

    @Test fun anAliasCountsUnderItsOwnNameAndItsRealClass() {
        val found = listOf(entry("com.vendor.tv", "com.vendor.tv.HomeAlias", target = "com.vendor.tv.RealHome"))
        assertEquals(setOf("com.vendor.tv" to "com.vendor.tv.HomeAlias", "com.vendor.tv" to "com.vendor.tv.RealHome"), stockHomes(found, "dev.plume"))
    }

    @Test fun theTestVariantOfPlumeIsNotAStockHome() {
        assertEquals(emptySet<Pair<String, String>>(), stockHomes(listOf(entry("dev.plume.an", "dev.plume.an.MainActivity")), "dev.plume.an"))
    }
}
