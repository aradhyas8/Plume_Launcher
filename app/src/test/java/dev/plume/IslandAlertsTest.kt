package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandAlertsTest {
    private fun passes(
        pkg: String = "com.ring.app", summary: Boolean = false, media: Boolean = false, ongoing: Boolean = false,
        category: String? = null, hasText: Boolean = true, importance: Int? = 3, dndAll: Boolean = true,
    ) = islandAlertPasses(pkg, summary, media, ongoing, category, hasText, importance, dndAll)

    @Test fun anOrdinaryAlertPasses() = assertTrue(passes())
    @Test fun systemNoiseIsSkipped() {
        assertFalse(passes(pkg = "android"))
        assertFalse(passes(pkg = "dev.plume"))
        assertFalse(passes(pkg = "com.google.android.gms"))
        assertFalse(passes(pkg = "com.mediatek.wwtv.tvcenter"))
        assertFalse(passes(pkg = "com.dolby.daxservice"))
    }
    @Test fun summariesAndMediaAreSkipped() {
        assertFalse(passes(summary = true))
        assertFalse(passes(media = true))
        assertFalse(passes(category = "transport"))
    }
    @Test fun ongoingOnlyForCalls() {
        assertFalse(passes(ongoing = true))
        assertTrue(passes(ongoing = true, category = "call"))
    }
    @Test fun emptyIsSkipped() = assertFalse(passes(hasText = false))
    @Test fun quietOnesAreSkippedButNotCalls() {
        assertFalse(passes(importance = 2))
        assertTrue(passes(importance = 2, category = "call"))
        assertTrue(passes(importance = null))
    }
    @Test fun doNotDisturbLetsOnlyCallsAndAlarms() {
        assertFalse(passes(dndAll = false))
        assertTrue(passes(dndAll = false, category = "call"))
        assertTrue(passes(dndAll = false, category = "alarm"))
    }

    private fun text(privacy: String, call: Boolean = false) =
        islandAlertText(privacy, call, "Ring", "Front door", "Someone is at the door", "Incoming call", "New notification")

    @Test fun fullShowsWhatItSays() = assertEquals("Front door" to "Someone is at the door", text("full"))
    @Test fun fullWithoutTitleUsesText() =
        assertEquals("Hi" to null, islandAlertText("full", false, "Ring", "", "Hi", "Incoming call", "New notification"))
    @Test fun appShowsOnlyTheApp() = assertEquals("Ring" to "New notification", text("app"))
    @Test fun appHidesTheCaller() = assertEquals("Ring" to "Incoming call", text("app", call = true))
    @Test fun hiddenShowsNothingButCalls() {
        assertNull(text("hidden"))
        assertEquals("Ring" to "Incoming call", text("hidden", call = true))
    }

    @Test fun cachedFullAlertDisappearsWhenPrivacyTightensOrAppIsMuted() {
        val alert = IslandAlerts.CachedAlert("com.ring.app", "full", IslandItem("doorbell", IslandKind.ALERT, "Front door", until = 2_000))
        assertTrue(alert.visible(1_000, "full", emptySet()))
        assertFalse(alert.visible(1_000, "app", emptySet()))
        assertFalse(alert.visible(1_000, "full", setOf("com.ring.app")))
        assertFalse(alert.visible(2_000, "full", emptySet()))
    }

    @Test fun firstPeekIsAllowed() = assertTrue(islandAlertMayPeek(null, 1_000))
    @Test fun secondPeekWaitsTenSeconds() {
        assertFalse(islandAlertMayPeek(1_000, 10_999))
        assertTrue(islandAlertMayPeek(1_000, 11_000))
    }
}
