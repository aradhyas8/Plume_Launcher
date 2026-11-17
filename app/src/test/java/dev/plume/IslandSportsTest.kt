package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

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
        assertEquals(t.entry, FollowedTeam.parse(t.entry)?.entry)
    }
    @Test fun pipeInNameIsKept() = assertEquals("A/B", FollowedTeam.parse(FollowedTeam("s", "l", "1", "AB", "A|B").entry)?.name)
    @Test fun brokenEntryIsSkipped() = assertNull(FollowedTeam.parse("basketball|nba"))

    @Test fun pinnedEntryRoundTrips() {
        val p = PinnedGame("soccer", "eng.1", "401", 1_000L)
        assertEquals(p.entry, PinnedGame.parse(p.entry)?.entry)
    }
    @Test fun pinWithoutEndIsSkipped() = assertNull(PinnedGame.parse("soccer|eng.1|401|soon"))

    // 2026-11-15T18:00Z is 1 PM in New York: eight hours back and six ahead are both 15 November there.
    private val noonNy = 1_794_765_600_000L
    @Test fun middayAsksOneDay() = assertEquals(listOf("20261115"), sportsDays(noonNy))
    @Test fun lateEveningAsksTomorrowToo() = assertEquals(listOf("20261115", "20261116"), sportsDays(noonNy + 9 * hour))
    @Test fun earlyMorningAsksYesterdayToo() = assertEquals(listOf("20261114", "20261115"), sportsDays(noonNy - 7 * hour))

    @Test fun datesJoinLeagueQuery() = assertEquals("?groups=80&dates=20261115", sportsQuery("groups=80", "20261115"))
    @Test fun datesAlone() = assertEquals("?dates=20261115", sportsQuery("", "20261115"))
    @Test fun noQueryNoMark() = assertEquals("", sportsQuery("", null))

    @Test fun pinBeatsTeamBeatsLeague() {
        assertEquals(SPORTS_PIN, sportsSource(game("pre", start = 5 * hour), pinned = true, team = true, league = true, now = 0L))
        assertEquals(SPORTS_TEAM, sportsSource(game("pre", start = 5 * hour), pinned = false, team = true, league = true, now = 0L))
    }
    @Test fun leagueShowsLive() = assertEquals(SPORTS_LEAGUE, sportsSource(game("in"), false, false, true, 0L))
    @Test fun leagueShowsThirtyMinutesAhead() = assertEquals(SPORTS_LEAGUE, sportsSource(game("pre", start = 30 * min), false, false, true, 0L))
    @Test fun leagueHidesLaterGame() = assertNull(sportsSource(game("pre", start = 31 * min), false, false, true, 0L))
    @Test fun leagueHidesFinal() = assertNull(sportsSource(game("post"), false, false, true, 0L))
    @Test fun unfollowedGameHidden() = assertNull(sportsSource(game("in"), false, false, false, 0L))

    @Test fun sinceOrdersSourceThenLiveThenChange() {
        val order = listOf(
            sportsSince(SPORTS_PIN, true, 2_000L), sportsSince(SPORTS_PIN, true, 1_000L), sportsSince(SPORTS_PIN, false, 3_000L),
            sportsSince(SPORTS_TEAM, true, 3_000L), sportsSince(SPORTS_TEAM, false, -noonNy), sportsSince(SPORTS_LEAGUE, true, noonNy),
        )
        assertEquals(order, order.sortedDescending())
    }
    @Test fun soonerUpcomingFirst() = assertTrue(sportsSince(SPORTS_TEAM, false, -noonNy) > sportsSince(SPORTS_TEAM, false, -(noonNy + hour)))

    @Test fun liveIsNow() = assertTrue(sportsNow(game("in", start = -5 * hour), 0L))
    @Test fun withinThreeHoursIsNow() = assertTrue(sportsNow(game("pre", start = 3 * hour), 0L))
    @Test fun laterIsNotNow() = assertFalse(sportsNow(game("pre", start = 3 * hour + 1), 0L))
    @Test fun finalIsNotNow() = assertFalse(sportsNow(game("post"), 0L))

    @Test fun playingTodayIsLocalDay() {
        val utc = ZoneId.of("UTC")
        val today = game("pre", start = noonNy + 5 * hour) // 23:00 UTC, still 15 November
        val tomorrow = SportsGame("1", "basketball/nba", noonNy + 7 * hour, "pre", false, "9:00 - 2nd", "9:00 - 2nd Quarter",
            SportsSide("1", "A", "A", "", null), SportsSide("2", "B", "B", "", null))
        assertEquals(setOf("11", "20"), sportsPlayingToday(listOf(today, tomorrow), noonNy, utc))
    }
    @Test fun liveFromYesterdayPlaysToday() = assertEquals(setOf("11", "20"), sportsPlayingToday(listOf(game("in", start = 0L)), noonNy, ZoneId.of("UTC")))


    @Test fun liveTeamGameComesFirst() = assertEquals(listOf(0, 1, 2, 2), listOf(
        sportsNowRank(true, false, "2nd · 1:28"), sportsNowRank(true, true, "Round 3"),
        sportsNowRank(true, true, "Round 3 - Suspended"), sportsNowRank(false, false, null)))
    @Test fun sponsorGoes() = assertEquals("Austin Championship", sportsShortName("Austin Championship pres. by Compliance Solutions"))
    @Test fun plainNameStays() = assertEquals("Brazilian Grand Prix", sportsShortName("Brazilian Grand Prix"))

    // ---------- cricket (scores as ESPN's cricket score panel sends them) ----------

    private fun cricket(summary: String, away: String, home: String) = SportsGame(
        "2", "cricket/scorepanel", 0L, "in", false, summary, summary,
        SportsSide("1", "ABT", "Abbottabad", away, null), SportsSide("2", "HYD-R", "Hyderabad R", home, null),
        name = "ABT v HYD-R", cricket = true,
    )

    @Test fun cricketRunsDropOvers() = assertEquals("339 & 169/7", cricketRuns("339 & 169/7 (58 ov)"))
    @Test fun cricketScoreShowsBothSides() = assertEquals("ABT 118/4 · HYD-R 121", cricketScore(cricket("", "118/4 (28.1 ov)", "121")).replace('\u00A0', ' '))
    @Test fun cricketSideNotBattedShowsLetters() = assertEquals("ABT 335/7 · HYD-R", cricketScore(cricket("", "335/7", "")).replace('\u00A0', ' '))

    @Test fun cricketFollowsTheMatchName() {
        // ESPN sends ABT as home, but names the match "ABT v HYD-R": ABT comes first.
        val g = SportsGame("2", "cricket/scorepanel", 0L, "in", false, "", "",
            SportsSide("2", "HYD-R", "Hyderabad R", "121", null), SportsSide("1", "ABT", "Abbottabad", "118/4 (28.1 ov)", null),
            name = "ABT v HYD-R", cricket = true)
        assertEquals("ABT 118/4 · HYD-R 121", cricketScore(g).replace('\u00A0', ' '))
        assertEquals("ABT", sportsSides(g).first?.abbreviation)
    }


    @Test fun cricketTossHidesOncePlayStarts() {
        val c = cricketState(cricket("Hyderabad R won toss", "118/4 (28.1 ov)", "121"))
        assertEquals("ABT", c.batting)
        assertEquals("28.1", c.overs)
        assertEquals("Hyderabad R", c.toss)
        assertNull(c.note)
        assertTrue(c.runs)
    }

    @Test fun cricketTossBeforeFirstBall() {
        val c = cricketState(cricket("Hyderabad R won the toss", "", ""))
        assertNull(c.batting)
        assertEquals("Hyderabad R", c.toss)
        assertFalse(c.runs)
    }

    @Test fun cricketSummaryThatSaysMoreStays() =
        assertEquals("Zimbabwe lead by 291 runs", cricketState(cricket("Zimbabwe lead by 291 runs", "217", "339 & 169/7 (58 ov)")).note)


    // ---------- picks ----------

    private fun pin(id: String, at: Long) = PinnedGame("nba", "nba", id, 9_000L, at, "IND at PHI")

    @Test fun pickReplacesTheOnlyGame() = assertEquals(listOf("1"), sportsPinsToDrop(listOf(pin("1", 10)), 1).map { it.eventId })
    @Test fun pickFitsWhenThereIsRoom() = assertEquals(emptyList<String>(), sportsPinsToDrop(listOf(pin("1", 10)), 2).map { it.eventId })
    @Test fun pickReplacesTheOldest() = assertEquals(listOf("2"), sportsPinsToDrop(listOf(pin("1", 30), pin("2", 10)), 2).map { it.eventId })
    // The limit went down to 1 after three picks: the new pick is the only one left.
    @Test fun smallerLimitDropsMore() = assertEquals(listOf("2", "1", "3"),sportsPinsToDrop(listOf(pin("1", 30), pin("2", 10), pin("3", 50)), 1).map { it.eventId })

    @Test fun pickKeepsItsNameAndTime() {
        val p = PinnedGame("nba", "nba", "7", 9_000L, 42, "A|B at C")
        assertEquals(PinnedGame("nba", "nba", "7", 9_000L, 42, "A/B at C").entry, PinnedGame.parse(p.entry)?.entry)
    }

    @Test fun oldPickStillReads() = assertEquals(PinnedGame("nba", "nba", "7", 9_000L).entry, PinnedGame.parse("nba|nba|7|9000")?.entry)

}
