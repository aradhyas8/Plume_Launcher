package dev.plume

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The island's sports source, from ESPN's public site API (no key, no account). Three things put a game on the
 * island: a game the user pinned from "Games now" ([gamesNow], [pinGame]), a game of a followed team ([follow]), and
 * a live or about-to-start game of a followed league ([followLeague]). The home screen calls [start] while it is
 * shown and [stop] when it leaves; each poll asks one scoreboard per league that any of the three needs, and how soon
 * the next poll comes depends on how close the games are (see [sportsPollMs]). The settings call [start] again after
 * every change. Nothing about the user goes out: the requests carry only the league and dates in the URL and a
 * generic User-Agent.
 */
internal object IslandSports : IslandSource {
    /**
     * A league the settings can offer, in the sport folder [group] (a string resource). [query] is added to its
     * scoreboard request. [teams] is false for the leagues with no teams list (races, tennis, golf, fights, cricket):
     * they have no Teams page, and their scoreboard is asked without dates, because it holds one event that lasts
     * days (a race weekend, a tournament) or, for cricket, every series on now, and a date filter drops those.
     */
    class League(val sport: String, val league: String, val name: String, val group: Int, val query: String = "", val teams: Boolean = true) {
        /** "sport/league", the same as [SportsGame.league]. */
        val key get() = "$sport/$league"
        internal val entry get() = "$sport|$league"
    }

    data class Team(val id: String, val name: String, val abbreviation: String, val logo: String?)

    /** A game for the "Games now" page: [title] is the live score or "IND at PHI · 7:30 PM". */
    data class NowGame(
        val sport: String, val league: String, val leagueName: String, val eventId: String, val title: String,
        val awayLogo: String?, val homeLogo: String?, val live: Boolean, val start: Long, val rank: Int = 2,
    )

    // The league names are proper names, so they stay plain constants rather than translated resources. ESPN has no
    // cricket list of all series, but its score panel ("scorepanel") holds every match on now, so "Cricket" is that.
    val LEAGUES = listOf(
        League("football", "nfl", "NFL", R.string.isl_sports_group_american_football),
        // Without groups=80 the college scoreboard has only the ranked games. ponytail: a game between two teams
        // outside the top division (FBS) still does not show; ask for groups=81 too if someone follows such a team.
        League("football", "college-football", "College Football", R.string.isl_sports_group_american_football, "groups=80"),
        League("basketball", "nba", "NBA", R.string.isl_sports_group_basketball),
        League("basketball", "wnba", "WNBA", R.string.isl_sports_group_basketball),
        // Without groups=50 the scoreboard has only a few featured games, not all of Division I.
        League("basketball", "mens-college-basketball", "College Basketball", R.string.isl_sports_group_basketball, "groups=50"),
        League("baseball", "mlb", "MLB", R.string.isl_sports_group_baseball),
        League("hockey", "nhl", "NHL", R.string.isl_sports_group_hockey),
        League("soccer", "eng.1", "Premier League", R.string.isl_sports_group_football),
        League("soccer", "esp.1", "La Liga", R.string.isl_sports_group_football),
        League("soccer", "ger.1", "Bundesliga", R.string.isl_sports_group_football),
        League("soccer", "ita.1", "Serie A", R.string.isl_sports_group_football),
        League("soccer", "fra.1", "Ligue 1", R.string.isl_sports_group_football),
        League("soccer", "ned.1", "Eredivisie", R.string.isl_sports_group_football),
        League("soccer", "usa.1", "MLS", R.string.isl_sports_group_football),
        League("soccer", "mex.1", "Liga MX", R.string.isl_sports_group_football),
        League("soccer", "ksa.1", "Saudi Pro League", R.string.isl_sports_group_football),
        League("soccer", "uefa.champions", "Champions League", R.string.isl_sports_group_football),
        League("soccer", "uefa.europa", "Europa League", R.string.isl_sports_group_football),
        League("cricket", "8048", "IPL", R.string.isl_sports_group_cricket, teams = false),
        League("cricket", "8044", "Big Bash League", R.string.isl_sports_group_cricket, teams = false),
        League("cricket", PANEL, "Cricket", R.string.isl_sports_group_cricket, teams = false),
        League("racing", "f1", "Formula 1", R.string.isl_sports_group_motorsport, teams = false),
        League("tennis", "atp", "ATP", R.string.isl_sports_group_tennis, teams = false),
        League("tennis", "wta", "WTA", R.string.isl_sports_group_tennis, teams = false),
        League("golf", "pga", "PGA Tour", R.string.isl_sports_group_golf, teams = false),
        League("mma", "ufc", "UFC", R.string.isl_sports_group_fighting, teams = false),
    )

