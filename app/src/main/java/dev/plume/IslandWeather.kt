package dev.plume

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/**
 * Weather for the island: only rain or snow starting or stopping within the hour. The top bar already shows the
 * weather, so the island never repeats it; it speaks only when something is about to change. One Open-Meteo request
 * every 30 minutes while the home screen is shown, with the same coordinates the top bar's weather already uses;
 * nothing else leaves the TV. Switch: "isl_wx_rain", off by default. The rules themselves are the plain functions at
 * the end of this file, so they can be tested without Android.
 */
internal object IslandWeather : IslandSource {
    private const val STALE = 25 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    @Volatile private var forecast: Forecast? = null
    private var tried = 0L
    private var triedFor: Triple<Float, Float, Boolean>? = null

    // The rain item peeks once per event. The key stays "wx:rain" throughout, so the event is remembered here: it is
    // the same event while it stays a start (or a stop) of the same rain or snow, even as the minutes count down.
    private var seen: String? = null
    private var seenAt = 0L

    /** What one fetch gave, times in wall-clock milliseconds. Temperatures are in the unit asked for, [fahrenheit] or not. */
    private class Forecast(
        val time: Long, val fahrenheit: Boolean, val lat: Float, val lon: Float,
        val slots: List<Long>, val mm: List<Double>, val snow: List<Double>,
        val days: List<Long>, val code: List<Int>, val max: List<Double>, val min: List<Double>, val pop: List<Int>,
    )

    // Ticks every 30 minutes but fetches when the last fetch is 25 minutes old, so a tick that lands a little early
    // still fetches instead of waiting a whole hour.
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 30 * 60_000L)
        }
    }

    /** Call when the home screen is shown. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Call when the home screen is hidden. The last forecast stays in memory. */
    fun stop() {
        main.removeCallbacks(tick)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    // "weather" is off/c/f; "wx_unit" keeps the unit while the top bar's weather is off. The island follows it either way.
    private fun fahrenheit(p: SharedPreferences): Boolean {
        val w = p.getString("weather", "off")
        return (if (w == "off") p.getString("wx_unit", "c") else w) == "f"
    }

    /** Main thread only. Fetches in the background when the forecast is old, missing, or in the other unit. */
    private fun refresh() {
        val ctx = app ?: return
        val p = prefs(ctx)
        if (!enabled(p)) return
        val lat = p.getFloat("geo_lat", 0f)
        val lon = p.getFloat("geo_lon", 0f)
        val f = forecast
        val wantF = fahrenheit(p)
        val now = System.currentTimeMillis()
        if (f != null && f.fahrenheit == wantF && f.lat == lat && f.lon == lon && now - f.time < STALE) return
        // items() asks for a refetch when the unit changed; this keeps that to one request a minute, even when it fails.
        val request = Triple(lat, lon, wantF)
        if (request == triedFor && now - tried < 60_000L) return
        triedFor = request
        tried = now
        io.execute {
            if (!enabled(p)) return@execute
            try {
                forecast = fetch(lat, lon, wantF)
                Island.changed()
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "island weather failed ($e)") // keep the last forecast; next tick retries
            }
        }
    }

    private fun enabled(p: SharedPreferences) =
        p.getBoolean("isl_on", true) && p.getBoolean("isl_wx_rain", false) && p.contains("geo_lat")

    private fun fetch(lat: Float, lon: Float, f: Boolean): Forecast {
        // 12 quarter hours are the next 3 hours. Unix times avoid parsing local dates; "timezone=auto" still makes the
        // days start at the place's midnight.
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&minutely_15=precipitation,snowfall&forecast_minutely_15=12" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2&timeformat=unixtime" + if (f) "&temperature_unit=fahrenheit" else ""
        val j = islandJson(url)
        val m = j.getJSONObject("minutely_15")
        val d = j.getJSONObject("daily")
        fun JSONArray.times() = List(length()) { getLong(it) * 1000 }
        // Open-Meteo sends null where it has no value: no rain, and no temperature (NaN, which the rules skip).
        fun JSONArray.doubles(none: Double) = List(length()) { optDouble(it, none) }
        fun JSONArray.ints() = List(length()) { optInt(it, -1) }
        val w = Forecast(
            System.currentTimeMillis(), f, lat, lon,
            m.getJSONArray("time").times(), m.getJSONArray("precipitation").doubles(0.0), m.getJSONArray("snowfall").doubles(0.0),
            d.getJSONArray("time").times(), d.getJSONArray("weather_code").ints(),
            d.getJSONArray("temperature_2m_max").doubles(Double.NaN), d.getJSONArray("temperature_2m_min").doubles(Double.NaN),
            d.getJSONArray("precipitation_probability_max").ints(),
        )
        // The rules index these lists together; a short list here would crash the home screen later.
        check(w.mm.size == w.slots.size && w.snow.size == w.slots.size) { "minutely_15 lengths differ" }
        check(listOf(w.code, w.max, w.min, w.pop).all { it.size == w.days.size }) { "daily lengths differ" }
        return w
    }

    override fun items(now: Long): List<IslandItem> {
        val ctx = app ?: return emptyList()
        val f = forecast ?: return emptyList()
        val p = prefs(ctx)
        if (!enabled(p)) return emptyList()
        if (f.lat != p.getFloat("geo_lat", 0f) || f.lon != p.getFloat("geo_lon", 0f) || f.fahrenheit != fahrenheit(p)) {
            refresh()
            return emptyList()
        }
        return listOfNotNull(rain(ctx, f, now))
    }

    private fun rain(ctx: Context, f: Forecast, now: Long): IslandItem? {
        val e = wxPrecip(f.slots, f.mm, f.snow, now)
        val id = e?.let { "${it.stops}/${it.snow}" }
        if (id != seen) {
            seen = id
            seenAt = now
        }
        e ?: return null
        val title = ctx.getString(
            when {
                e.stops && e.snow -> R.string.isl_wx_snow_stops
                e.stops -> R.string.isl_wx_rain_stops
                e.snow -> R.string.isl_wx_snow_in
                else -> R.string.isl_wx_rain_in
            },
            wxMinutes(e.at, now),
        )
        val sub = if (e.stops) null else {
            val kind = ctx.getString(
                if (e.snow) intArrayOf(R.string.isl_wx_light_snow, R.string.isl_wx_moderate_snow, R.string.isl_wx_heavy_snow)[e.level]
                else intArrayOf(R.string.isl_wx_light_rain, R.string.isl_wx_moderate_rain, R.string.isl_wx_heavy_rain)[e.level]
            )
            // DateFormat.getTimeFormat follows the TV's 12 or 24 hour setting.
            e.endsAt?.let { ctx.getString(R.string.isl_wx_until, kind, DateFormat.getTimeFormat(ctx).format(Date(it))) } ?: kind
        }
        // The minutes count down, so the island asks again while it shows this.
        return IslandItem(
            "wx:rain", IslandKind.WEATHER, title, sub,
            icon = if (e.snow) R.drawable.ms_weather_snowy_fill else R.drawable.ms_rainy_fill, mono = true,
            tick = 60_000L, since = seenAt, until = e.at, peekUntil = seenAt + 6_000L,
        )
    }


}

