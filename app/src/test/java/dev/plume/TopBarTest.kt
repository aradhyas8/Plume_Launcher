package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarTest {
    private val known = listOf("wifi", "sources", "apps", "system", "plume")

    @Test fun nothingSavedShowsTheDefault() {
        assertEquals(known, TopBar.parse(null, known, known, "plume"))
    }

    @Test fun aSavedListKeepsItsOrderAndDropsWhatIsUnknownOrRepeated() {
        assertEquals(listOf("apps", "wifi", "plume"), TopBar.parse("apps,wifi,gone,apps,plume", known, known, "plume"))
    }

    @Test fun plumeSettingsIsNeverMissing() {
        assertEquals(listOf("wifi", "plume"), TopBar.parse("wifi", known, known, "plume"))
        assertEquals(listOf("plume"), TopBar.parse("", known, known, "plume")) // the bar is never empty
    }

    @Test fun anIconSlidesPastTheOnesOnScreen() {
        val order = listOf("wifi", "sources", "apps", "plume")
        val shown = listOf("wifi", "apps", "plume") // no input: Sources is not on screen
        assertEquals(listOf("wifi", "sources", "plume", "apps"), TopBar.slide(order, shown, "apps", 1))
        assertEquals(listOf("apps", "sources", "wifi", "plume"), TopBar.slide(order, shown, "apps", -1))
    }

    @Test fun anIconAtAnEndStaysThere() {
        val order = listOf("wifi", "apps", "plume")
        assertEquals(order, TopBar.slide(order, order, "wifi", -1))
        assertEquals(order, TopBar.slide(order, order, "plume", 1))
        assertEquals(order, TopBar.slide(order, order, "system", 1)) // not on the bar
    }

    @Test fun anAddedIconLandsRightAfterTheOneYouHeld() {
        val order = listOf("wifi", "apps", "plume")
        assertEquals(listOf("wifi", "system", "apps", "plume"), TopBar.addAfter(order, "system", "wifi"))
        assertEquals(listOf("wifi", "apps", "plume", "system"), TopBar.addAfter(order, "system", "plume"))
        assertEquals(listOf("wifi", "apps", "plume", "system"), TopBar.addAfter(order, "system", "missing"))
    }
}
