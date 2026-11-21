package dev.plume

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

class IconPackFixtureActivity : Activity()

/** Uses an unknown test package, real Android resources, and the same reader used for every installed pack. */
class IconPacksCheck(private val runner: Instrumentation) {
    fun run() {
        val context = runner.targetContext
        val fixture = runner.context
        val component = "dev.plume.test/dev.plume.IconPackFixtureActivity"
        val packages = setOf("tv.fixture")
        val xml = "<resources><item component=\"ComponentInfo{tv.fixture/.Main}\" drawable=\"icon\"/></resources>"
        fun parse(text: String) = text.byteInputStream().use { IconPacks.parse(it, packages) }
        check(parse(xml).resolve("tv.fixture/.Main") == "icon")
        for (bad in listOf("", "<wrong/>", "<resources/>", xml.dropLast(2),
            "<!DOCTYPE resources [<!ENTITY file SYSTEM 'file:///does-not-exist'>]>$xml",
            "<resources><item component=\"tv.fixture/.Main\" drawable=\"${"x".repeat(2049)}\"/></resources>")) {
            var rejected = false
            try { parse(bad) } catch (_: Exception) { rejected = true }
            check(rejected) { "Accepted invalid mapping" }
        }
        val compiled = fixture.resources.getIdentifier("appfilter", "xml", fixture.packageName)
        fixture.resources.getXml(compiled).use {
            check(IconPacks.parse(it, packages).resolve("tv.fixture/.Main") == "pack_test_wide")
        }
        check(IconPacks.discover(context).any { it.pkg == fixture.packageName }) { "Unknown fixture pack was not discovered" }
        val pack = IconPacks.load(context, fixture.packageName, listOf(component, "tv.fixture/tv.fixture.Main", "tv.missing/tv.missing.Main"))
        check(pack.icons.size == 2)
        check(pack.icons.values.sumOf { it.byteCount } <= 8 * 1024 * 1024)
        val wide = pack.icons.getValue(component)
        check(kotlin.math.abs(wide.width * 9 - wide.height * 16) <= 16) { "Wide artwork lost its aspect ratio" }
        check(pack.banner(context.resources, component) != null) { "Wide artwork is not used as a banner" }
        check(Color.alpha(wide.getPixel(0, 0)) == 0) { "Lost transparency" }
        check(pack.icon(context.resources, component) is LayerDrawable) { "White artwork lacks contrast backing" }
        check(pack.icon(context.resources, "tv.absent/.Main") == null)
        check(IconPacks.stamp(context, "tv.nonexistent.pack").endsWith(":unavailable"))

        // Every external TV pack uses exactly the same resource reader; install them on the test device first.
        for (pkg in listOf("dev.projectivy.blueprint.app", "com.hqnscl.minimalist.app", "tv.corebuilds.iconpack")) {
            if (IconPacks.discover(context).none { it.pkg == pkg }) continue
            val result = IconPacks.load(context, pkg, listOf("com.netflix.ninja/com.netflix.ninja.MainActivity",
                "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity",
                "org.videolan.vlc/org.videolan.vlc.StartActivity"))
            check(result.icons.isNotEmpty()) { "No TV artwork resolved from $pkg" }
            check(result.icons.values.sumOf { it.byteCount } <= 4 * 1024 * 1024)
        }
        checkSelection(fixture.packageName)
    }

    private fun checkSelection(pack: String) = with(runner) {
        val prefs = targetContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        val keys = listOf("setup_shown", "lock_pin", "icon_style", "icon_pack")
        val before = prefs.all.filterKeys { it in keys }
        var home: MainActivity? = null
        fun await(condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until) {
                var ok = false
                runOnMainSync { ok = condition() }
                if (ok) return
                SystemClock.sleep(50)
            }
            error("Icon pack UI did not settle")
        }
        try {
            prefs.edit().putBoolean("setup_shown", true).remove("lock_pin").remove("icon_pack").putString("icon_style", "colour").commit()
            val app = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            home = app
            val row = app.findViewById<ViewGroup>(R.id.appRow)
            await { row.childCount > 0 }
            val refresh = MainActivity::class.java.getDeclaredMethod("refreshApps", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val selected = MainActivity::class.java.getDeclaredField("iconPack").apply { isAccessible = true }
            fun click(text: String) = runOnMainSync {
                val matches = ArrayList<View>()
                app.findViewById<View>(R.id.settingsList).findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT)
                var view = matches.firstOrNull { (it as? android.widget.TextView)?.text?.toString() == text }
                while (view != null && !view.isFocusable) view = view.parent as? View
                check(view?.performClick() == true) { "Missing setting: $text" }
            }
            runOnMainSync { app.openLauncherSettings(R.string.icon_pack) }
            click(targetContext.getString(R.string.icon_pack))
            await { app.findViewById<ViewGroup>(R.id.settingsList).childCount > 1 }
            click("Test icon pack")
            await { prefs.getString("icon_pack", null) == pack && selected.get(app) != null }
            runOnMainSync {
                val tile = row.findViewWithTag<ViewGroup>(pack)
                check(tile != null) { "Test pack app missing from home row" }
                check((tile.getChildAt(0) as ImageView).drawable is LayerDrawable) { "Home row did not use pack artwork" }
            }
            // A failed selection must leave the active selection intact.
            runOnMainSync { refresh.invoke(app, "tv.nonexistent.pack", true) }
            SystemClock.sleep(1000)
            check(prefs.getString("icon_pack", null) == pack)
            // Only the newest request can publish its result.
            runOnMainSync { refresh.invoke(app, pack, true); refresh.invoke(app, null, true) }
            await { prefs.getString("icon_pack", null) == null && selected.get(app) == null }
        } finally {
            runOnMainSync { home?.finish() }
            prefs.edit().apply {
                keys.forEach { remove(it) }
                before.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
        }
    }
}
