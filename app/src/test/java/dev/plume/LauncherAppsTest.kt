package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAppsTest {
    @Test fun tvEntriesTakePrecedenceAndPhoneOnlyAppsRemain() {
        val tv = listOf("both" to "tv", "tv-only" to "tv")
        val other = listOf("both" to "phone", "phone-only" to "phone")
        assertEquals(tv + ("phone-only" to "phone"), preferTvApps(tv, other) { it.first })
    }

    @Test fun onlyTvMakerPhoneAppsAreExtra() {
        assertFalse(isExtraApp(tvEntry = true, systemApp = true)) // Netflix, YouTube shipped with the TV
        assertFalse(isExtraApp(tvEntry = false, systemApp = false)) // a phone app the user installed
        assertTrue(isExtraApp(tvEntry = false, systemApp = true))
    }

    @Test fun extraAppsStartOutOfTheRow() {
        assertFalse(inRow("x", emptySet(), extra = true))
        assertTrue(inRow("x", setOf("x"), extra = true))
        assertTrue(inRow("y", emptySet()))
    }
}
