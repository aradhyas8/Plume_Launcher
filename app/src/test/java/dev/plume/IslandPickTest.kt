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
}
