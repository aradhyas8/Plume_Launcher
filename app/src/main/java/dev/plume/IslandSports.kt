package dev.plume

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The island's sports source: the games of the teams the user follows, from ESPN's public site API (no key, no
 * account). The home screen calls [start] while it is shown and [stop] when it leaves; each poll asks one scoreboard
 * per league that has a followed team, and how soon the next poll comes depends on how close the games are (see
 * [sportsPollMs]). The settings page lists [LEAGUES], fetches a league's [teams] and calls [follow] and [unfollow].
 * Nothing about the user goes out: the requests carry only the league in the path and a generic User-Agent.
 */
internal object IslandSports : IslandSource {
    /** A league the settings can offer. [query] is added to its scoreboard request. */
    class League(val sport: String, val league: String, val name: String, val query: String = "")

    data class Team(val id: String, val name: String, val abbreviation: String, val logo: String?)

    // The league names are proper names, so they stay plain constants rather than translated resources. Cricket is
    // left out: ESPN has no teams list for it ("League not found"), and its scoreboard is empty outside the season.
    val LEAGUES = listOf(
        League("football", "nfl", "NFL"),
        League("basketball", "nba", "NBA"),
        League("basketball", "wnba", "WNBA"),
        League("baseball", "mlb", "MLB"),
        League("hockey", "nhl", "NHL"),
        League("soccer", "usa.1", "MLS"),
        League("soccer", "eng.1", "Premier League"),
        League("soccer", "esp.1", "La Liga"),
        League("soccer", "ger.1", "Bundesliga"),
        League("soccer", "ita.1", "Serie A"),
        League("soccer", "fra.1", "Ligue 1"),
        League("soccer", "uefa.champions", "Champions League"),
        // Without groups=80 the college scoreboard has only the ranked games. ponytail: a game between two teams
        // outside the top division (FBS) still does not show; ask for groups=81 too if someone follows such a team.
        League("football", "college-football", "College Football", "groups=80"),
    )

    const val PREF_ON = "isl_sports"
    const val PREF_TEAMS = "isl_teams"

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val PEEK_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private val io by lazy { Executors.newSingleThreadScheduledExecutor() }
    @Volatile private var app: Context? = null
    @Volatile private var task: ScheduledFuture<*>? = null
    // Each start() takes a new number, so a poll from an earlier start that is still running does not schedule a
    // second chain of polls.
    @Volatile private var generation = 0

    /** A game as the island last saw it, with when it peeks and when it goes, kept across polls. */
    private class Shown(val game: SportsGame, val peekUntil: Long, val until: Long)

    @Volatile private var shown: List<Shown> = emptyList()

    // ---------- followed teams (for the settings page; no network) ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun followed(ctx: Context): List<FollowedTeam> =
        prefs(ctx).getStringSet(PREF_TEAMS, null).orEmpty().mapNotNull(FollowedTeam::parse).sortedBy { it.name }

    fun follow(ctx: Context, sport: String, league: String, team: Team) {
        val entry = FollowedTeam(sport, league, team.id, team.abbreviation, team.name).entry
        editTeams(ctx) { set -> set.removeAll { FollowedTeam.parse(it)?.same(sport, league, team.id) == true }; set += entry }
    }

    fun unfollow(ctx: Context, sport: String, league: String, teamId: String) =
        editTeams(ctx) { set -> set.removeAll { FollowedTeam.parse(it)?.same(sport, league, teamId) == true } }

    // The set getStringSet returns must not be changed in place, so the edit works on a copy.
    private fun editTeams(ctx: Context, change: (MutableSet<String>) -> Unit) {
        val set = HashSet(prefs(ctx).getStringSet(PREF_TEAMS, null).orEmpty())
        change(set)
        prefs(ctx).edit().putStringSet(PREF_TEAMS, set).apply()
    }

