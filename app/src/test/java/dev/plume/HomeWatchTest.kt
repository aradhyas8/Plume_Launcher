package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchTest {
    private val amazon = "com.amazon.tv.launcher"
    private val amazonHome = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val starter = "com.amazon.firehomestarter"
    private val gtv = "com.google.android.apps.tv.launcherx"
    private val gtvHome = "$gtv.home.HomeActivity"
    private val gtvKids = "$gtv.home.VanillaModeHomeActivity"
    private val oldFire = "com.amazon.firelauncher"
    private fun watch(fire: Boolean = true, homes: Set<String> = setOf(gtvHome, gtvKids)) = HomeWatch(fire) { homes }

    @Test fun amazonHomeFromAnAppJumpsAtOnce() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun onePressJumpsOnce() {
        val w = watch()
        assertEquals(0L, w.opened(amazon, amazonHome, 1_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 1_200))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    // Another window of the same press must not drop the jump that waits for Settings to go away.
    @Test fun aSecondHomeWindowOfThePressKeepsTheWaitingJump() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, "$amazon.ui.HomeActivity", 2_100))
    }

    @Test fun theStarterOfTheAmazonHomeIsNotAHomeWindow() {
        val w = watch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertNull(w.opened(starter, "$starter.HomeStarterActivity", 2_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_050))
    }

    @Test fun otherAmazonLauncherScreensAreLeftAlone() {
        assertNull(watch().opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 1_000))
    }

    @Test fun everyHomeActivityOfTheAmazonLauncherCounts() {
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity", 1_000))
        assertEquals(0L, watch().opened(amazon, "$amazon.ui.HomeActivity_v2", 1_000))
    }

    @Test fun waitsWhenSettingsMayFollow() {
        val w = watch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun theWaitIsOnlyForFireTv() {
        val w = watch(fire = false)
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 2_000))
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(0L, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun olderFireLauncher() {
        assertEquals(0L, watch().opened(oldFire, "$oldFire.Launcher", 1_000))
    }

    @Test fun googleTvHomeJumpsAtOnce() {
        val w = watch(fire = false)
        w.opened("dev.plume", "dev.plume.MainActivity", 2_000)
        assertEquals(0L, w.opened(gtv, gtvHome, 3_000))
        assertEquals(0L, w.opened(gtv, gtvKids, 4_000))
    }

    // #150: the Google TV launcher also draws notices and sign-in screens, and Plume took each for the home screen.
    @Test fun otherGoogleTvLauncherWindowsAreLeftAlone() {
        val w = watch(fire = false)
        for ((i, cls) in listOf(
            "$gtv.networkreauth.NetworkReauthActivity",
            "$gtv.dashboard.DashboardActivity",
            "$gtv.gemini.chat.ChatActivity",
            "android.widget.FrameLayout", // a notice drawn by the launcher
        ).withIndex()) assertNull(cls, w.opened(gtv, cls, 1_000L + i * 1_000))
    }

    // The system gave no list: keep the rule that was there before, so Home on Google TV still works.
    @Test fun withoutAListTheGoogleTvLauncherCountsExceptItsDashboard() {
        val w = watch(fire = false, homes = emptySet())
        assertEquals(0L, w.opened(gtv, gtvHome, 1_000))
        assertNull(w.opened(gtv, "$gtv.dashboard.DashboardActivity", 3_000))
    }

    @Test fun aWindowWithoutAClassIsLeftAlone() {
        assertNull(watch().opened(gtv, null, 1_000))
        assertNull(watch().opened(amazon, null, 1_000))
    }

    @Test fun aLauncherThatIsNotInTheListIsLeftAlone() {
        assertNull(watch().opened("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", 1_000))
    }

    private fun entry(pkg: String, cls: String, target: String? = null) = HomeEntry(pkg, cls, target)

    @Test fun theGoogleTvHomesAreWhatTheLauncherDeclaresForHome() {
        val found = listOf(
            entry(gtv, gtvHome),
            entry(gtv, gtvKids),
            entry(amazon, amazonHome),
            entry("dev.plume", "dev.plume.MainActivity"),
        )
        assertEquals(setOf(gtvHome, gtvKids), googleTvHomes(found))
    }

    @Test fun anAliasCountsUnderItsOwnNameAndItsRealClass() {
        val found = listOf(entry(gtv, "$gtv.HomeAlias", target = "$gtv.home.RealHome"))
        assertEquals(setOf("$gtv.HomeAlias", "$gtv.home.RealHome"), googleTvHomes(found))
    }

    @Test fun noGoogleTvLauncherGivesAnEmptySet() {
        assertEquals(emptySet<String>(), googleTvHomes(listOf(entry(amazon, amazonHome))))
    }
}
