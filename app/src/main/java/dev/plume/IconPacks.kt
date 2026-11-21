package dev.plume

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.sqrt

internal fun iconComponent(value: String?): String? {
    if (value != null && (value.startsWith("ComponentInfo{") != value.endsWith('}'))) return null
    val text = value?.removePrefix("ComponentInfo{")?.removeSuffix("}") ?: return null
    val parts = text.split('/')
    if (parts.size != 2 || !ICON_PACKAGE.matches(parts[0])) return null
    val activity = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
    if (!ICON_ACTIVITY.matches(activity)) return null
    return "${parts[0]}/$activity"
}

private val ICON_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
private val ICON_ACTIVITY = Regex("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*")
private val ICON_DRAWABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Keep only installed packages; package fallback requires a single unambiguous drawable. */
internal class IconPackIndex(private val packages: Set<String>) {
    private val exact = LinkedHashMap<String, String>()
    private val byPackage = HashMap<String, MutableSet<String>>()
    var entries = 0
        private set

    fun add(component: String?, drawable: String?) {
        val key = iconComponent(component) ?: return
        val name = drawable?.removePrefix("@drawable/")?.takeIf { ICON_DRAWABLE.matches(it) } ?: return
        entries++
        val pkg = key.substringBefore('/')
        if (pkg !in packages) return
        exact.putIfAbsent(key, name)
        byPackage.getOrPut(pkg) { HashSet() }.add(name)
    }

    fun resolve(component: String): String? {
        val key = iconComponent(component) ?: return null
        return exact[key] ?: byPackage[key.substringBefore('/')]?.singleOrNull()
    }
}

/** Static ADW-style packs share one reader. No pack-specific packages or launch intents. */
@SuppressLint("DiscouragedApi")
internal object IconPacks {
    private const val XML_LIMIT = 8 * 1024 * 1024
    private const val BITMAP_LIMIT = 8 * 1024 * 1024 // ponytail: shared by all matched icons; per-icon cache if packs get huge
    data class Choice(val pkg: String, val label: String)
    class Pack(val pkg: String, val label: String, val icons: Map<String, Bitmap>) {
        fun icon(resources: Resources, component: String): Drawable? {
            val bitmap = icons[component] ?: return null
            val image = BitmapDrawable(resources, bitmap)
            // Thin white or black symbols need a contrasting backing on both light and glass tiles.
            var light = 0.0
            var weight = 0.0
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                val c = bitmap.getPixel(x, y)
                val alpha = Color.alpha(c) / 255.0
                light += (Color.red(c) * .2126 + Color.green(c) * .7152 + Color.blue(c) * .0722) / 255 * alpha
                weight += alpha
            }
            if (weight == 0.0 || light / weight in 0.2..0.8) return image
            val back = GradientDrawable().apply {
                setColor(if (light / weight > .8) 0xFF202124.toInt() else 0xFFF1F3F4.toInt())
                cornerRadius = minOf(bitmap.width, bitmap.height) * .12f
                setSize(bitmap.width, bitmap.height)
            }
            return LayerDrawable(arrayOf(back, image))
        }