    const val PREF_ON = "isl_sports"
    const val PREF_TEAMS = "isl_teams"
    const val PREF_LEAGUES = "isl_leagues"
    const val PREF_GAMES = "isl_games"

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val PANEL = "scorepanel"
    private const val PEEK_MS = 5_000L
    private const val NOW_CACHE_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val io by lazy { Executors.newSingleThreadScheduledExecutor() }
    @Volatile private var app: Context? = null
    @Volatile private var task: ScheduledFuture<*>? = null
    // Each start() takes a new number, so a poll from an earlier start that is still running does not schedule a
    // second chain of polls.
    @Volatile private var generation = 0

    /**
     * A game as the island last saw it: why it shows ([source], see [sportsSource]), when its score last changed,
     * when it peeks and when it goes, kept across polls.
     */
    private class Shown(val game: SportsGame, val source: Int, val changed: Long, val peekUntil: Long, val until: Long)

    @Volatile private var shown: List<Shown> = emptyList()
    // The last good scoreboard of each league, by "sport/league", so the Teams page can put today's teams first.
    private val lastBoard = ConcurrentHashMap<String, List<SportsGame>>()
    @Volatile private var nowCache: Pair<Long, List<NowGame>>? = null

    // ---------- what the user follows (for the settings pages; no network) ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    private fun stored(ctx: Context, key: String) = prefs(ctx).getStringSet(key, null).orEmpty()

    // The set getStringSet returns must not be changed in place, so the edit works on a copy.
    private fun edit(ctx: Context, key: String, change: (MutableSet<String>) -> Unit) {
        val copy = HashSet(stored(ctx, key))
        change(copy)
        prefs(ctx).edit().putStringSet(key, copy).apply()
    }

    fun followed(ctx: Context): List<FollowedTeam> = stored(ctx, PREF_TEAMS).mapNotNull(FollowedTeam::parse).sortedBy { it.name }

    fun follow(ctx: Context, sport: String, league: String, team: Team) {
        val entry = FollowedTeam(sport, league, team.id, team.abbreviation, team.name).entry
        edit(ctx, PREF_TEAMS) { set -> set.removeAll { FollowedTeam.parse(it)?.same(sport, league, team.id) == true }; set += entry }
    }

    fun unfollow(ctx: Context, sport: String, league: String, teamId: String) =
        edit(ctx, PREF_TEAMS) { set -> set.removeAll { FollowedTeam.parse(it)?.same(sport, league, teamId) == true } }

    /** The followed leagues, in [LEAGUES] order. Stored as "sport|league". */
    fun followedLeagues(ctx: Context): List<League> = stored(ctx, PREF_LEAGUES).let { set -> LEAGUES.filter { it.entry in set } }

    fun followLeague(ctx: Context, l: League) = edit(ctx, PREF_LEAGUES) { it += l.entry }

    fun unfollowLeague(ctx: Context, l: League) = edit(ctx, PREF_LEAGUES) { it -= l.entry }

    fun pinned(ctx: Context): List<PinnedGame> = stored(ctx, PREF_GAMES).mapNotNull(PinnedGame::parse)

    /**
     * Pins a game from [gamesNow]. The pin goes by itself two hours after the game's final; when the island never
     * sees a final (the TV was off), it goes eight hours after the start, or after the pin for an event that started
     * days ago (golf, a Test match).
     */
    fun pinGame(ctx: Context, g: NowGame) {
        val entry = PinnedGame(g.sport, g.league, g.eventId, maxOf(g.start, System.currentTimeMillis()) + 8 * HOUR).entry
        edit(ctx, PREF_GAMES) { set -> set.removeAll { PinnedGame.parse(it)?.eventId == g.eventId }; set += entry }
    }

    fun unpinGame(ctx: Context, eventId: String) =
        edit(ctx, PREF_GAMES) { set -> set.removeAll { PinnedGame.parse(it)?.eventId == eventId } }

    /**
     * The hold menu's "unfollow" for the island item [itemKey] ("sports:<eventId>"): unpins the game when it is
     * pinned, else unfollows the followed teams playing in it, else unfollows its league. Returns what it did, for a
     * toast, or null when the game is not one the island shows.
     */
    fun unfollowFor(ctx: Context, itemKey: String): String? {
        val id = itemKey.removePrefix("sports:")
        val g = shown.firstOrNull { it.game.id == id }?.game ?: return null
        if (pinned(ctx).any { it.eventId == id }) {
            unpinGame(ctx, id)
            return ctx.getString(R.string.isl_sports_unpinned, label(ctx, g))
        }
        val (sport, league) = g.league.split('/', limit = 2)
        val teams = followed(ctx).filter { it.sport == sport && it.league == league && (it.teamId == g.away?.id || it.teamId == g.home?.id) }
        if (teams.isNotEmpty()) {
            teams.forEach { unfollow(ctx, sport, league, it.teamId) }
            return ctx.getString(R.string.isl_sports_unfollowed, teams.joinToString(", ") { it.name })
        }
        val l = followedLeagues(ctx).firstOrNull { it.key == g.league } ?: return null
        unfollowLeague(ctx, l)
        return ctx.getString(R.string.isl_sports_unfollowed, l.name)
    }

