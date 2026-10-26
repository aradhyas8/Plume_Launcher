package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBatteryTest {
    @Test fun theLabelIsThePercentOrADash() {
        assertEquals("69%", RemoteBattery.label(69))
        assertEquals("0%", RemoteBattery.label(0))
        assertEquals("–", RemoteBattery.label(null))
    }

    @Test fun lowIsAtOrUnderTheChosenLevelAndNeedsAReading() {
        assertTrue(RemoteBattery.isLow(20, 20))
        assertTrue(RemoteBattery.isLow(15, 15))
        assertFalse(RemoteBattery.isLow(16, 15))
        assertTrue(RemoteBattery.isLow(0, 20))
        assertFalse(RemoteBattery.isLow(5, 0)) // alert off
        assertFalse(RemoteBattery.isLow(null, 20)) // unknown is not low
        assertEquals(listOf(20, 15, 0), RemoteBattery.LOWS)
    }

    @Test fun aReadingStaysGoodForTenMinutes() {
        val min = 60_000L
        assertTrue(RemoteBattery.fresh(1_000, 1_000 + 9 * min))
        assertFalse(RemoteBattery.fresh(1_000, 1_000 + 10 * min))
        assertFalse(RemoteBattery.fresh(0, 5 * min)) // never read
        assertFalse(RemoteBattery.fresh(10 * min, 5 * min)) // the clock went back: read again
    }

    @Test fun theWorstRemoteIsTheOneThatCounts() {
        val r = listOf(RemoteBattery.Reading("Remote", 69), RemoteBattery.Reading("Pad", 31))
        assertEquals(31, RemoteBattery.lowest(r))
        assertEquals("Remote 69%, Pad 31%", RemoteBattery.describe(r))
        assertEquals(null, RemoteBattery.lowest(emptyList()))
    }
}