        /** Pack art that is already banner-shaped (wide), drawn as is; null for square icons. */
        fun banner(resources: Resources, component: String): Drawable? =
            icons[component]?.takeIf { it.width * 10 >= it.height * 14 }?.let { BitmapDrawable(resources, it) }
    }

    fun intents() = listOf("org.adw.launcher.THEMES", "org.adw.ActivityStarter.THEMES", "com.novalauncher.THEME",
        "ch.deletescape.lawnchair.ICONPACK").map { Intent(it) } +
        Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")

    fun discover(context: Context): List<Choice> {
        val pm = context.packageManager
        return intents().flatMap { pm.queryIntentActivities(it, 0) }.distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                try {
                    read(pm.getResourcesForApplication(pkg), pkg, emptySet())
                    Choice(pkg, info.loadLabel(pm).toString())
                } catch (_: Exception) { null }
            }.sortedBy { it.label.lowercase() }
    }

    fun stamp(context: Context, pkg: String?): String {
        if (pkg == null) return ""
        return try {
            val pm = context.packageManager
            if (!pm.getApplicationInfo(pkg, 0).enabled) "$pkg:unavailable"
            else "$pkg:${pm.getPackageInfo(pkg, 0).lastUpdateTime}:${context.resources.displayMetrics.densityDpi}"
        } catch (_: Exception) { "$pkg:unavailable" }
    }

    /** IO thread only. The complete snapshot has at most 8 MB of pixels; wide artwork gets the same area as a square icon. */
    fun load(context: Context, pkg: String, components: List<String>): Pack {
        val pm = context.packageManager
        require(pm.getApplicationInfo(pkg, 0).enabled)
        val resources = pm.getResourcesForApplication(pkg)
        val index = read(resources, pkg, components.map { it.substringBefore('/') }.toSet())
        val matches = components.distinct().mapNotNull { component -> index.resolve(component)?.let { component to it } }
        val area = minOf(384 * 384, BITMAP_LIMIT / 4 / maxOf(1, matches.size))
        val icons = matches.mapNotNull { (component, name) ->
            try {
                val id = resources.getIdentifier(name, "drawable", pkg)
                if (id == 0) null else raster(resources, id, area)?.let { component to it }
            } catch (_: Exception) { null }
        }.toMap()
        return Pack(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), icons)
    }

    private fun raster(resources: Resources, id: Int, area: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(resources, id, bounds)
        var decoded: Bitmap? = null
        val drawable = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            if (bounds.outWidth > 8192 || bounds.outHeight > 8192 || bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) return null
            var sample = 1
            while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > area * 4L) sample *= 2
            val bitmap = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }) ?: return null
            decoded = bitmap
            BitmapDrawable(resources, bitmap)
        } else resources.getDrawable(id, null)
        val fallback = sqrt(area.toDouble()).toInt()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallback
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallback
        val scale = minOf(sqrt(area.toDouble() / (w.toLong() * h)).toFloat(), 960f / maxOf(w, h), 1f)
        val bitmap = Bitmap.createBitmap(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        } finally { decoded?.recycle() }
        return bitmap
    }

    private fun read(resources: Resources, pkg: String, packages: Set<String>): IconPackIndex {
        for (type in listOf("xml", "raw", "assets")) try {
            if (type == "assets") return resources.assets.open("appfilter.xml").use { parse(it, packages) }
            val id = resources.getIdentifier("appfilter", type, pkg)
            if (id == 0) continue
            if (type == "xml") return resources.getXml(id).use { parse(it, packages) }
            return resources.openRawResource(id).use { parse(it, packages) }
        } catch (_: Exception) { /* Another supported location can contain a valid mapping. */ }
        throw IllegalArgumentException("No readable static appfilter")
    }

    internal fun parse(input: InputStream, packages: Set<String>): IconPackIndex {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= XML_LIMIT) { "Icon mapping is too large" }
            bytes.write(buffer, 0, count)
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(ByteArrayInputStream(bytes.toByteArray()), null)
        return parse(parser, packages)
    }

    internal fun parse(parser: XmlPullParser, packages: Set<String>): IconPackIndex {
        val index = IconPackIndex(packages)
        var tokens = 0
        var root = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            require(++tokens <= 300_000 && parser.depth <= 16) { "Icon mapping is too complex" }
            require(parser.eventType != XmlPullParser.DOCDECL) { "Document declarations are not supported" }
            if (parser.eventType == XmlPullParser.START_TAG) {
                require(parser.attributeCount <= 32)
                for (i in 0 until parser.attributeCount) require(parser.getAttributeValue(i).length <= 2048)
                if (parser.depth == 1) { require(parser.name == "resources"); root = true }
                if (parser.depth == 2 && parser.name == "item") {
                    index.add(parser.getAttributeValue(null, "component"), parser.getAttributeValue(null, "drawable"))
                    require(index.entries <= 100_000)
                }
            }
            parser.nextToken()
        }
        require(root && index.entries > 0) { "No static icon mappings" }
        return index
    }
}