// ---------- the rules, plain Kotlin ----------

private const val WX_SLOT = 15 * 60_000L
private const val WX_HOUR = 60 * 60_000L

// Less than this in a quarter hour counts as dry: Open-Meteo reports traces like 0.02 mm that nobody would call rain.
private const val WX_WET = 0.1

/**
 * Rain or snow that starts or [stops] at [at]. [level] is 0 light, 1 moderate, 2 heavy. [endsAt] is when rain that
 * starts is over again, or null when it lasts past the forecast.
 */
internal class WxPrecip(val stops: Boolean, val at: Long, val snow: Boolean, val level: Int, val endsAt: Long?)

/**
 * The change coming within the hour, from quarter-hour slots starting at [times] with [mm] of precipitation and
 * [snowCm] of snow each: dry now and wet soon, or wet now and dry soon. Null when nothing changes, or when [now] is
 * outside the slots (an old forecast says nothing about now).
 */
internal fun wxPrecip(times: List<Long>, mm: List<Double>, snowCm: List<Double>, now: Long): WxPrecip? {
    val i = times.indexOfLast { it <= now }
    if (i < 0 || now >= times[i] + WX_SLOT) return null
    fun wet(k: Int) = mm[k] >= WX_WET
    val soon = (i + 1 until times.size).filter { times[it] - now <= WX_HOUR }
    return if (!wet(i)) {
        val j = soon.firstOrNull { wet(it) } ?: return null
        val end = (j + 1 until times.size).firstOrNull { !wet(it) }
        WxPrecip(false, times[j], snowCm[j] > 0, wxLevel(mm[j]), end?.let { times[it] })
    } else {
        val j = soon.firstOrNull { !wet(it) } ?: return null
        WxPrecip(true, times[j], snowCm[i] > 0, wxLevel(mm[i]), null)
    }
}

/** Intensity from millimetres in a quarter hour: 0 light, 1 moderate, 2 heavy. */
internal fun wxLevel(mm: Double) = when {
    mm < 0.5 -> 0
    mm < 2.0 -> 1
    else -> 2
}

/** Whole minutes until [at], rounded up, so it never says "in 0 min" before it happens. */
internal fun wxMinutes(at: Long, now: Long) = ((at - now + 59_999) / 60_000).toInt()

/** A WMO weather code's icon, for the top bar's weather and the island's ([day]: the sun, else the moon). */
internal fun weatherIcon(code: Int, day: Boolean) = when (code) {
    0 -> if (day) R.drawable.ms_light_mode_fill else R.drawable.ms_dark_mode_fill
    1, 2 -> if (day) R.drawable.ms_partly_cloudy_day_fill else R.drawable.ms_partly_cloudy_night_fill
    45, 48 -> R.drawable.ms_foggy_fill
    in 51..67, in 80..82 -> R.drawable.ms_rainy_fill
    in 71..77, 85, 86 -> R.drawable.ms_weather_snowy_fill
    in 95..99 -> R.drawable.ms_thunderstorm_fill
    else -> R.drawable.ms_cloud_fill
}