    // ---------- settings lists (network, off the main thread) ----------

    /**
     * A league's teams for the Teams page: the teams that play today first, then by name. [done] runs on the main
     * thread, with an empty list on failure. [playingToday] then says which ones play today, so the page can mark them.
     */
    fun teams(sport: String, league: String, done: (List<Team>) -> Unit) {
        // Its own thread, so the list does not wait behind a slow poll.
        Thread {
            val list = try {
                // The default page holds only 50 teams; college football has over 700.
                val teams = islandJson("$BASE/$sport/$league/teams?limit=1000").getJSONArray("sports").getJSONObject(0)
                    .getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
                val all = (0 until teams.length()).mapNotNull { i ->
                    val t = teams.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
                    val id = t.optString("id").ifEmpty { return@mapNotNull null }
                    Team(id, t.optString("displayName"), t.optString("abbreviation"),
                        t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifEmpty { null })
                }
                // A league nobody follows has no scoreboard yet; one more request here gives the page today's teams.
                if (!lastBoard.containsKey("$sport/$league")) LEAGUES.firstOrNull { it.key == "$sport/$league" }?.let { fetch(it, System.currentTimeMillis()) }
                val today = playingToday(sport, league)
                all.sortedWith(compareBy<Team>({ it.id !in today }, { it.name }))
            } catch (e: Exception) {
                android.util.Log.w("Plume", "sports teams $sport/$league failed ($e)")
                emptyList()
            }
            main.post { done(list) }
        }.start()
    }

    /** The ids of the league's teams that play today (local time), from its last scoreboard. No network. */
    fun playingToday(sport: String, league: String): Set<String> =
        sportsPlayingToday(lastBoard["$sport/$league"].orEmpty(), System.currentTimeMillis(), ZoneId.systemDefault())

    /**
     * Every game of every league that is live or starts within three hours, live first, then by start time. [done]
     * runs on the main thread; a league that fails is left out. The list is kept for a minute, so going back and
     * forth on the page does not ask ESPN again.
     */
    fun gamesNow(ctx: Context, done: (List<NowGame>) -> Unit) {
        nowCache?.takeIf { System.currentTimeMillis() - it.first < NOW_CACHE_MS }?.let { cached -> main.post { done(cached.second) }; return }
        Thread {
            val now = System.currentTimeMillis()
            // A few leagues at a time: one by one, 26 leagues take too long; all at once is a burst of requests.
            val pool = Executors.newFixedThreadPool(6)
            val jobs = LEAGUES.map { l -> l to pool.submit(Callable { fetch(l, now).orEmpty() }) }
            pool.shutdown()
            val list = jobs.flatMap { (l, job) ->
                job.get().filter { sportsNow(it, now) }.map { g ->
                    val live = g.state == "in"
                    val title = when {
                        !live -> "${label(ctx, g)} · ${clock(ctx, g.start)}"
                        g.cricket -> cricketScore(g)
                        g.name != null -> "${g.name} · ${g.shortDetail}"
                        else -> sportsScore(g)
                    }
                    NowGame(l.sport, l.league, l.name, g.id, title, g.away?.logo ?: g.logo, g.home?.logo, live, g.start,
                        sportsNowRank(live, g.name != null || l.sport == "cricket", g.shortDetail))
                }
            }.distinctBy { it.eventId }.sortedWith(compareBy<NowGame>({ it.rank }, { it.start }))
            nowCache = now to list
            main.post { done(list) }
        }.start()
    }

    // ---------- polling ----------

    /** Starts polling, or polls again now when it already runs (the settings can call it after a change). */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        task?.cancel(false)
        val gen = ++generation
        task = io.schedule(Runnable { poll(gen) }, 0, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        generation++
        task?.cancel(false)
        task = null
    }

