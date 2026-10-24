package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconStyleTest {
    @Test fun aStoredStyleWins() {
        assertEquals("banner", iconStyleOf("banner", oldColour = false))
        assertEquals("mono", iconStyleOf("mono", oldColour = true))
        assertEquals("colour", iconStyleOf("colour", oldColour = false))
    }

    @Test fun theMonochromeBannerIsABannerStyle() {
        assertEquals("bannermono", iconStyleOf("bannermono", oldColour = true))
        assertTrue(isBannerStyle("banner"))
        assertTrue(isBannerStyle("bannermono"))
        assertFalse(isBannerStyle("mono"))
        assertFalse(isBannerStyle("colour"))
    }

    @Test fun peopleWhoChoseColourBeforeStylesStayOnColour() {
        // Plume before the Banner style kept Colour as an on/off setting and no style.
        assertEquals("colour", iconStyleOf(null, oldColour = true))
    }

    @Test fun noChoiceMeansMonochrome() {
        assertEquals("mono", iconStyleOf(null, oldColour = false))
    }

    @Test fun anUnknownStyleFallsBackToTheOldSetting() {
        assertEquals("mono", iconStyleOf("glass", oldColour = false))
        assertEquals("colour", iconStyleOf("glass", oldColour = true))
    }
}
