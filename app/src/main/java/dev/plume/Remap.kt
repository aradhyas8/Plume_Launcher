package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.view.KeyEvent

/**
 * Remote key remapping. Plume's Home key service already sees every key before any app does, so a key mapped
 * here is handled there: [Entry.short] runs on a press and [Entry.long] on a hold ("" when unset). An action is
 * "none" (swallow the key), "app:<package>", "hdmi:<input id>", "media:<name>" or "sys:<name>".
 * Kept in one preference string ("home"/"keymap", a line per key) and in memory only once a key is pressed.
 */
object Remap {
    class Entry(val short: String, val long: String)

    /** Set while the settings page waits for a key to change. */
    var learn: ((KeyEvent) -> Unit)? = null

    /** The key whose press was just taken to be learned: its repeats and its release are swallowed too. */
    var pending: String? = null

    private var map: LinkedHashMap<String, Entry>? = null

    // Keys that move around Plume or leave it: never learned, never changed, so a remote can't be locked out.
    private val KEEP = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    val MEDIA = linkedMapOf(
        "playpause" to (KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.act_play_pause),
        "next" to (KeyEvent.KEYCODE_MEDIA_NEXT to R.string.act_next),
        "previous" to (KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.act_previous),
        "stop" to (KeyEvent.KEYCODE_MEDIA_STOP to R.string.act_stop),
        "rewind" to (KeyEvent.KEYCODE_MEDIA_REWIND to R.string.act_rewind),
        "forward" to (KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.act_forward),
    )

    val SYS = linkedMapOf(
        "back" to (AccessibilityService.GLOBAL_ACTION_BACK to R.string.act_back),
        "recents" to (AccessibilityService.GLOBAL_ACTION_RECENTS to R.string.act_recents),
        "notifications" to (AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to R.string.act_notifications),
        "quick" to (AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to R.string.act_quick),
        "power" to (AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to R.string.act_power),
    )

    /** A key's identity: its Android code, or its scan code for keys Android has no code for. */
    fun id(e: KeyEvent) = if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN) "k${e.keyCode}" else "s${e.scanCode}"

    /** Also keys with no code at all, which would all share one identity. */
    fun kept(e: KeyEvent) = e.keyCode in KEEP || (e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == 0)

    fun all(c: Context): LinkedHashMap<String, Entry> = map ?: LinkedHashMap<String, Entry>().also { m ->
        for (line in c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("keymap", "")!!.lines()) {
            val p = line.split('\t')
            if (p.size == 3) m[p[0]] = Entry(p[1], p[2])
        }
        map = m
    }

    /** Saves a key's actions; a key left with none is forgotten and goes back to its usual job. */
    fun put(c: Context, id: String, short: String, long: String) {
        val m = all(c)
        if (short.isEmpty() && long.isEmpty()) m.remove(id) else m[id] = Entry(short, long)
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
            .putString("keymap", m.entries.joinToString("\n") { "${it.key}\t${it.value.short}\t${it.value.long}" }).apply()
    }

    fun run(s: AccessibilityService, action: String) {
        val arg = action.substringAfter(':', "")
        try {
            when (action.substringBefore(':')) {
                "app" -> (s.packageManager.getLeanbackLaunchIntentForPackage(arg) ?: s.packageManager.getLaunchIntentForPackage(arg))
                    ?.let { s.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "hdmi" -> s.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(arg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "media" -> MEDIA[arg]?.let { (code, _) ->
                    val audio = s.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                "sys" -> SYS[arg]?.let { (code, _) -> s.performGlobalAction(code) }
            }
        } catch (e: Exception) {
            // An app that's gone, or an input that's unplugged: the key just does nothing.
        }
    }

    /** What a key is called in the list: "Media play pause" for Android's own codes, "Key 4001" for the rest. */
    fun name(c: Context, id: String): String {
        val n = id.substring(1).toInt()
        val s = if (id[0] == 'k') KeyEvent.keyCodeToString(n) else ""
        return if (s.startsWith("KEYCODE_")) s.substring(8).lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        else c.getString(R.string.key_number, n)
    }

    fun describe(c: Context, action: String): String {
        val arg = action.substringAfter(':', "")
        return when (action.substringBefore(':')) {
            "" -> c.getString(R.string.key_unset)
            "none" -> c.getString(R.string.key_block)
            "app" -> try {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(arg, 0)).toString()
            } catch (e: Exception) {
                arg
            }
            "hdmi" -> try {
                c.getSystemService(TvInputManager::class.java).getTvInputInfo(arg)!!.loadLabel(c).toString()
            } catch (e: Exception) {
                "HDMI"
            }
            "media" -> MEDIA[arg]?.let { c.getString(it.second) } ?: arg
            "sys" -> SYS[arg]?.let { c.getString(it.second) } ?: arg
            else -> action
        }
    }
}