    private fun poll(gen: Int) {
        if (gen != generation) return
        val ctx = app ?: return
        val now = System.currentTimeMillis()
        val before = items(now)
        val on = prefs(ctx).getBoolean(PREF_ON, true)
        val teams = if (on) followed(ctx) else emptyList()
        val leagues = if (on) followedLeagues(ctx).map { it.key }.toSet() else emptySet()
        val pins = if (on) pinned(ctx) else emptyList()
        val teamIds = teams.map { "${it.sport}/${it.league}/${it.teamId}" }.toSet()
        val pinIds = pins.map { it.eventId }.toSet()
        val needed = (teams.map { "${it.sport}/${it.league}" } + leagues + pins.map { "${it.sport}/${it.league}" }).distinct()
        val old = shown.associateBy { it.game.id }
        // One fetch per league, whether teams, the league itself or pins asked for it.
        val games = needed.flatMap { key ->
            val (sport, league) = key.split('/', limit = 2)
            val l = LEAGUES.firstOrNull { it.key == key } ?: League(sport, league, league, 0)
            // When a request fails the league keeps the games it had, so one network hiccup does not blank a live game.
            fetch(l, now) ?: shown.map { it.game }.filter { it.league == key }
        }.distinctBy { it.id }
        val picked = games.mapNotNull { g ->
            val team = "${g.league}/${g.away?.id}" in teamIds || "${g.league}/${g.home?.id}" in teamIds
            val source = sportsSource(g, g.id in pinIds, team, g.league in leagues, now) ?: return@mapNotNull null
            val o = old[g.id]
            // Only pinned games and followed teams open the island on a goal; a followed league's games stay quiet.
            // Cricket shows by name and its score moves every ball, so it would open the island on every poll.
            val scored = g.name == null && sportsScored(o?.game, g)
            val peek = if (scored && source != SPORTS_LEAGUE) now + PEEK_MS else o?.peekUntil ?: 0L
            // A final result keeps the end time it got when it was first seen final.
            val until = when {
                g.state != "post" -> Long.MAX_VALUE
                o != null && o.game.state == "post" -> o.until
                else -> sportsFinalUntil(now, g.start, o?.game?.state == "in")
            }
            Shown(g, source, if (scored) now else o?.changed ?: g.start, peek, until)
        }
        shown = picked
        // A pin goes two hours after its final, or at its own end time when the island never saw a final.
        val gone = pins.filter { p -> now >= minOf(p.until, picked.firstOrNull { it.game.id == p.eventId }?.until ?: Long.MAX_VALUE) }
            .map { it.eventId }.toSet()
        if (gone.isNotEmpty()) edit(ctx, PREF_GAMES) { set -> set.removeAll { PinnedGame.parse(it)?.eventId in gone } }
        if (items(now) != before) Island.changed()
        // A followed league's game counts for the poll interval before it shows, so it appears 30 minutes ahead on time.
        val watch = games.filter { it.league in leagues } + picked.map { it.game }
        // With nothing followed (or sports off) there is nothing to wait for; the settings call start() again.
        if (needed.isNotEmpty() && gen == generation) task = io.schedule(Runnable { poll(gen) }, sportsPollMs(watch, now), TimeUnit.MILLISECONDS)
    }

    /** A league's games around now, or null when a request fails. Also keeps them as the league's [lastBoard]. */
    private fun fetch(l: League, now: Long): List<SportsGame>? = try {
        val urls = when {
            l.league == PANEL -> listOf("$BASE/${l.sport}/$PANEL")
            l.teams -> sportsDays(now).map { "$BASE/${l.key}/scoreboard" + sportsQuery(l.query, it) }
            else -> listOf("$BASE/${l.key}/scoreboard" + sportsQuery(l.query, null))
        }
        urls.flatMap { url ->
            val j = islandJson(url)
            // The cricket panel holds one scoreboard per series under "scores"; a league's scoreboard is one.
            val boards = j.optJSONArray("scores")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: listOf(j)
            boards.flatMap { b ->
                val logo = b.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifEmpty { null }
                val events = b.optJSONArray("events")
                if (events == null) emptyList() else (0 until events.length()).mapNotNull { game(l, events.optJSONObject(it), logo) }
            }
        }.distinctBy { it.id }.also { lastBoard[l.key] = it }
    } catch (e: Exception) {
        android.util.Log.w("Plume", "sports scoreboard ${l.key} failed ($e)")
        null
    }

