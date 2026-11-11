package dev.plume

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException

/** Version 1 transfers portable preferences only. Missing supported keys return to Plume defaults. */
internal object SettingsTransfer {
    private const val LIMIT = 65_536
    private val choices = mapOf(
        "theme" to setOf("light", "dark", "wallpaper", "auto"),
        "glass_style" to setOf("off", "frosted", "clear"),
        "icon_style" to setOf("mono", "colour", "banner", "bannermono"),
        "row_size" to setOf("standard", "large", "largest"),
        "row_align" to setOf("left", "centre", "right"),
        "names" to setOf("focus", "always", "never"),
        "row_show" to setOf("always", "down", "never"),
        "sort" to setOf("manual", "usage"),
        "clock_size" to setOf("38", "48", "60"),
        "clock_style" to setOf("light", "bold", "serif"),
        "weather" to setOf("off", "c", "f"),
        "wx_unit" to setOf("c", "f"),
    )
    private val switches = setOf("glass", "colour_icons", "app_anim", "row_drop", "cw_colour", "geo_manual")
    private val clockFormats = ClockFormat.entries.associateBy { it.key }
    private val keys = choices.keys + switches + clockFormats.keys + setOf("order", "row_hidden", "cw_off", "row_name", "top_bar",
        "wp_every", "geo_city", "geo_lat", "geo_lon")
    private val pkg = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    @Suppress("DEPRECATION")
    fun file(context: Context): File = File(context.externalMediaDirs.firstOrNull()
        ?: throw IOException("External media storage is unavailable"), "backup/plume-settings.json")

    fun portable(all: Map<String, *>): Map<String, Any> {
        val values = all.filterKeys { it in keys }.toMutableMap()
        // HDMI identifiers and automatic location results belong to this TV.
        (values["order"] as? String)?.let { order -> values["order"] = order.split(',').filter { pkg.matches(it) }.joinToString(",") }
        (values["row_hidden"] as? Set<*>)?.let { hidden -> values["row_hidden"] = hidden.filterIsInstance<String>().filter { pkg.matches(it) }.toSet() }
        if (values["geo_manual"] != true) for (key in listOf("geo_city", "geo_lat", "geo_lon")) values.remove(key)
        return validate(values)
    }

    /** Also used before any preference mutation; JSON numbers are converted to their declared preference types. */
    fun validate(values: Map<String, *>): Map<String, Any> = buildMap {
        for ((key, value) in values) {
            if (key !in SettingsTransfer.keys) continue
            val checked: Any = when {
                key in choices -> {
                    require(value is String && value in choices.getValue(key)) { "Unsupported $key" }
                    value
                }
                key in switches -> { require(value is Boolean) { "Invalid $key" }; value }
                key in clockFormats -> {
                    require(value is String && clockFormats.getValue(key).accepts(value)) { "Invalid $key" }
                    value
                }
                key == "geo_lat" || key == "geo_lon" -> {
                    require(value is Number) { "Invalid $key" }
                    val number = value.toDouble()
                    val max = if (key == "geo_lat") 90.0 else 180.0
                    require(number.isFinite() && number in -max..max) { "Invalid $key" }
                    number.toFloat()
                }
                key == "wp_every" -> {
                    require(value is Number && value.toDouble() in setOf(1.0, 5.0, 10.0, 15.0, 30.0, 60.0, 1440.0)) { "Invalid $key" }
                    value.toInt()
                }
                key == "row_hidden" || key == "cw_off" -> {
                    require(value is Collection<*> && value.size <= 512 && value.all { it is String && pkg.matches(it) }) { "Invalid $key" }
                    value.filterIsInstance<String>().toSet()
                }
                else -> {
                    require(value is String && value.length <= 16_384 && '\u0000' !in value) { "Invalid $key" }
                    if (key == "order") require(value.isEmpty() || value.split(',').let { it.size <= 512 && it.all(pkg::matches) }) { "Invalid order" }
                    if (key == "top_bar") require(value.split(',').all { it in setOf("wifi", "sources", "apps", "system", "plume", "power") }) { "Invalid top bar" }
                    value
                }
            }
            put(key, checked)
        }
        if (get("geo_manual") == true) require(listOf("geo_city", "geo_lat", "geo_lon").all { containsKey(it) }) { "Incomplete weather location" }
        else for (key in listOf("geo_city", "geo_lat", "geo_lon")) remove(key)
    }

    fun encode(all: Map<String, *>): ByteArray {
        val settings = JSONObject()
        portable(all).forEach { (key, value) -> settings.put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
        return JSONObject().put("format", "plume-settings").put("version", 1).put("settings", settings)
            .toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= LIMIT) { "Settings file is too large" } }
    }

    fun decode(bytes: ByteArray): Map<String, Any> {
        require(bytes.size <= LIMIT) { "Settings file is too large" }
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Settings file is too deeply nested" } }
                '}', ']' -> depth--
            }
        }
        val parser = JSONTokener(text)
        val root = parser.nextValue()
        require(root is JSONObject && parser.nextClean() == '\u0000') { "Invalid settings file" }
        require(root.opt("format") == "plume-settings" && root.opt("version") == 1) { "Unsupported settings file" }
        val settings = root.getJSONObject("settings")
        val values = settings.keys().asSequence().associateWith { key ->
            settings.get(key).let { value -> if (value is JSONArray) (0 until value.length()).map(value::get) else value }
        }
        return validate(values)
    }

    fun save(file: File, all: Map<String, *>) {
        val bytes = encode(all)
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("Cannot create backup folder")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun load(file: File): Map<String, Any> = AtomicFile(file).openRead().use { input ->
        val bytes = ByteArray(LIMIT + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        decode(bytes.copyOf(size))
    }

    fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val valid = validate(values)
        val before = prefs.all.filterKeys { it in keys || it == "geo_time" || it == "wx_time" }
        fun write(values: Map<String, *>): Boolean {
            val edit = prefs.edit()
            (keys + setOf("geo_time", "wx_time")).forEach(edit::remove)
            for ((key, value) in values) when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            return edit.commit()
        }
        if (!write(valid)) {
            val restored = write(before)
            throw IOException(if (restored) "Cannot save settings" else "Cannot save or restore settings")
        }
    }
}
