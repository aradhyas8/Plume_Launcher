package dev.plume

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object Tmdb {
    private val keyPattern = Regex("[a-fA-F0-9]{32}")
    private val imagePath = Regex("/[a-zA-Z0-9]+\\.jpg")

    private fun keyFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "tmdb-key"))

    fun validKey(key: String) = keyPattern.matches(key)

    fun key(context: Context): String? = try {
        keyFile(context).readFully().decodeToString().takeIf(::validKey)
    } catch (_: IOException) {
        null
    }

    fun saveKey(context: Context, key: String?) {
        val file = keyFile(context)
        val output = file.startWrite()
        try {
            output.write(key.orEmpty().encodeToByteArray())
            file.finishWrite(output)
        } catch (e: IOException) {
            file.failWrite(output)
            throw e
        }
    }

    fun acceptsKey(key: String) = request("authentication", key)?.optBoolean("success") == true

    data class Title(val id: Int, val type: String, val name: String, val originalName: String)

    fun match(titles: List<Title>, name: String, type: String?): Title? = titles.filter {
        it.id > 0 && (type == null || type == it.type) &&
            (name.trim().equals(it.name.trim(), ignoreCase = true) || name.trim().equals(it.originalName.trim(), ignoreCase = true))
    }.distinctBy { it.type to it.id }.singleOrNull()

    fun backdrop(title: String, type: String?, key: String): String? {
        val query = URLEncoder.encode(title, "UTF-8")
        val results = request("search/multi?query=$query", key)?.getJSONArray("results")
            ?: throw IOException("TMDB rejected the key")
        val titles = (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            val kind = item.optString("media_type")
            val field = when (kind) {
                "movie" -> "title"
                "tv" -> "name"
                else -> return@mapNotNull null
            }
            Title(item.optInt("id"), kind, item.optString(field), item.optString("original_$field"))
        }
        val found = match(titles, title, type) ?: return null
        val images = request("${found.type}/${found.id}/images?include_image_language=null", key)?.getJSONArray("backdrops")
            ?: throw IOException("TMDB rejected the key")
        return (0 until images.length()).asSequence()
            .map { images.getJSONObject(it) }
            .filter { it.isNull("iso_639_1") && ShowsWall.fits(it.optInt("width"), it.optInt("height")) }
            .filter { imagePath.matches(it.optString("file_path")) }
            .maxByOrNull { it.optDouble("vote_average", 0.0) }
            ?.let { "https://image.tmdb.org/t/p/original${it.getString("file_path")}" }
    }

    private fun request(path: String, key: String): JSONObject? {
        val separator = if ('?' in path) '&' else '?'
        val connection = URL("https://api.themoviedb.org/3/$path${separator}api_key=$key").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            return when (connection.responseCode) {
                200 -> connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                401, 403 -> null
                else -> throw IOException("TMDB request failed")
            }
        } finally {
            connection.disconnect()
        }
    }
}