    private fun game(l: League, e: JSONObject?, leagueLogo: String?): SportsGame? {
        e ?: return null
        val c = e.optJSONArray("competitions")?.optJSONObject(0)
        val sides = c?.optJSONArray("competitors")
        var away: SportsSide? = null
        var home: SportsSide? = null
        for (i in 0 until (sides?.length() ?: 0)) {
            val s = sides?.optJSONObject(i) ?: continue
            // Racers, players and fighters are athletes, not teams; those events show by name.
            val t = s.optJSONObject("team") ?: continue
            val side = SportsSide(t.optString("id"), t.optString("abbreviation"),
                t.optString("shortDisplayName").ifEmpty { t.optString("displayName") }, s.optString("score"),
                t.optString("logo").ifEmpty { null }, t.optString("color").ifEmpty { null })
            if (s.optString("homeAway") == "home") home = side else away = side
        }
        val status = e.optJSONObject("status")
        val type = status?.optJSONObject("type") ?: return null
        // Cricket scores ("339 & 169/7 (58 ov)") are too long for the pill, and races, tournaments and fights have no
        // two teams, so those show by name with ESPN's summary ("Zimbabwe lead by 291 runs") or status ("Lap 34/71").
        // A golf event's own status has no detail; its round ("Round 3") is on the competition.
        val named = l.sport == "cricket" || away == null || home == null
        val cType = c?.optJSONObject("status")?.optJSONObject("type")
        fun text(k: String) = (if (named) status?.optString("summary").orEmpty() else "").ifEmpty { type.optString(k) }
            .ifEmpty { cType?.optString(k).orEmpty() }.ifEmpty { type.optString("description") }
        return SportsGame(e.optString("id").ifEmpty { return null }, l.key, sportsTime(e.optString("date")), type.optString("state"),
            type.optBoolean("completed"), text("shortDetail"), text("detail"), away, home,
            if (named) sportsShortName(e.optString("shortName").ifEmpty { e.optString("name") }).ifEmpty { return null } else null, leagueLogo,
            cricket = l.sport == "cricket" && away != null && home != null)
    }

    // ---------- items ----------

    override fun items(now: Long): List<IslandItem> {
        val ctx = app ?: return emptyList()
        return shown.mapNotNull { item(ctx, it, now) }
    }

    private fun item(ctx: Context, s: Shown, now: Long): IslandItem? {
        val g = s.game
        val key = "sports:" + g.id
        val live = g.state == "in"
        // A followed league's live game ranks with the upcoming games, so it never pushes a timer or the user's own
        // teams out of the pill.
        val kind = if (live && s.source != SPORTS_LEAGUE) IslandKind.SPORTS_LIVE else IslandKind.SPORTS
        val since = sportsSince(s.source, live, if (g.state == "pre") -g.start else s.changed)
        // Cricket names its match home side first ("ABT v HYD-R"): the pictures follow the order of the words.
        val (first, second) = sportsSides(g)
        val away = first?.logo ?: g.logo
        val home = second?.logo
        // Many teams (most domestic cricket) have no logo at ESPN: a badge with the team's letters and colour stands in.
        val (badge, badge2) = sportsBadges(first, second)
        return when {
            g.state == "pre" && sportsSoon(g.start, now) -> {
                val zone = ZoneId.systemDefault()
                val tomorrow = Instant.ofEpochMilli(g.start).atZone(zone).toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val sub = ctx.getString(if (tomorrow) R.string.isl_sports_tomorrow else R.string.isl_sports_today, clock(ctx, g.start))
                IslandItem(key = key, kind = kind, title = label(ctx, g), sub = sub, mono = false, imageUri = away,
                    image2Uri = home, badge = badge, badge2 = badge2, since = since,
                    description = g.name?.let { "$it, $sub" } ?: ctx.getString(R.string.isl_sports_desc_pre, g.away?.name, g.home?.name, sub))
            }
            live && g.cricket -> {
                val line = cricketLine(ctx, g)
                IslandItem(key = key, kind = kind, title = cricketScore(g), sub = line, mono = false, imageUri = away,
                    image2Uri = home, badge = badge, badge2 = badge2, since = since, peekUntil = s.peekUntil,
                    description = "${g.away?.name} ${g.away?.score}, ${g.home?.name} ${g.home?.score}, $line")
            }
            live -> IslandItem(key = key, kind = kind, title = g.name ?: sportsScore(g),
                sub = if (g.name != null) g.shortDetail else sportsDetail(g.shortDetail), mono = false, imageUri = away,
                image2Uri = home, badge = badge, badge2 = badge2, since = since, peekUntil = s.peekUntil,
                description = describe(ctx, g, if (g.name != null) g.detail else sportsDetail(g.detail)))
            // A postponed or cancelled game is "post" but not completed; it has no result to show.
            g.state == "post" && g.completed -> {
                val final = if (g.name != null) g.shortDetail else ctx.getString(R.string.isl_sports_final)
                IslandItem(key = key, kind = kind, title = if (g.cricket) cricketScore(g) else g.name ?: sportsScore(g), sub = final,
                    mono = false, imageUri = away, image2Uri = home, badge = badge, badge2 = badge2, since = since, until = s.until, peekUntil = s.peekUntil,
                    description = describe(ctx, g, final))
            }
            else -> null
        }
    }

