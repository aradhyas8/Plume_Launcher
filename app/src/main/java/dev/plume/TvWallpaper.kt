package dev.plume

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.WindowManager

/**
 * The "TV wallpaper": whatever is set as the system wallpaper, a still picture or a live one, shown behind the home
 * screen. Android draws it, in the wallpaper's own app, and only while the home screen is on screen: Plume adds no
 * decoder, no file and nothing that runs once you open an app. Plume can't read its pixels, so Glass has nothing
 * to blur; text takes its colour from the colours the wallpaper reports.
 */
internal object TvWallpaper {
    /** The wallpaper choice, next to "res:…", "file:…", "folder:…", "bing" and "shows". */
    const val CHOICE = "system"

    /** Lets the system wallpaper through the window, or covers it again. */
    fun show(a: Activity, on: Boolean) {
        if (on) {
            a.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            a.window.setBackgroundDrawable(null)
        } else {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    /**
     * The wallpaper's main colour, for the text over it, or [fallback] where Android doesn't say (before 8.1, or a
     * live wallpaper that reports none). Text that the wallpaper says suits dark text gets a light colour, so it reads dark.
     */
    fun color(a: Activity, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < 27) return fallback
        val c = try {
            WallpaperManager.getInstance(a).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            null
        } ?: return fallback
        if (Build.VERSION.SDK_INT >= 31 && (c.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0) return Color.WHITE
        return c.primaryColor.toArgb()
    }

    /** The TV's own screen for picking a wallpaper, if it has one: many Android TV and Google TV builds don't. */
    fun picker(a: Activity): Intent? =
        listOf(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), Intent(Intent.ACTION_SET_WALLPAPER))
            .firstOrNull { it.resolveActivity(a.packageManager) != null }
}
