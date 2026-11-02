package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherAppsTest {
    @Test fun tvEntriesTakePrecedenceAndPhoneOnlyAppsRemain() {
        val tv = listOf("both" to "tv", "tv-only" to "tv")
        val other = listOf("both" to "phone", "phone-only" to "phone")
        assertEquals(tv + ("phone-only" to "phone"), preferTvApps(tv, other) { it.first })
    }
}