    /** "IND at PHI", or the event's name when it shows by name. */
    private fun label(ctx: Context, g: SportsGame) = g.name ?: ctx.getString(R.string.isl_sports_at, g.away?.abbreviation, g.home?.abbreviation)

    /**
     * What is going on in a live cricket match, in words anyone can read: "ABT batting · 28.1 ov", then ESPN's
     * summary when it says more ("Zimbabwe lead by 291 runs"). ESPN keeps "X won toss" as the summary for the whole
     * match, so the toss shows only before the first ball.
     */
    private fun cricketLine(ctx: Context, g: SportsGame): String {
        val c = cricketState(g)
        return when {
            c.batting != null -> listOfNotNull(
                c.overs?.let { ctx.getString(R.string.isl_cricket_batting_overs, c.batting, it) } ?: ctx.getString(R.string.isl_cricket_batting, c.batting),
                c.note).joinToString(" · ")
            c.toss != null && !c.runs -> ctx.getString(R.string.isl_cricket_toss, c.toss)
            else -> c.note ?: g.shortDetail
        }
    }

    private fun describe(ctx: Context, g: SportsGame, state: String) = g.name?.let { "$it, $state" } ?: ctx.getString(R.string.isl_sports_desc_score,
        g.away?.name, g.away?.score.orEmpty().ifEmpty { "0" }, g.home?.name, g.home?.score.orEmpty().ifEmpty { "0" }, state)

    // The launcher's own time format when the user set one, else the TV's 12 or 24 hour setting.
    private fun clock(ctx: Context, time: Long): String {
        val pattern = prefs(ctx).getString(ClockFormat.TIME.key, null)?.takeIf(ClockFormat.TIME::accepts)
            ?: if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        return DateFormat.format(pattern, time).toString()
    }
}

// ---------- plain rules (no Android, no org.json), tested in IslandSportsTest ----------

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** Why a game shows, best first: the user pinned it, a followed team plays, or its league is followed. */
internal const val SPORTS_PIN = 0
internal const val SPORTS_TEAM = 1
internal const val SPORTS_LEAGUE = 2

/** One followed team, stored as "sport|league|teamId|abbreviation|name" so the settings can list it offline. */
internal data class FollowedTeam(val sport: String, val league: String, val teamId: String, val abbreviation: String, val name: String) {
    // A "|" in a name would break the entry, so it becomes "/".
    val entry get() = listOf(sport, league, teamId, abbreviation, name).joinToString("|") { it.replace('|', '/') }

    fun same(sport: String, league: String, teamId: String) = this.sport == sport && this.league == league && this.teamId == teamId

    companion object {
        fun parse(entry: String): FollowedTeam? {
            val p = entry.split('|')
            return if (p.size == 5 && p[2].isNotEmpty()) FollowedTeam(p[0], p[1], p[2], p[3], p[4]) else null
        }
    }
}

/** One pinned game, stored as "sport|league|eventId|untilMillis"; [until] is when the pin goes at the latest. */
internal data class PinnedGame(val sport: String, val league: String, val eventId: String, val until: Long) {
    val entry get() = "$sport|$league|$eventId|$until"

    companion object {
        fun parse(entry: String): PinnedGame? {
            val p = entry.split('|')
            val until = p.getOrNull(3)?.toLongOrNull() ?: return null
            return if (p.size == 4 && p[2].isNotEmpty()) PinnedGame(p[0], p[1], p[2], until) else null
        }
    }
}

internal data class SportsSide(val id: String, val abbreviation: String, val name: String, val score: String, val logo: String?,
    val color: String? = null)

/**
 * A game from a scoreboard. [league] is "sport/league"; [state] is ESPN's "pre", "in" or "post"; [start] is wall-clock
 * ms. An event with a [name] (a race, a tournament, a fight, a cricket match) shows by that name and its
 * [shortDetail] instead of a score; [away] and [home] may then be missing, and [logo] is the league's picture.
 */
internal data class SportsGame(
    val id: String, val league: String, val start: Long, val state: String, val completed: Boolean,
    val shortDetail: String, val detail: String, val away: SportsSide?, val home: SportsSide?,
    val name: String? = null, val logo: String? = null,
    /** A cricket match between two teams: it shows both scores, not ESPN's summary. */
    val cricket: Boolean = false,
)

/** ESPN's "2026-11-15T18:00Z" (no seconds) as wall-clock ms, or 0 when it does not read. */
internal fun sportsTime(iso: String): Long =
    try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (e: Exception) { 0L }

// ESPN's scoreboard days are US Eastern days.
private val espnZone = ZoneId.of("America/New_York")

