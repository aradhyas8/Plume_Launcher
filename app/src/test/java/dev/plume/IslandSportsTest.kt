package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSportsTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun game(state: String, start: Long = 0L, away: String = "39", home: String = "37") = SportsGame(
        "1", "basketball/nba", start, state, state == "post", "9:00 - 2nd", "9:00 - 2nd Quarter",
        SportsSide("11", "IND", "Pacers", away, null), SportsSide("20", "PHI", "76ers", home, null),
    )

    @Test fun liveGamePollsEvery30Seconds() = assertEquals(30_000L, sportsPollMs(listOf(game("pre"), game("in")), 0L))
    @Test fun gameWithinTwoHoursPollsEvery5Minutes() = assertEquals(5 * min, sportsPollMs(listOf(game("pre", start = 2 * hour)), 0L))
    @Test fun laterGamePollsEvery30Minutes() = assertEquals(30 * min, sportsPollMs(listOf(game("pre", start = 3 * hour), game("post")), 0L))
    @Test fun nothingPollsEvery30Minutes() = assertEquals(30 * min, sportsPollMs(emptyList(), 0L))

    @Test fun scoreIsAwayThenHome() = assertEquals("IND 39 – 37 PHI", sportsScore(game("in")))
    @Test fun missingScoreReadsZero() = assertEquals("IND 0 – 0 PHI", sportsScore(game("in", away = "", home = "")))

    @Test fun preGameShowsFromSixHours() = assertTrue(sportsSoon(6 * hour, 0L))
    @Test fun preGameHiddenBeforeSixHours() = assertFalse(sportsSoon(6 * hour + 1, 0L))

    @Test fun scoreChangeWhileLive() = assertTrue(sportsScored(game("in"), game("in", away = "42")))
    @Test fun sameScoreIsNoChange() = assertFalse(sportsScored(game("in"), game("in")))
    @Test fun firstSightIsNoChange() = assertFalse(sportsScored(null, game("in")))
    @Test fun kickOffIsNoChange() = assertFalse(sportsScored(game("pre", away = "", home = ""), game("in", away = "0", home = "0")))
    @Test fun lastSecondScoreCounts() = assertTrue(sportsScored(game("in"), game("post", home = "40")))

    @Test fun clockMovesAfterPeriod() = assertEquals("2nd · 9:00", sportsDetail("9:00 - 2nd"))
    @Test fun tenthsMoveToo() = assertEquals("4th · 34.5", sportsDetail("34.5 - 4th"))
    @Test fun otherDetailStays() = assertEquals("Halftime", sportsDetail("Halftime"))
    @Test fun dateDetailStays() = assertEquals("11/15 - 7:30 PM EST", sportsDetail("11/15 - 7:30 PM EST"))

    @Test fun finalSeenLiveStaysTwoHours() = assertEquals(10 * hour, sportsFinalUntil(8 * hour, 0L, sawLive = true))
    @Test fun oldFinalGoesSoon() = assertEquals(6 * hour, sportsFinalUntil(10 * hour, 0L, sawLive = false))
    @Test fun freshFinalStaysTwoHours() = assertEquals(5 * hour, sportsFinalUntil(3 * hour, 0L, sawLive = false))

    @Test fun espnTimeReads() = assertEquals(1_794_765_600_000L, sportsTime("2026-11-15T18:00Z"))
    @Test fun badTimeIsZero() = assertEquals(0L, sportsTime("soon"))

    @Test fun followedEntryRoundTrips() {
        val t = FollowedTeam("basketball", "nba", "20", "PHI", "Philadelphia 76ers")
        assertEquals(t, FollowedTeam.parse(t.entry))
    }
    @Test fun pipeInNameIsKept() = assertEquals("A/B", FollowedTeam.parse(FollowedTeam("s", "l", "1", "AB", "A|B").entry)?.name)
    @Test fun brokenEntryIsSkipped() = assertNull(FollowedTeam.parse("basketball|nba"))
}
