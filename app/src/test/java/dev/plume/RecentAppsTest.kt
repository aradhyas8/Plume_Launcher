package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAppsTest {
    @Test fun onlyOpenedInstalledAppsAppearNewestFirst() {
        val opened = mapOf("old" to 10L, "new" to 30L, "removed" to 40L, "never" to 0L)
        assertEquals(listOf("new", "old"), recentApps(listOf("old", "never", "new")) { opened.getValue(it) })
    }

    @Test fun emptyHistoryHasNoApps() {
        assertEquals(emptyList<String>(), recentApps(listOf("a", "b")) { 0L })
        assertEquals(emptyList<String>(), recentApps(emptyList<String>()) { 1L })
    }

    @Test fun recentAppsFitInTwoRowsAndKeepTiesStable() {
        assertEquals((20 downTo 9).toList(), recentApps((1..20).toList()) { it.toLong() })
        assertEquals(listOf("a", "b"), recentApps(listOf("a", "b")) { 10L })
    }

    @Test fun openingAnAppMovesItFirst() {
        val opened = mutableMapOf("a" to 1L, "b" to 2L)
        val installed = listOf("a", "b")
        assertEquals(listOf("b", "a"), recentApps(installed) { opened.getValue(it) })
        opened["a"] = 3L
        assertEquals(listOf("a", "b"), recentApps(installed) { opened.getValue(it) })
    }

    @Test fun firstHomeIsImmediateAndSecondHomeOpensRecents() {
        val home = HomePress()
        assertFalse(home.released(1_000))
        assertTrue(home.released(1_350))
        assertFalse(home.released(1_400)) // a third press starts a fresh pair
    }

    @Test fun slowPressesDoNotOpenRecents() {
        val home = HomePress()
        assertFalse(home.released(1_000))
        assertFalse(home.released(1_351))
    }

    @Test fun anotherKeyOrAHoldCancelsThePair() {
        val home = HomePress()
        home.released(1_000)
        home.reset()
        assertFalse(home.released(1_200))
    }

    @Test fun anEarlierEventDoesNotCountAsADoublePress() {
        val home = HomePress()
        home.released(1_000)
        assertFalse(home.released(900))
    }
}
