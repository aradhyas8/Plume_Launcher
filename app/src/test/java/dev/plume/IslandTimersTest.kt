package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandTimersTest {
    @Test fun timeLeftNeverGoesBelowZero() {
        assertEquals(4_000L, islLeft(10_000L, 6_000L))
        assertEquals(0L, islLeft(10_000L, 12_000L))
    }

    @Test fun fractionIsTheShareThatIsGone() {
        assertEquals(0f, islFraction(600_000L, 600_000L), 0f)
        assertEquals(0.25f, islFraction(450_000L, 600_000L), 1e-6f)
        assertEquals(1f, islFraction(0L, 600_000L), 0f)
        assertEquals(1f, islFraction(0L, 0L), 0f)
        assertEquals(0f, islFraction(700_000L, 600_000L), 0f)
    }

    @Test fun aPausedTimerKeepsItsTimeLeft() {
        // A 10 minute timer from 0, paused at 4 minutes and resumed at 9 minutes, ends at 15 minutes.
        val left = islLeft(600_000L, 240_000L)
        val end = islResumeEnd(left, 540_000L)
        assertEquals(900_000L, end)
        assertEquals(60_000L, islLeft(end, 840_000L))
    }

    @Test fun aPausedStopwatchGoesOnFromWhereItStopped() {
        // Started at 1 s, paused at 11 s (10 s shown), resumed at 61 s: at 66 s it shows 15 s.
        val shown = islElapsed(1_000L, 0L, true, 11_000L)
        assertEquals(10_000L, shown)
        assertEquals(10_000L, islElapsed(1_000L, shown, false, 50_000L))
        val base = islResumeBase(shown, 61_000L)
        assertEquals(15_000L, islElapsed(base, 0L, true, 66_000L))
    }

    @Test fun sleepWordsRoundUp() {
        assertEquals(28L, islSleepCount(27 * 60_000L + 1L))
        assertEquals(1L, islSleepCount(60_000L))
        assertEquals(45L, islSleepCount(44_001L))
        assertEquals(0L, islSleepCount(0L))
    }
}
