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

    @Test fun lowStartsAtTwentyAndNeedsAReading() {
        assertTrue(RemoteBattery.isLow(20))
        assertTrue(RemoteBattery.isLow(0))
        assertFalse(RemoteBattery.isLow(21))
        assertFalse(RemoteBattery.isLow(null)) // unknown is not low
    }

    @Test fun aReadingStaysGoodForTenMinutes() {
        val min = 60_000L
        assertTrue(RemoteBattery.fresh(1_000, 1_000 + 9 * min))
        assertFalse(RemoteBattery.fresh(1_000, 1_000 + 10 * min))
        assertFalse(RemoteBattery.fresh(0, 5 * min)) // never read
        assertFalse(RemoteBattery.fresh(10 * min, 5 * min)) // the clock went back: read again
    }
}
