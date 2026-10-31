package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeWatchTest {
    private val amazon = "com.amazon.tv.launcher"
    private val amazonHome = "com.amazon.tv.launcher.ui.HomeActivity_vNext"

    @Test fun amazonHomeFromAnAppJumpsAtOnce() {
        val w = HomeWatch()
        assertNull(w.opened("com.netflix.ninja", "Player", 1_000))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun onePressJumpsOnce() {
        val w = HomeWatch()
        assertEquals(0L, w.opened(amazon, amazonHome, 1_000))
        assertNull(w.opened(amazon, amazonHome, 1_200))
        assertEquals(0L, w.opened(amazon, amazonHome, 2_000))
    }

    @Test fun otherAmazonLauncherScreensAreLeftAlone() {
        assertNull(HomeWatch().opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 1_000))
    }

    @Test fun waitsWhenSettingsMayFollow() {
        val w = HomeWatch()
        w.opened("com.amazon.tv.settings.v2", "Settings", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        w.jumped()
        w.opened("dev.plume", "dev.plume.MainActivity", 3_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 4_000))
    }

    @Test fun olderFireLauncher() {
        assertEquals(0L, HomeWatch().opened("com.amazon.firelauncher", "com.amazon.firelauncher.Launcher", 1_000))
    }

    @Test fun googleTvUnchanged() {
        val w = HomeWatch()
        val gtv = "com.google.android.apps.tv.launcherx"
        assertNull(w.opened(gtv, "com.google.android.apps.tv.launcherx.dashboard.DashboardActivity", 1_000))
        w.opened("dev.plume", "dev.plume.MainActivity", 2_000)
        assertEquals(0L, w.opened(gtv, "com.google.android.apps.tv.launcherx.home.HomeActivity", 3_000))
    }

    // Home pressed with Plume on screen: Fire OS sent more than one window event for the press, and the second one
    // dropped the jump that waited for Settings, so Amazon's home stayed on screen (seen on a Fire TV, Fire OS 7.7.1.7).
    @Test fun moreWindowsOfTheSamePressKeepTheWaitingJump() {
        val w = HomeWatch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_100))
        assertEquals(HomeWatch.KEEP, w.opened("com.android.systemui", "Panel", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened("dev.plume", "dev.plume.MainActivity", 2_300))
    }

    @Test fun anotherAppOpeningDropsTheWaitingJump() { // Settings on top of Amazon's home
        val w = HomeWatch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened("com.amazon.tv.settings.v2", "Settings", 2_200))
    }

    @Test fun waitingJumpSurvivesTheGapUntilItRuns() {
        val w = HomeWatch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_000 + HomeWatch.SETTINGS_WAIT_MS))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_575))
        w.jumped()
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 3_000))
    }

    @Test fun anotherAmazonLauncherScreenDropsTheWaitingJump() {
        val w = HomeWatch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertNull(w.opened(amazon, "com.amazon.tv.launcher.ui.QuickSettingsActivity", 2_200))
    }

    @Test fun androidWindowDoesNotDropTheWaitingJump() {
        val w = HomeWatch()
        w.opened("dev.plume", "dev.plume.MainActivity", 1_000)
        assertEquals(HomeWatch.SETTINGS_WAIT_MS, w.opened(amazon, amazonHome, 2_000))
        assertEquals(HomeWatch.KEEP, w.opened("android", "Dialog", 2_200))
        assertEquals(HomeWatch.KEEP, w.opened(amazon, amazonHome, 2_300))
    }
}