/**
 * The scoreboard days to ask for: the ESPN days of eight hours ago and six hours ahead, so a long game that started
 * before ESPN's midnight and a game soon after it both come back, wherever the TV is. ESPN refuses a date range
 * ("dates=20261114-20261116" is an error for every team league), so each day is its own request: one most of the
 * day, two in the hours around ESPN's midnight. Without dates a football league returns its next matchday, days away.
 */
internal fun sportsDays(now: Long): List<String> = listOf(now - 8 * HOUR, now + 6 * HOUR)
    .map { Instant.ofEpochMilli(it).atZone(espnZone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) }.distinct()

/** The scoreboard query: the league's own [query] ("groups=80") and the [day], joined with "&". */
internal fun sportsQuery(query: String, day: String?): String =
    listOfNotNull(query.ifEmpty { null }, day?.let { "dates=$it" }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }

/**
 * Why [g] shows ([SPORTS_PIN], [SPORTS_TEAM] or [SPORTS_LEAGUE]), or null when it does not. A followed league shows
 * only its games that are live or start within 30 minutes; a league has too many games to show them all day.
 */
internal fun sportsSource(g: SportsGame, pinned: Boolean, team: Boolean, league: Boolean, now: Long): Int? = when {
    pinned -> SPORTS_PIN
    team -> SPORTS_TEAM
    league && (g.state == "in" || g.state == "pre" && g.start - now <= 30 * MINUTE) -> SPORTS_LEAGUE
    else -> null
}

// Wider than any wall-clock time, so the source and live steps of sportsSince never overlap.
private const val SINCE_STEP = 10_000_000_000_000L

/**
 * The item's since, which the island sorts newest first among items of the same kind: pinned games before followed
 * teams before followed leagues, live games before the rest, and then the larger [order] first. [order] is the time
 * of the last score change for a game that is on or over, and minus the start for a game to come, so the next one
 * comes first.
 */
internal fun sportsSince(source: Int, live: Boolean, order: Long) = order - source * 2 * SINCE_STEP - if (live) 0L else SINCE_STEP

/** A game for "Games now": live, or starting within three hours (or late by less than an hour). */
internal fun sportsNow(g: SportsGame, now: Long) = g.state == "in" || g.state == "pre" && g.start - now in -HOUR..3 * HOUR

/** The ids of the teams with a game today in [zone], or on now (a game that started late yesterday). */
internal fun sportsPlayingToday(games: List<SportsGame>, now: Long, zone: ZoneId): Set<String> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return games.filter { it.state == "in" || Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == today }
        .flatMap { listOfNotNull(it.away?.id, it.home?.id) }.toSet()
}

/** How long to wait before the next poll: soon while a game is on, less often the further away the games are. */
internal fun sportsPollMs(games: List<SportsGame>, now: Long): Long = when {
    games.any { it.state == "in" } -> 30_000L
    games.any { it.state == "pre" && it.start - now <= 2 * HOUR } -> 5 * MINUTE
    else -> 30 * MINUTE
}

/** A game that has not started shows from six hours before its start. */
internal fun sportsSoon(start: Long, now: Long) = start - now <= 6 * HOUR

/** Away on the left and home on the right, like the logos: "IND 39 – 37 PHI". */
internal fun sportsScore(g: SportsGame) =
    "${g.away?.abbreviation} ${g.away?.score.orEmpty().ifEmpty { "0" }} – ${g.home?.score.orEmpty().ifEmpty { "0" }} ${g.home?.abbreviation}"

/**
 * "9:00 - 2nd" reads better period first: "2nd · 9:00" (in the last minute basketball's clock is "34.5"). Anything
 * else ("Halftime", "Top 5th", "45'") stays as ESPN wrote it.
 */
internal fun sportsDetail(detail: String): String {
    val parts = detail.split(" - ")
    return if (parts.size == 2 && parts[0].matches(clockText)) "${parts[1]} · ${parts[0]}" else detail
}

private val clockText = Regex("\\d+(:\\d\\d)?(\\.\\d+)?")

/** The score moved since the last poll of a game that was on, so the island opens for a moment to show it. */
internal fun sportsScored(old: SportsGame?, new: SportsGame) =
    old != null && old.state == "in" && (old.away?.score != new.away?.score || old.home?.score != new.home?.score)

/**
 * When a final result goes: two hours after the island first saw it final. A game first seen already over (the TV
 * was off, or the launcher started late) has ended at the latest four hours after its start, so an old result does
 * not stay for two hours more.
 */
internal fun sportsFinalUntil(seenFinal: Long, start: Long, sawLive: Boolean) =
    (if (sawLive) seenFinal else minOf(seenFinal, start + 4 * HOUR)) + 2 * HOUR

