package dev.plume

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** Run on a test emulator with -e class dev.plume.WallpaperUploadsCheck. */
class WallpaperUploadsCheck(private val runner: Instrumentation) {
    fun run() = with(runner) {
        val prefs = targetContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        val dir = checkNotNull(targetContext.getExternalFilesDir("wallpapers"))
        val prefix = "Wallpaper Test ${SystemClock.uptimeMillis()}"
        val files = ('A'..'D').associateWith { File(dir, "$prefix $it.png") }
        val scale = shell("settings get global animator_duration_scale")
        var launcher: MainActivity? = null
        try {
            shell("settings put global animator_duration_scale 2")
            writePhoto(files.getValue('A'), Color.RED)
            files.getValue('B').writeText("Not an image")
            writePhoto(files.getValue('C'), Color.TRANSPARENT)
            writePhoto(files.getValue('D'), Color.BLUE)
            val initial = "file:${files.getValue('A').name}"
            prefs.edit().putBoolean("setup_shown", true).putString("theme", "light")
                .putString("seen_notes", targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName)
                .putBoolean("video_keep_one", false).putString("wp_dark", initial).putString("wp_light", initial).commit()
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            launcher = activity
            val wallpaper = activity.findViewById<ImageView>(R.id.wallpaper)
            await("Initial wallpaper did not load") { wallpaper.drawable is BitmapDrawable }
            val list = activity.findViewById<ViewGroup>(R.id.settingsList)
            fun select(photo: Char) {
                val label = checkNotNull(findLabel(list, "$prefix $photo"))
                var tile: View = label
                while (!tile.isClickable) tile = tile.parent as View
                check(tile.performClick())
            }
            runOnMainSync {
                activity.findViewById<View>(R.id.launcherSettings).performClick()
                MainActivity::class.java.getDeclaredMethod("pickUploads", Boolean::class.javaPrimitiveType, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, false, null)
                select('B')
            }
            SystemClock.sleep(300)
            check(prefs.getString("wp_dark", null) == initial && prefs.getString("wp_light", null) == initial)
            val initialDrawable = wallpaper.drawable
            runOnMainSync {
                check(initialDrawable is BitmapDrawable && wallpaper.drawable === initialDrawable)
                select('C')
            }
            await("One press did not start a crossfade") { (wallpaper.drawable as? TransitionDrawable)?.isCrossFadeEnabled == true }
            var firstFade: TransitionDrawable? = null
            runOnMainSync {
                firstFade = wallpaper.drawable as TransitionDrawable
                select('D')
            }
            val latest = "file:${files.getValue('D').name}"
            await("One press did not save the latest photo") { prefs.getString("wp_light", null) == latest }
            runOnMainSync { check(wallpaper.drawable === firstFade) { "New selection interrupted the visible blend" } }
            await("Latest photo did not finish fading in") {
                val bitmap = (wallpaper.drawable as? BitmapDrawable)?.bitmap ?: return@await false
                val sample = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                try { sample.getPixel(0, 0) == Color.BLUE } finally { sample.recycle() }
            }
        } finally {
            runOnMainSync { launcher?.finish() }
            if (scale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $scale")
            files.values.forEach { it.delete() }
        }
    }

    private fun writePhoto(file: File, colour: Int) {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(colour)
            if (colour == Color.TRANSPARENT) bitmap.setPixel(160, 90, Color.GREEN)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun findLabel(view: View, text: String): TextView? = when {
        view is TextView && view.text == text -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findLabel(view.getChildAt(it), text) }
        else -> null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        do {
            var done = false
            runner.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < until)
        error(message)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(runner.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
}
