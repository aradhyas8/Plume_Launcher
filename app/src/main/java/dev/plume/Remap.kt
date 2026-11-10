package dev.plume

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

/**
 * Remote key remapping. Plume's Home key service already sees every key before any app does, so a key mapped
 * here is handled there: [Entry.short] runs on a press and [Entry.long] on a hold ("" when unset). An action is
 * "none" (swallow the key), "app:<package>", "hdmi:<input id>", "media:<name>", "sys:<name>" or "open:<place>" (Plume or Android Settings).
 * Kept in one preference string ("home"/"keymap", a line per key) and in memory only once a key is pressed.
 */
object Remap {
    class Entry(val short: String, val long: String)

    /** Set while the settings page waits for a key to change; the service must see keys meanwhile. */
    var learn: ((KeyEvent) -> Unit)? = null
        set(value) {
            field = value
            HomeOverride.running?.applyKeyFilter()
        }

    /** The key whose press was just taken to be learned: its repeats and its release are swallowed too. */
    var pending: String? = null

    private var map: LinkedHashMap<String, Entry>? = null

    // Keys that move around Plume or leave it: never learned, never changed, so a remote can't be locked out.
    private val KEEP = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT,
    )

    val MEDIA = linkedMapOf(
        "playpause" to (KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.act_play_pause),
        "next" to (KeyEvent.KEYCODE_MEDIA_NEXT to R.string.act_next),
        "previous" to (KeyEvent.KEYCODE_MEDIA_PREVIOUS to R.string.act_previous),
        "stop" to (KeyEvent.KEYCODE_MEDIA_STOP to R.string.act_stop),
        "rewind" to (KeyEvent.KEYCODE_MEDIA_REWIND to R.string.act_rewind),
        "forward" to (KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.act_forward),
        "mute" to (KeyEvent.KEYCODE_VOLUME_MUTE to R.string.act_mute),
    )

    val SYS = linkedMapOf(
        "back" to (AccessibilityService.GLOBAL_ACTION_BACK to R.string.act_back),
        "recents" to (AccessibilityService.GLOBAL_ACTION_RECENTS to R.string.act_recents),
        "notifications" to (AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to R.string.act_notifications),
        "quick" to (AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to R.string.act_quick),
        "power" to (AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to R.string.act_power),
    )

    /** A Home intent opens this view once, after the main screen is ready. */
    const val OPEN_ALL_APPS = "open_all_apps"
    const val OPEN_RECENT_APPS = "open_recent_apps"

    /** Screens that a key can open without an app tile. */
    val OPEN = linkedMapOf(
        "settings" to (Settings.ACTION_SETTINGS to R.string.act_settings),
        "network" to (Settings.ACTION_WIFI_SETTINGS to R.string.act_network),
        "allapps" to (Intent.ACTION_MAIN to R.string.act_all_apps),
        "recents" to (Intent.ACTION_MAIN to R.string.act_plume_recents),
    )

    /** A key's identity uses its scan code too, when available. */
    fun id(e: KeyEvent) = id(e.keyCode, e.scanCode)

    /** Also keys with no code at all, which would all share one identity. */
    fun kept(e: KeyEvent) = kept(e.keyCode, e.scanCode)

    // The same rules on plain numbers and a plain SharedPreferences, so the unit tests need no Android framework.
    internal fun id(keyCode: Int, scanCode: Int) = when {
        keyCode == KeyEvent.KEYCODE_UNKNOWN -> "s$scanCode"
        scanCode != 0 -> "k${keyCode}s$scanCode"
        else -> "k$keyCode"
    }

    internal fun lookup(map: Map<String, Entry>, keyCode: Int, scanCode: Int) =
        map[id(keyCode, scanCode)] ?: if (keyCode != KeyEvent.KEYCODE_UNKNOWN) map["k$keyCode"] else null

    internal fun kept(keyCode: Int, scanCode: Int) = keyCode in KEEP || (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == 0)

    private fun store(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun all(c: Context) = all(store(c))

    internal fun all(p: SharedPreferences): LinkedHashMap<String, Entry> = map ?: LinkedHashMap<String, Entry>().also { m ->
        for (line in p.getString("keymap", "")!!.lines()) {
            val f = line.split('\t')
            if (f.size == 3) m[f[0]] = Entry(f[1], f[2])
        }
        map = m
    }

    /** Forgets the keys held in memory, so the next [all] reads the preferences again. */
    internal fun reset() {
        map = null
    }

    /** Saves a key's actions; a key left with none is forgotten and goes back to its usual job. */
    fun put(c: Context, id: String, short: String, long: String) {
        put(store(c), id, short, long)
        HomeOverride.running?.applyKeyFilter()
    }

    internal fun put(p: SharedPreferences, id: String, short: String, long: String) {
        val m = all(p)
        if (short.isEmpty() && long.isEmpty()) m.remove(id) else m[id] = Entry(short, long)
        p.edit().putString("keymap", m.entries.joinToString("\n") { "${it.key}\t${it.value.short}\t${it.value.long}" }).apply()
    }

    private val KAMINO = ComponentName("com.amazon.tv.inputpreference.service", "com.amazon.tv.inputpreference.service.KaminoInputSwitchService")

    /** The hardware number at the end of a TV input id ("…/HW2"); some Fire TV switchers accept it as inputType. */
    internal fun hardwareNumber(id: String) = Regex("/HW(\\d+)$").find(id)?.groupValues?.get(1)?.toInt()

    /** #146: this provider's inputs fail through Amazon's switcher, so Plume supplies the playback surface. */
    internal fun playsInput(id: String) = id.startsWith("com.droidlogic.tvinput/")

    /**
     * Opens an HDMI input, for the tiles and for keys alike. Fire OS hands the standard passthrough request to Amazon's
     * Live TV app, which opens the wrong screen (#91). DroidLogic inputs use Plume's player (#146); other Fire TV inputs
     * use Amazon's input switch. Some builds refuse that switch: then a toast says how to switch by hand.
     */
    fun openInput(c: Context, id: String) {
        if (!c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
            c.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (playsInput(id)) {
            c.startActivity(Intent(c, InputActivity::class.java).putExtra("input", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }
        val port = hardwareNumber(id)
        val started = port != null && try {
            c.startService(Intent().setComponent(KAMINO).putExtra("inputType", port)) != null
        } catch (e: Exception) {
            false // not exported to other apps on this build, or Android refused a start from the background
        }
        if (!started) Toast.makeText(c, R.string.fire_os_no_input, Toast.LENGTH_LONG).show()
    }

    /** The system's power dialog, for the top-bar icon and for a remote key. Some systems (Fire OS, say) refuse it: say so. */
    fun powerMenu(s: AccessibilityService) {
        if (!s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) Toast.makeText(s, R.string.power_menu_failed, Toast.LENGTH_LONG).show()
    }

    fun run(s: AccessibilityService, action: String) {
        val arg = action.substringAfter(':', "")
        try {
            when (action.substringBefore(':')) {
                "app" -> (s.packageManager.getLeanbackLaunchIntentForPackage(arg) ?: s.packageManager.getLaunchIntentForPackage(arg))
                    ?.let { s.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "hdmi" -> openInput(s, arg)
                "media" -> MEDIA[arg]?.let { (code, _) ->
                    val audio = s.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                        if (audio.isVolumeFixed) Toast.makeText(s, R.string.mute_unavailable, Toast.LENGTH_LONG).show()
                        else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                }
                "sys" -> SYS[arg]?.let { (code, _) ->
                    if (code == AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) powerMenu(s) else s.performGlobalAction(code)
                }
                "open" -> OPEN[arg]?.let { (action, _) ->
                    val intent = Intent(action)
                    if (arg == "allapps" || arg == "recents") intent.setClass(s, MainActivity::class.java)
                        .addCategory(Intent.CATEGORY_HOME).putExtra(if (arg == "recents") OPEN_RECENT_APPS else OPEN_ALL_APPS, true)
                    s.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } catch (e: Exception) {
            if (action == "media:mute") Toast.makeText(s, R.string.mute_unavailable, Toast.LENGTH_LONG).show()
            // An app that's gone, or an input that's unplugged: the key just does nothing.
        }
    }

    /** What a key is called in the list: "Media play pause" for Android's own codes, "Key 4001" for the rest. */
    fun name(c: Context, id: String): String {
        val n = id.substring(1).substringBefore('s').toInt()
        val s = if (id[0] == 'k') KeyEvent.keyCodeToString(n) else ""
        val label = if (s.startsWith("KEYCODE_")) s.substring(8).lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        else c.getString(R.string.key_number, n)
        return if (id[0] == 'k' && 's' in id) "$label (${id.substringAfter('s')})" else label
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
            "open" -> OPEN[arg]?.let { c.getString(it.second) } ?: arg
            else -> action
        }
    }
}