    /** A league's teams by name, for the settings page. [done] runs on the main thread, with an empty list on failure. */
    fun teams(sport: String, league: String, done: (List<Team>) -> Unit) {
        // Its own thread, so the list does not wait behind a slow poll.
        Thread {
            val list = try {
                // The default page holds only 50 teams; college football has over 700.
                val teams = getJson("$BASE/$sport/$league/teams?limit=1000").getJSONArray("sports").getJSONObject(0)
                    .getJSONArray("leagues").getJSONObject(0).getJSONArray("teams")
                (0 until teams.length()).mapNotNull { i ->
                    val t = teams.optJSONObject(i)?.optJSONObject("team") ?: return@mapNotNull null
                    val id = t.optString("id").ifEmpty { return@mapNotNull null }
                    Team(id, t.optString("displayName"), t.optString("abbreviation"),
                        t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifEmpty { null })
                }.sortedBy { it.name }
            } catch (e: Exception) {
                android.util.Log.w("Plume", "sports teams $sport/$league failed ($e)")
                emptyList()
            }
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
        val teams = if (prefs(ctx).getBoolean(PREF_ON, true)) followed(ctx) else emptyList()
        val old = shown.associateBy { it.game.id }
        val games = teams.groupBy { it.sport to it.league }.flatMap { (key, list) ->
            val (sport, league) = key
            val ids = list.map { it.teamId }.toSet()
            val query = LEAGUES.firstOrNull { it.sport == sport && it.league == league }?.query.orEmpty()
            // When a request fails the league keeps the games it had, so one network hiccup does not blank a live game.
            val fresh = scoreboard(sport, league, query) ?: shown.map { it.game }.filter { it.league == "$sport/$league" }
            fresh.filter { it.away.id in ids || it.home.id in ids }
        }.distinctBy { it.id }
        shown = games.map { g ->
            val o = old[g.id]
            val peek = if (sportsScored(o?.game, g)) now + PEEK_MS else o?.peekUntil ?: 0L
            // A final result keeps the end time it got when it was first seen final.
            val until = when {
                g.state != "post" -> Long.MAX_VALUE
                o != null && o.game.state == "post" -> o.until
                else -> sportsFinalUntil(now, g.start, o?.game?.state == "in")
            }
            Shown(g, peek, until)
        }
        if (items(now) != before) Island.changed()
        // With nothing followed (or sports off) there is nothing to wait for; the settings call start() again.
        if (teams.isNotEmpty() && gen == generation) task = io.schedule(Runnable { poll(gen) }, sportsPollMs(games, now), TimeUnit.MILLISECONDS)
    }

    private fun scoreboard(sport: String, league: String, query: String): List<SportsGame>? = try {
        val events = getJson("$BASE/$sport/$league/scoreboard" + if (query.isEmpty()) "" else "?$query").optJSONArray("events")
        if (events == null) emptyList() else (0 until events.length()).mapNotNull { game("$sport/$league", events.optJSONObject(it)) }
    } catch (e: Exception) {
        android.util.Log.w("Plume", "sports scoreboard $sport/$league failed ($e)")
        null
    }

    private fun game(league: String, e: JSONObject?): SportsGame? {
        e ?: return null
        val sides = e.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: return null
        var away: SportsSide? = null
        var home: SportsSide? = null
        for (i in 0 until sides.length()) {
            val s = sides.optJSONObject(i) ?: continue
            val t = s.optJSONObject("team") ?: continue
            val side = SportsSide(t.optString("id"), t.optString("abbreviation"),
                t.optString("shortDisplayName").ifEmpty { t.optString("displayName") }, s.optString("score"),
                t.optString("logo").ifEmpty { null })
            if (s.optString("homeAway") == "home") home = side else away = side
        }
        val type = e.optJSONObject("status")?.optJSONObject("type") ?: return null
        return SportsGame(e.optString("id"), league, sportsTime(e.optString("date")), type.optString("state"),
            type.optBoolean("completed"), type.optString("shortDetail"), type.optString("detail"),
            away ?: return null, home ?: return null)
    }

    // Only the generic User-Agent goes with the request: no account, no device id, no location.
    private fun getJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "tvhome-island")
        }
        return try {
            JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            c.disconnect()
        }
    }

    // ---------- items ----------

    override fun items(now: Long): List<IslandItem> {
        val ctx = app ?: return emptyList()
        return shown.mapNotNull { item(ctx, it, now) }
    }

    private fun item(ctx: Context, s: Shown, now: Long): IslandItem? {
        val g = s.game
        val key = "sports:" + g.id
        return when {
            g.state == "pre" && sportsSoon(g.start, now) -> {
                val zone = ZoneId.systemDefault()
                val tomorrow = Instant.ofEpochMilli(g.start).atZone(zone).toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val sub = ctx.getString(if (tomorrow) R.string.isl_sports_tomorrow else R.string.isl_sports_today, clock(ctx, g.start))
                IslandItem(key, IslandKind.SPORTS, ctx.getString(R.string.isl_sports_at, g.away.abbreviation, g.home.abbreviation),
                    sub, mono = false, imageUri = g.away.logo, image2Uri = g.home.logo, since = g.start,
                    description = ctx.getString(R.string.isl_sports_desc_pre, g.away.name, g.home.name, sub))
            }
            g.state == "in" -> IslandItem(key, IslandKind.SPORTS_LIVE, sportsScore(g), sportsDetail(g.shortDetail),
                mono = false, imageUri = g.away.logo, image2Uri = g.home.logo, since = g.start, peekUntil = s.peekUntil,
                description = describe(ctx, g, sportsDetail(g.detail)))
            // A postponed or cancelled game is "post" but not completed; it has no result to show.
            g.state == "post" && g.completed -> {
                val final = ctx.getString(R.string.isl_sports_final)
                IslandItem(key, IslandKind.SPORTS, sportsScore(g), final, mono = false, imageUri = g.away.logo,
                    image2Uri = g.home.logo, since = g.start, until = s.until, peekUntil = s.peekUntil,
                    description = describe(ctx, g, final))
            }
            else -> null
        }
    }

    private fun describe(ctx: Context, g: SportsGame, state: String) = ctx.getString(R.string.isl_sports_desc_score,
        g.away.name, g.away.score.ifEmpty { "0" }, g.home.name, g.home.score.ifEmpty { "0" }, state)

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

internal data class SportsSide(val id: String, val abbreviation: String, val name: String, val score: String, val logo: String?)

/** A game from a scoreboard. [league] is "sport/league"; [state] is ESPN's "pre", "in" or "post"; [start] is wall-clock ms. */
internal data class SportsGame(
    val id: String, val league: String, val start: Long, val state: String, val completed: Boolean,
    val shortDetail: String, val detail: String, val away: SportsSide, val home: SportsSide,
)

/** ESPN's "2026-11-15T18:00Z" (no seconds) as wall-clock ms, or 0 when it does not read. */
internal fun sportsTime(iso: String): Long =
    try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (e: Exception) { 0L }

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
    "${g.away.abbreviation} ${g.away.score.ifEmpty { "0" }} – ${g.home.score.ifEmpty { "0" }} ${g.home.abbreviation}"

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
    old != null && old.state == "in" && (old.away.score != new.away.score || old.home.score != new.home.score)

/**
 * When a final result goes: two hours after the island first saw it final. A game first seen already over (the TV
 * was off, or the launcher started late) has ended at the latest four hours after its start, so an old result does
 * not stay for two hours more.
 */
internal fun sportsFinalUntil(seenFinal: Long, start: Long, sawLive: Boolean) =
    (if (sawLive) seenFinal else minOf(seenFinal, start + 4 * HOUR)) + 2 * HOUR