/**
 * Where a game goes in Games now: a live match between two teams first (the thing most people look for), then live
 * events that last for hours or days (golf, a race weekend, cricket), then what starts soon. A suspended or delayed
 * event is not really on, so it waits with the upcoming ones.
 */
internal fun sportsNowRank(live: Boolean, longEvent: Boolean, detail: String?): Int = when {
    !live || detail?.contains("Suspend", true) == true || detail?.contains("Delay", true) == true -> 2
    longEvent -> 1
    else -> 0
}

/** An event's name without its sponsor ("Austin Championship pres. by X" is the Austin Championship). */
internal fun sportsShortName(name: String): String =
    name.replace(Regex("""\s+(pres\.|presented|powered|sponsored)\s+by\s+.*$""", RegexOption.IGNORE_CASE), "").trim()

// ---------- cricket ----------

private val oversText = Regex("""\(([\d.]+) ov""")

/** A cricket score without its overs: "339 & 169/7 (58 ov)" is "339 & 169/7". */
internal fun cricketRuns(score: String) = score.replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim()

/** The two sides in the order the match is named: home first for cricket ("ABT v HYD-R"), else away first. */
internal fun sportsSides(g: SportsGame): Pair<SportsSide?, SportsSide?> =
    if (g.cricket && g.home != null && g.name?.startsWith(g.home.abbreviation) == true) g.home to g.away else g.away to g.home

/**
 * "ABT 118/4 · HYD-R 121", in the order of the match's name; a side that has not batted shows its letters only. Each
 * side's letters and runs never part across two lines (no-break spaces).
 */
internal fun cricketScore(g: SportsGame) = sportsSides(g).toList().filterNotNull().joinToString(" · ") { s ->
    cricketRuns(s.score).let { if (it.isEmpty()) s.abbreviation else "${s.abbreviation} $it" }.replace(' ', '\u00A0')
}

/** A badge's letters: "HYD-R" is "HYD", at most four. */
internal fun badgeLetters(abbreviation: String) =
    abbreviation.split('-', ' ', '/').firstOrNull { it.length >= 2 }?.take(4) ?: abbreviation.filter { it.isLetterOrDigit() }.take(4)

/** Badge colours for a team ESPN gives no colour: calm and mid-dark, so white letters read on each. */
private val BADGE_COLOURS = listOf("3d6e9e", "8a4b6f", "4e7d5b", "9a6a2f", "5b5b9e", "2f7f86", "8e4a3f")

/**
 * The two sides' badges ("ABT|3d6e9e"). A side with no colour from ESPN gets one picked by its letters; the second
 * side never gets the first one's colour, so the two badges always look different.
 */
internal fun sportsBadges(first: SportsSide?, second: SportsSide?): Pair<String?, String?> {
    fun pick(s: SportsSide, skip: String?): String {
        s.color?.takeIf { it.length == 6 && it != skip }?.let { return it }
        val i = Math.floorMod(s.abbreviation.hashCode(), BADGE_COLOURS.size)
        return BADGE_COLOURS[i].takeIf { it != skip } ?: BADGE_COLOURS[(i + 1) % BADGE_COLOURS.size]
    }
    val a = first?.takeIf { it.abbreviation.isNotEmpty() }?.let { pick(it, null) }
    val b = second?.takeIf { it.abbreviation.isNotEmpty() }?.let { pick(it, a) }
    return a?.let { "${first.abbreviation}|$it" } to b?.let { "${second.abbreviation}|$it" }
}

/**
 * A live match as parts: who bats and their overs (the side whose score ends "(28.1 ov)"), who won the toss, and
 * ESPN's summary when it says more than the toss or the status ([note]). [runs]: a side has scored.
 */
internal data class CricketState(val batting: String?, val overs: String?, val toss: String?, val note: String?, val runs: Boolean)

internal fun cricketState(g: SportsGame): CricketState {
    val sides = listOfNotNull(g.away, g.home)
    val bat = sides.firstOrNull { oversText.containsMatchIn(it.score) }
    val overs = bat?.let { oversText.find(it.score)?.groupValues?.get(1) }
    val summary = g.shortDetail.trim()
    val toss = Regex("""^(.+?) won (the )?toss""", RegexOption.IGNORE_CASE).find(summary)?.groupValues?.get(1)
    val plain = toss != null || summary.isEmpty() || summary.equals("Live", true) || summary.startsWith("Starts", true)
    return CricketState(bat?.abbreviation, overs, toss, summary.takeUnless { plain }, sides.any { cricketRuns(it.score).isNotEmpty() })
}
