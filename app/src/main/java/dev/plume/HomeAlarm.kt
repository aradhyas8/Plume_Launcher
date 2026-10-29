package dev.plume

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Android holds back an activity start from an app for 5 seconds after Home, so on a Fire TV Plume shows 5 s late.
 * A start that the system sends is not held back: an alarm's PendingIntent is. But the alarm service never fires
 * an alarm sooner than `min_futurity` (5 s by default), so while the Home button is on we set it to 0 and
 * put the old value back when the button goes off. Android 12 and later keep it where only adb can write.
 */
object HomeAlarm {
    private const val SETTING = "alarm_manager_constants"
    private const val KEY = "min_futurity"
    private const val SET = "futurity_set"
    private const val WAS = "futurity_was"

    /** Fire OS 7 (Android 9) and 8 (Android 11), where the app can write the setting with the Home button's grant. */
    private fun possible(c: Context) = HomeOverride.fireTv(c) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    /** Home button on: no wait for the alarm. A value that is 0 already (adb, another app) stays as it is. */
    fun engage(c: Context) {
        if (!possible(c) || c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        val now = Settings.Global.getString(c.contentResolver, SETTING)
        if (value(now, KEY) == "0") return
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean(SET, true).putString(WAS, value(now, KEY)).apply()
        write(c, with(now, KEY, "0"))
    }

    /** Home button off: the old value back, but only if we changed it and nobody changed it since. */
    fun release(c: Context) {
        val prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SET, false)) return
        val now = Settings.Global.getString(c.contentResolver, SETTING)
        if (value(now, KEY) == "0" && !write(c, with(now, KEY, prefs.getString(WAS, null)))) return
        prefs.edit().remove(SET).remove(WAS).apply()
    }

    /** False: no alarm, and the caller starts the activity itself as before. Exact alarms need no permission before Android 12. */
    @Suppress("MissingPermission")
    fun start(c: Context, intent: Intent): Boolean {
        if (!possible(c) || value(Settings.Global.getString(c.contentResolver, SETTING), KEY) != "0") return false
        return try {
            val send = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            c.getSystemService(AlarmManager::class.java).setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), send)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun write(c: Context, constants: String?) = try {
        Settings.Global.putString(c.contentResolver, SETTING, constants)
    } catch (_: SecurityException) { // grant taken back
        false
    }

    /** [constants] is a list such as `min_futurity=0,min_interval=60000`; null when the setting is not there. */
    internal fun value(constants: String?, key: String): String? = constants.orEmpty().split(',')
        .map { it.trim() }.firstOrNull { it.substringBefore('=') == key && '=' in it }?.substringAfter('=')

    /** The list with [key] set to [value], or without it when [value] is null. The other entries stay as they are. */
    internal fun with(constants: String?, key: String, value: String?): String? {
        val others = constants.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') != key }
        return (others + listOfNotNull(value?.let { "$key=$it" })).joinToString(",").ifEmpty { null }
    }
}
