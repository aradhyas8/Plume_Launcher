package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandPickTest {
    private data class S(val name: String, val playing: Boolean, val title: Boolean)

    private fun pick(vararg s: S) = islandPick(s.toList(), { it.playing }, { it.title })?.name

    @Test fun playingWinsOverAnEarlierPausedOne() = assertEquals("b", pick(S("a", false, true), S("b", true, true)))
    @Test fun pausedWithTitleWhenNothingPlays() = assertEquals("a", pick(S("a", false, true), S("b", false, false)))
    @Test fun playingWithoutTitleIsSkipped() = assertEquals("b", pick(S("a", true, false), S("b", false, true)))
    @Test fun noneWithoutTitles() = assertNull(pick(S("a", true, false)))

    @Test fun playingMovesOnSinceTheUpdate() = assertEquals(15_000L, islandPosition(10_000, 1_000, 6_000, 1f, true, 200_000))
    @Test fun pausedStaysPut() = assertEquals(10_000L, islandPosition(10_000, 1_000, 6_000, 1f, false, 200_000))
    @Test fun neverPastTheEnd() = assertEquals(200_000L, islandPosition(199_000, 1_000, 60_000, 1f, true, 200_000))
    @Test fun timeReads() = assertEquals("3:07", islandTime(187_400))
    @Test fun pausedLongStepsAside() = assertEquals(true, islandIdle(1_000, 301_000, 300_000, selected = false))
    @Test fun pausedShortStays() = assertEquals(false, islandIdle(1_000, 200_000, 300_000, selected = false))
    @Test fun playingStays() = assertEquals(false, islandIdle(0, 900_000, 300_000, selected = false))
    @Test fun selectedStays() = assertEquals(false, islandIdle(1_000, 900_000, 300_000, selected = true))
}
