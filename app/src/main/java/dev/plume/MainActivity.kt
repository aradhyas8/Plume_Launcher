package dev.plume

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import org.json.JSONObject

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> autoDark(sunPlace(prefs), System.currentTimeMillis(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    else -> false
}

/** The weather's location (found or chosen), kept on the TV: Auto follows the sun there. Null until there is one. */
internal fun sunPlace(prefs: SharedPreferences) =
    if (prefs.contains("geo_lat")) prefs.getFloat("geo_lat", 0f).toDouble() to prefs.getFloat("geo_lon", 0f).toDouble() else null

/** Dark from sunset to sunrise at [place]; with no place, from 19:00 to 7:00 as before. */
internal fun autoDark(place: Pair<Double, Double>?, now: Long, hour: Int) =
    if (place == null) hour !in 7..18 else Sun.dark(place.first, place.second, now)

/** "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/**
 * Sort key of a Watch Next row: what you started comes before suggestions (types other than 0, "continue"), then
 * newest first. Some apps write the time in seconds, not milliseconds (Wholphin does, for what is playing): a number
 * that small is read as seconds, or it would sort a thousand times older than every other row.
 */
internal fun watchSortKey(type: Long, engaged: Long): Long {
    val millis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged
    return (if (type == 0L) 1L shl 50 else 0L) + millis
}

/**
 * Sort key of a card from an app's home-screen channel. Channels have no Watch Next type, so every card counts as
 * "continue". The card's own last-watched time when the app sets one (Nuvio does), else the time the app was last
 * opened from Plume.
 */
internal fun channelSortKey(engaged: Long, opened: Long) = watchSortKey(0, if (engaged > 0) engaged else opened)

/**
 * Rows as (weight, sort key, item) to (sort key, item), highest weight first. A channel's cards often share one sort
 * key; the weight is the app's own order for them (Nuvio gives its newest the highest), and [newestPerApp] keeps it.
 */
internal fun <T> byWeight(rows: List<Triple<Long, Long, T>>) = rows.sortedByDescending { it.first }.map { it.second to it.third }

/**
 * Newest first across apps (ties keep their order), each app's [perApp] newest ahead of the rest: one app's ten
 * shows can't bury the others. The rest follow, newest first, so an app on its own isn't held to [perApp].
 */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    val (front, rest) = items.sortedByDescending { it.first }.map { it.second }.partition { shown.merge(app(it), 1, Int::plus)!! <= perApp }
    return front + rest
}

/**
 * Whether an app's home-screen channel feeds Continue Watching. Channels named "Continue Watching" (Stremio's) start
 * on, every other one (Plex On Deck, a Stremio catalog) starts off; [flipped] holds the ones the user switched.
 */
internal fun channelOn(name: String, key: String, flipped: Set<String>) =
    name.equals("Continue Watching", ignoreCase = true) != (key in flipped)

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

private val SET_TOP_WORDS = listOf("sky", "tivo", "xfinity", "comcast", "directv", "dish", "stb", "set-top", "settop", "cable", "satellite", "freesat", "freeview")
private val AUDIO_WORDS = listOf("soundbar", "sound bar", "sonos", "avr", "receiver", "home theater", "home theatre", "bose", "denon", "marantz", "onkyo", "yamaha", "harman", "jbl")

/**
 * Icon key for an HDMI tile, from its name: the CEC device name, or the name the user gave the input in TV
 * settings. Android doesn't give apps the CEC device type, so the name is all there is. One brand word covers
 * the family ("PlayStation" is PS3 to PS5). [named]: a CEC device, which is a player when nothing else matches.
 */
internal fun hdmiIcon(name: String, named: Boolean): String {
    val n = name.lowercase()
    return when {
        "playstation" in n -> "playstation"
        "xbox" in n -> "xbox"
        "nintendo" in n || "switch" in n -> "nintendoswitch"
        "apple tv" in n || "appletv" in n -> "appletv"
        "shield" in n -> "nvidia"
        "chromecast" in n || "google tv" in n -> "chromecast"
        SET_TOP_WORDS.any { it in n } -> "settop"
        named -> "playback"
        else -> "hdmi"
    }
}

/** Soundbars and AV receivers name themselves over CEC too, but there's nothing to switch to. */
internal fun isAudioDevice(name: String) = name.lowercase().let { n -> AUDIO_WORDS.any { it in n } }

/** An app is in the home row unless it was taken out; it stays in All apps either way. */
internal fun inRow(pkg: String, hidden: Set<String>) = pkg !in hidden

/**
 * Before the Sources list, an input with a signal or a CEC name was in the row without a pin. Once, on the update,
 * pin every input that was in the row so that no tile goes away. [inputs]: the input key and whether it has no signal.
 */
internal fun pinsAfterSourcesUpdate(inputs: List<Pair<String, Boolean>>, hidden: Set<String>, pinned: Set<String>) =
    pinned + inputs.filter { (pkg, offline) -> pkg !in hidden && (!offline || pkg in pinned) }.map { it.first }

/** The icon styles, in the order the setting steps through them. */
internal val ICON_STYLE_KEYS = listOf("mono", "colour", "banner", "bannermono")

/** Banner and Monochrome banner: the row shows the apps' own pictures in 16:9 tiles. */
internal fun isBannerStyle(style: String) = style.startsWith("banner")

/** The style a stored choice means. Before styles, Colour was an on/off setting ([oldColour]): it decides until a style is picked. */
internal fun iconStyleOf(stored: String?, oldColour: Boolean) =
    stored?.takeIf { it in ICON_STYLE_KEYS } ?: if (oldColour) "colour" else "mono"

/** Allowed to start an activity from the background because the Home button service is bound. */
class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class MainActivity : Activity() {

    /**
     * An app's icon is loaded once, but not held: the mono mark and the accent colour are made from it and kept, and a
     * tile that shows the icon holds its own copy, so ~20 launcher icons (up to a megabyte each) need not stay in memory.
     * If the garbage collector took it before it was needed, [load] brings it back.
     * [offline]: an HDMI port with no signal right now (the Sources list says so).
     */
    private class App(val pkg: String, val label: String, val launch: Intent, icon: Drawable, val offline: Boolean = false, private val load: (() -> Drawable)? = null) {
        private val strong = if (load == null) icon else null // our own drawables (HDMI marks) are tiny and have no loader
        private var weak = java.lang.ref.WeakReference(icon)
        val icon: Drawable get() = strong ?: weak.get() ?: load!!().also { weak = java.lang.ref.WeakReference(it) }
    }
    /** [group]: what shares the front places in Continue Watching, the app or, for an app channel, that channel. */
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?,
                       val group: String? = pkg)
    /** One row of cards you can switch to: Watch Next, or one app channel that is on. */
    private class CardSource(val key: String, val title: String)
    /** What the shows row holds: the [sources] it is split in (none when mixed), the one on show and its cards. */
    private class Cards(val sources: List<CardSource>, val current: CardSource?, val list: List<Prog>)

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    private val posterIo = Executors.newFixedThreadPool(3)
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ui = Handler(Looper.getMainLooper())
    private val posters = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    private var apps = APPS ?: emptyList()
    private var sources = SOURCES ?: emptyList() // HDMI ports and the devices on them: the Sources list, not the apps
    private var appsDirty = APPS == null
    private var watchKey: String? = null
    private var cardSources = emptyList<CardSource>() // By title and Per channel: the sources the shows row is split in
    private var cardCurrent: CardSource? = null
    private var switching = false // the next list is another source of the same row: its cards are swapped in place
    private var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    private var lastApp: View? = null
    private var lastCard: View? = null
    private var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE
    private var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    private var cardLit = cardRest

    private lateinit var appRow: LinearLayout
    private lateinit var watchRow: LinearLayout
    private lateinit var watchTitle: TextView
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: View
    private val appScroll by lazy { findViewById<View>(R.id.appScroll) }
    private var titleAlpha = 1f // the row title's own alpha from the layout, read before placeRow fades it
    private var rowOut = false // "Show row: On Down": the bottom row has focus, so it's slid in
    private lateinit var allApps: View
    private lateinit var allAppsGrid: GridLayout
    private lateinit var wifi: ImageView
    private lateinit var glass: GlassLayer // paints the blurred wallpaper under glass tiles

    private var resumed = false
    private val refreshIfDirty = Runnable { if (appsDirty && !editing) refreshApps() } // not while a tile is being moved: onResume catches up

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it); ACCENTS.remove(it) } // an update may bring a new icon
            // The system can tell us after we are back in front, for example after an uninstall: onResume has run already.
            if (resumed) {
                ui.removeCallbacks(refreshIfDirty)
                ui.postDelayed(refreshIfDirty, 300L)
            }
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        glass = GlassLayer(this)
        findViewById<ViewGroup>(R.id.root).addView(glass, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) // over the wallpaper, under the home screen
        for (id in intArrayOf(R.id.appScroll, R.id.watchScroll, R.id.allApps)) findViewById<View>(id).setOnScrollChangeListener { _, _, _, _, _ -> glass.kick(150) }
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        titleAlpha = watchTitle.alpha
        watchScroll = findViewById(R.id.watchScroll)
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        findViewById<View>(R.id.sourcesButton).visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
        applyTheme()
        setUpTitle() // after the theme: the pill is styled with the theme's card colours
        loadWallpaper()
        // "Show row: On Down": the row slides in while one of its cards has focus, however focus got there.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val out = rowShow() == "down" && now?.getTag(R.id.watchRow) is Prog
            if (out != rowOut) { rowOut = out; placeRow(animate = true) }
        }
        watchScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) placeRow(animate = false) // the drop is measured from the layout
        }

        // Top bar: network, All apps, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { unlock { openSettings(Settings.ACTION_WIFI_SETTINGS) } },
            R.id.sourcesButton to { showSources(findViewById(R.id.sourcesButton)) },
            R.id.allAppsButton to { openAllApps() },
            R.id.systemSettings to { unlock { openSettings(Settings.ACTION_SETTINGS) } },
            R.id.launcherSettings to { unlock { openLauncherSettings() } },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            // Lit, the icon sits on its ring, so it takes the theme colour; otherwise it's on the wallpaper (see fitInk).
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ring.setColor(if (!focused) 0 else if (dark) DARK_CARD_LIT[0] else Color.WHITE)
                setColorFilter(if (focused) fg else iconInk)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
        }
        if (fakeSetup(intent)) {
            // debug: setup for a pretend TV already started
        } else if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(R.string.theme) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: walk through making Home land on Plume, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { startHomeSetup() }
        } else if (notesUnseen()) {
            ui.post { whatsNewCard() } // after an update; the self-updater reopens Plume, so it's seen right away
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        openedFrom?.get()?.let { tile -> ui.post { if (tile.isAttachedToWindow) settleRow(tile) } }
        openedFrom = null
        ensureHomeService()
        homeSetupResumed()
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        ui.removeCallbacks(wallpaperTick)
        ui.post(wallpaperTick)
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && photoScreensaver() == null && !paintingsReady(this)) downloadPaintings {}
        checkUpdate(force = false)
        Bing.onChanged = bingChanged
        Bing.refresh(applicationContext, prefs)
        ui.post { updateBadge() } // after layout, so the icon has its size
    }

    /** Auto theme flips at sunrise and sunset (or 7:00 and 19:00) even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (wantDark() != dark) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else toast(getString(R.string.photos_permission), long = true)
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.default_launcher)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(refreshIfDirty)
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        ui.removeCallbacks(wallpaperTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onStop() {
        unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
        Remap.learn = null // never keep swallowing keys for a page that's no longer on screen
        if (homeTest != 0) leftDuringTest = true
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        if (Bing.onChanged === bingChanged) Bing.onChanged = null
        unregisterReceiver(packageReceiver)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        upload?.stop()
        io.shutdownNow()
        posterIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
     * unless the user turned it off.
     */
    private fun ensureHomeService() {
        if (homeServiceOn()) return prefs.edit().remove("home_off").apply() // what Android says wins
        if (prefs.getBoolean("home_off", false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.plume/.HomeOverride). */
    private fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

    /** Off hands Home back to the TV's own launcher, and Plume leaves it off until switched on again. */
    private fun turnOffHomeButton() {
        prefs.edit().putBoolean("home_off", true).apply()
        HomeOverride.running?.disableSelf()
        if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            val me = ComponentName(this, HomeOverride::class.java)
            val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
        }
        if (homeServiceOn()) {
            toast(getString(R.string.home_off_hint), long = true, system = true)
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        renderSettings(R.string.home_button)
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(R.string.home_button)
        // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
        // with "You don't have an app that can do this", which we can't detect from here.
        toast(getString(R.string.home_on_hint), long = true, system = true)
        openHomeServiceSettings()
    }

    /** False when no Settings screen opened at all. */
    private fun openHomeServiceSettings(): Boolean {
        val service = ComponentName(this, HomeOverride::class.java).flattenToString()
        val tries = listOf(
            // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                startActivity(intent) // not resolveActivity(): Android 11+ hides other apps from that
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    private fun makeDefaultHome() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        } else {
            openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
        }
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Plume, stopping at the first that works: the default-home
    // role (skipped on Google TV and Fire TV, which ignore it), then the Home button service, then on Android 13+
    // App info's "Allow restricted settings" for sideloaded apps, then adb help for TVs that still grey it out.
    // Each way is checked by asking for a Home press: one that reaches us arrives as a Home intent; one that
    // doesn't sends us to the background.

    private var setupDialog: Dialog? = null
    private var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open, 3 App info open
    private var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    private var leftDuringTest = false
    private var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing
    private var restrictedTried = false // App info's "Allow restricted settings" already offered this round

    /** Google TV takes the home role but its own launcher still gets Home, so only the service works there. */
    private fun googleTv() = fakeDevice?.let { it == "google" } ?: try {
        packageManager.getPackageInfo(HomeWatch.GOOGLE_TV, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun fireTv() = fakeDevice?.let { it == "fire" } ?: HomeOverride.fireTv(this)
    private fun sdk() = if (fakeSdk > 0) fakeSdk else Build.VERSION.SDK_INT

    /**
     * Debug builds: act as another TV and start setup as on a first launch, to walk each device's popups here.
     * adb shell am start -n dev.plume/.MainActivity --es fake_device fire|google|stock|real --ei fake_sdk 33
     * Only the setup's choices change; the TV's own screens and Home key stay what they are.
     */
    private fun fakeSetup(intent: Intent?): Boolean {
        val device = intent?.getStringExtra("fake_device") ?: return false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        fakeDevice = device.takeIf { it != "real" }
        fakeSdk = intent.getIntExtra("fake_sdk", 0)
        ui.post { startHomeSetup() }
        return true
    }

    /** Home opens Plume one way or the other: the service anywhere, the role only where the TV honours it. */
    private fun homeSetUp() = homeServiceOn() || (isDefaultHome() && !googleTv() && !fireTv())

    private fun startHomeSetup() {
        restrictedTried = false
        // Fire OS takes the role request and ignores it; Google TV grants it and still opens its own launcher.
        if (fireTv() || googleTv()) return tryHomeService()
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) rolePopup()
        else afterHomeRole()
    }

    private fun afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

    /** Service still off after Accessibility: on Android 13+ a sideloaded app's toggle is greyed until App info allows it. */
    private fun serviceStillOff() = if (sdk() >= 33 && !restrictedTried) restrictedPopup() else adbPopup()

    private fun tryHomeService() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) testPopup(2) else servicePopup()
    }

    /** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
    private fun homeSetupResumed() {
        if (setupStep == 2) {
            setupStep = 0
            if (homeServiceOn()) testPopup(2) else serviceStillOff()
        } else if (setupStep == 3) {
            setupStep = 0
            servicePopup()
        } else if (homeTest != 0 && leftDuringTest) {
            val failed = homeTest
            homeTest = 0
            if (failed == 1) tryHomeService() else adbPopup()
        }
    }

    private fun rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
        getString(R.string.setup_continue) to {
            setupStep = 1
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    private fun testPopup(way: Int) {
        if (previewAt < 0) {
            homeTest = way
            leftDuringTest = false
        }
        homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
            getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
    }

    private fun servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
        getString(R.string.setup_open_accessibility) to {
            setupStep = 2
            if (!openHomeServiceSettings()) {
                setupStep = 0
                adbPopup()
            }
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    /** Android 13+ "restricted settings": App info has "Allow restricted settings", as a row or under ⋮, on TVs that show it. */
    private fun restrictedPopup() {
        if (previewAt < 0) restrictedTried = true
        homePopup(2, getString(R.string.setup_restricted_title), getString(R.string.setup_restricted_body), null,
            getString(R.string.setup_open_app_info) to {
                setupStep = 3
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (e: Exception) {
                    setupStep = 0
                    adbPopup()
                }
            },
            getString(R.string.setup_no_menu) to { adbPopup() })
    }

    /** The one-time grant lets Plume switch the service on itself, past the greyed-out toggle. */
    private fun adbPopup() {
        val mono = Typeface.MONOSPACE
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) DARK_BG else 0xFFE9EBEF.toInt())
            }
            listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE").forEach {
                addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
            addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
            getString(R.string.setup_try_again) to { tryHomeService() },
            getString(R.string.setup_close) to { setupDialog?.dismiss() })
    }

    /**
     * Once after an update: the first four CHANGELOG.md entries of this version, so put the best ones first.
     * The card shows each entry's bold headline and the sentence after it; See all changes shows the rest.
     * Closing it counts as reading the notes, which clears the dot on the Plume settings icon.
     */
    private fun whatsNewCard() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            getString(R.string.whats_new).split('\n').filter { !it.startsWith("#") }.take(4).forEach {
                val head = it.indexOf("**", 2)
                val cut = if (head < 0) -1 else it.indexOf(". ", head + 2)
                addView(label("", 15f).apply {
                    text = noteText(if (cut < 0) it else it.substring(0, cut + 1))
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(12), 0, 0)
                })
            }
        }
        homePopup(0, getString(R.string.whats_new_card_title, myVersion()), null, notes,
            getString(R.string.done) to { setupDialog?.dismiss() },
            getString(R.string.see_all_changes) to {
                setupDialog?.dismiss()
                unlock { openLauncherSettings(R.string.updates); showUpdates() }
            })
    }

    private fun donePopup() = homePopup(0, getString(R.string.setup_done_title),
        getString(if (homeServiceOn() && !fireTv()) R.string.setup_done_body_service else R.string.setup_done_body), null,
        getString(R.string.setup_done) to { setupDialog?.dismiss() })

    /** A card in the middle of the screen over the dimmed home screen, in the hold menus' colours. */
    private fun homePopup(step: Int, title: String, body: String?, extra: View?, vararg buttons: Pair<String, () -> Unit>) {
        setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
        val card: LinearLayout = if (frosted()) glassSurface(dp(20).toFloat()) else LinearLayout(this).apply {
            background = if (dark) rimCard(dp(16), false, solid = true) else GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
        }
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            // Google TV and Fire TV skip the role step, so their count starts at the service.
            val skip = if (fireTv() || googleTv()) 1 else 0
            if (step > 0) addView(label(getString(R.string.setup_step, step - skip, 3 - skip), 12f).apply { secondary(0.6f) })
            addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            body?.let { addView(label(it, 14f).apply { maxLines = 6; secondary(0.8f); setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) }) }
            extra?.let { addView(it) }
            buttons.forEachIndexed { i, (name, action) ->
                addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
            }
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(if (frosted()) 0.35f else 0.6f) // glass shows what is behind, so it needs less dimming
        }
        dialog.setOnDismissListener {
            if (setupDialog !== dialog) return@setOnDismissListener
            setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
            setupStep = 0
            homeTest = 0
            previewAt = -1
        }
        setupDialog = dialog
        dialog.show()
        card.getChildAt(card.childCount - buttons.size).requestFocus()
    }

    /** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
    private fun previewHomeSetup(at: Int = 0) {
        previewAt = at
        when (at) {
            0 -> rolePopup()
            1 -> testPopup(1)
            2 -> servicePopup()
            3 -> restrictedPopup()
            4 -> adbPopup()
            5 -> donePopup()
            else -> setupDialog?.dismiss()
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (fakeSetup(intent)) return
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> pageBack?.invoke() ?: closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = prefersDark(prefs)

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) DARK_TEXT else 0xFF111214.toInt()
        // Cards are see-through, so the wallpaper shows through them like frosted glass: dark ones are rimCard()s at
        // 70%, light ones frosted grey at 78%, and focus lifts them to 80% and 95%.
        // Over a blur the light tiles need less colour of their own (43% at rest, 75% selected).
        val glassy = glassActive()
        cardRest = if (dark) DARK_CARD else if (glassy) intArrayOf(0x6EE2E5E9, 0x6ED8DCE1) else intArrayOf(0xC8E2E5E9.toInt(), 0xC8D8DCE1.toInt())
        cardLit = if (dark) DARK_CARD_LIT else if (glassy) intArrayOf(0xBEFFFFFF.toInt(), 0xBEFFFFFF.toInt()) else intArrayOf(0xF2FFFFFF.toInt(), 0xF2FFFFFF.toInt())
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) DARK_BG else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        allApps.setBackgroundColor(ground)
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather)) {
            findViewById<TextView>(id).setTextColor(fg)
        }
        iconInk = fg
        for (v in topIcons()) v.setColorFilter(if (v.isFocused) fg else iconInk)
        watchInk = fg
    }

    // ---------- text over the wallpaper ----------
    // The wallpaper can be any photo, so the theme's text colour can vanish into it. A tiny copy of the wallpaper is
    // kept, and the clock block and the Continue watching text each take white or near-black, whichever stands out
    // more from the pixels right behind them. Plain wallpapers keep the theme's colour.

    private var wpThumb: Bitmap? = null
    private var watchInk = Color.BLACK
    private var iconInk = Color.BLACK

    private fun topIcons() = listOf(R.id.wifi, R.id.sourcesButton, R.id.allAppsButton, R.id.systemSettings, R.id.launcherSettings)
        .map { findViewById<ImageView>(it) }

    private fun fitInk() {
        val top = listOf(R.id.clock, R.id.date, R.id.weather).map { findViewById<TextView>(it) }
        val topInk = inkBehind(top)
        for (v in top) ink(v, topInk, fade = true)
        val icons = topIcons()
        iconInk = inkBehind(icons)
        for (v in icons) if (!v.isFocused) v.setColorFilter(iconInk)
        val apps = listOf(R.id.appsTitle, R.id.rowLabel).map { findViewById<TextView>(it) }
        val appsInk = inkBehind(apps)
        for (v in apps) ink(v, appsInk, fade = true)
        val watch = listOf(watchTitle) + (0 until watchRow.childCount).flatMap { i ->
            (watchRow.getChildAt(i) as ViewGroup).let { col -> (0 until col.childCount).mapNotNull { col.getChildAt(it) as? TextView } }
        }
        watchInk = inkBehind(watch)
        for (v in watch) ink(v, watchInk, fade = true)
    }

    /** White or near-black, by the average brightness of the wallpaper under [views]; the theme colour without a photo. */
    private fun inkBehind(views: List<View>): Int {
        val t = wpThumb ?: return fg
        val wall = findViewById<View>(R.id.wallpaper)
        val at = IntArray(2)
        wall.getLocationInWindow(at)
        val (wx, wy) = at[0] to at[1]
        val r = Rect()
        for (v in views) if (v.isShown && v.width > 0) {
            v.getLocationInWindow(at)
            r.union(at[0] - wx, at[1] - wy, at[0] - wx + v.width, at[1] - wy + v.height)
        }
        if (r.isEmpty || wall.width == 0) return fg
        // The wallpaper is centre-cropped: scaled to cover the screen, centred.
        val s = maxOf(wall.width.toFloat() / t.width, wall.height.toFloat() / t.height)
        val dx = (wall.width - t.width * s) / 2
        val dy = (wall.height - t.height * s) / 2
        fun tx(x: Int) = ((x - dx) / s).toInt().coerceIn(0, t.width - 1)
        fun ty(y: Int) = ((y - dy) / s).toInt().coerceIn(0, t.height - 1)
        var sum = 0f
        var n = 0
        for (y in ty(r.top)..ty(r.bottom)) for (x in tx(r.left)..tx(r.right)) {
            sum += Color.luminance(t.getPixel(x, y))
            n++
        }
        return inkFor(sum / n)
    }

    /** Text colour plus a soft shadow in the opposite colour, so it holds on busy photos too. */
    private fun ink(v: TextView, color: Int, fade: Boolean = false) {
        if (wpThumb == null) v.setShadowLayer(0f, 0f, 0f, 0)
        else v.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), if (color == Color.WHITE) 0x80000000.toInt() else 0x80FFFFFF.toInt())
        if (!fade || v.currentTextColor == color) return v.setTextColor(color)
        ValueAnimator.ofArgb(v.currentTextColor, color).apply {
            duration = 800 // with the wallpaper's crossfade
            addUpdateListener { v.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/dev.plume/files/wallpapers/
    // (adb push, no permission needed) and get the fades drawn over them (see paintFades). A folder on the TV (MediaStore,
    // so USB drives too) shows its photos in shuffled turns, one decoded at a time.
    // Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            (wallpaperName(Bing.CHOICE) to Bing.CHOICE) +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null) ?: defaultWallpaper(forDark)

    /** Glass: the tiles are see-through over a blur of the wallpaper (a setting, on by default since 1.6). */
    private fun glassOn() = prefs.getBoolean("glass", true)

    /** Glass needs a picture to blur: with a plain background the tiles stay as they are. */
    private fun glassActive() = glassOn() && !currentWallpaper().startsWith("plain:")

    /** The menus, the cards and the settings panel are glass too when Glass is on. */
    private fun frosted() = glassActive()

    /** The glass surfaces that are on screen, so that a wallpaper change (or a blur that arrives late) reaches them. */
    private val glassSurfaces = ArrayList<java.lang.ref.WeakReference<GlassDrawable>>()

    /** A frosted surface in this theme: the blurred wallpaper under it, a tint, a hairline rim and a little light on top. */
    private fun glassSurface(round: Float, flatRight: Boolean = false): GlassBox = GlassBox(this).also { styleGlass(it.glass, round, flatRight) }

    private fun styleGlass(g: GlassDrawable, round: Float, flatRight: Boolean = false) {
        g.round = round
        g.flatRight = flatRight
        g.wash = if (dark) 0x8C000000.toInt() else 0xA8FFFFFF.toInt() // 55% black, or 66% white
        g.rim = if (dark) 0x33FFFFFF else 0x1F000000
        g.highlight = if (dark) 0x14FFFFFF else 0x66FFFFFF
        g.setBlur(glass.blur)
        glassSurfaces.removeAll { it.get() == null }
        glassSurfaces.add(java.lang.ref.WeakReference(g))
    }

    private var noteView: View? = null
    private var noteHide: Runnable? = null

    /**
     * A short message at the bottom of the screen. With Glass it is a frosted pill, which fades in and out;
     * otherwise it is the system's toast. A message shown just before another screen opens keeps the system's toast
     * ([system]): the pill would stay behind that screen and nobody would see it.
     */
    private fun toast(text: CharSequence, long: Boolean = false, system: Boolean = false) {
        if (system || !frosted()) return Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        val root = findViewById<FrameLayout>(R.id.root)
        noteHide?.let { ui.removeCallbacks(it) }
        noteView?.let { root.removeView(it) }
        val pill = glassSurface(dp(24).toFloat()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(13), dp(26), dp(13))
            alpha = 0f
            translationY = dp(14).toFloat()
            addView(label(text.toString(), 15f).apply {
                maxLines = 3
                ellipsize = null
                maxWidth = dp(640)
            })
        }
        root.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(34) })
        noteView = pill
        pill.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DECELERATE).start()
        noteHide = Runnable {
            pill.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(220).withEndAction { root.removeView(pill) }.start()
        }.also { ui.postDelayed(it, if (long) 3500L else 2000L) }
    }

    /** Supporting text in dark mode: dim grey on black, lighter on glass, where it sits over a photo. */
    private fun text2() = if (frosted()) 0xFFCCCCCC.toInt() else DARK_TEXT_2

    private fun wallpaperName(choice: String) = when {
        choice == Bing.CHOICE -> getString(R.string.bing_daily)
        choice.startsWith("folder:") -> getString(R.string.folder_name, choice.substringAfter(':').substringAfter(':'))
        else -> choice.substringAfter(':').substringBeforeLast('.').let { n -> WALLPAPER_NAMES[n]?.let { getString(it) } ?: n }
    }

    private fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            view.setImageDrawable(null)
            glass.setBlur(null)
            wpRaw = null
            fadesOn = false
            refreshFades()
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val own = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) { // a picture on a TV short of memory: show the default instead of crashing
                null
            }
            val bmp = own ?: decodeWallpaper(fallback)
            val blur = if (bmp != null && glassActive()) glassPixels(bmp, ground, dark) else null
            val thumb = bmp?.let { Bitmap.createScaledBitmap(it, 64, maxOf(1, 64 * it.height / it.width), true) }
            // Nothing reads the pixels from here on: keep the picture in graphics memory only, not once more in the process.
            val shown = bmp?.let { b ->
                try {
                    b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
                } catch (e: Exception) {
                    b
                }
            }
            ui.post {
                glass.setBlur(blur)
                glassSurfaces.forEach { it.get()?.setBlur(glass.blur) } // menus and cards that are open, and the panel
                if (settingsScrim.visibility == View.VISIBLE) showPanelGlass() // a wallpaper picked from the panel itself
                wpRaw = thumb
                fadesOn = own != null && !choice.startsWith("res:")
                refreshFades()
                val old = view.drawable
                if (old == null || shown == null) return@post view.setImageBitmap(shown)
                // Soft change from one photo to the next; then drop the old one so only one stays in memory.
                val fade = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, shown))).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(fade)
                fade.startTransition(800)
                ui.postDelayed({ if (view.drawable === fade) view.setImageBitmap(shown) }, 900)
            }
        }
    }

    private fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        // fitWallpaper's bitmap is mutable: the Bing dim is painted onto it.
        val bmp = when {
            choice == Bing.CHOICE -> fitWallpaper { Bing.file(this).inputStream() }
            choice.startsWith("folder:") -> {
                val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
                fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
            }
            else -> {
                val file = File(wallpaperDir(), choice.removePrefix("file:"))
                fitWallpaper { file.inputStream() }
            }
        } ?: return null
        // Bing's pictures are often bright daylight, which fights true black: dim them in dark mode.
        if (dark && choice == Bing.CHOICE) Canvas(bmp).drawColor(Color.argb(DARK_DIM, 0, 0, 0))
        return bmp
    }

    // ---------- fades over your own photos ----------
    // Drawn as a layer over the wallpaper, not into it, so the sliders in settings change them live.

    private var fadesOn = false // the wallpaper shown is your own photo (bundled ones have their fade baked in)
    private var wpRaw: Bitmap? = null // wpThumb before the fades are painted onto it

    private val fades by lazy {
        object : View(this) {
            override fun onDraw(c: Canvas) {
                if (fadesOn) paintFades(c, width.toFloat(), height.toFloat())
            }
        }.also { findViewById<ViewGroup>(R.id.root).addView(it, 1, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
    }

    /** Strength of the "fade_top" or "fade_bottom" layer, 0-100; 0 is off. Starts off for anyone who had Photo fade off. */
    private fun fade(key: String) = prefs.getInt(key, if (prefs.getBoolean("wp_fade", true)) 100 else 0)

    private fun paintFades(c: Canvas, w: Float, h: Float) {
        val base = ground and 0x00FFFFFF
        val paint = Paint().apply { isDither = true }
        // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
        // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
        fade("fade_top").takeIf { it > 0 }?.let { pct ->
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = pct * 255 / 100
            c.drawRect(0f, 0f, w, h * 0.3f, paint)
        }
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        fade("fade_bottom").takeIf { it > 0 }?.let { pct ->
            paint.shader = LinearGradient(0f, h * 0.28f, 0f, h * 0.5f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            paint.alpha = pct * 255 / 100
            c.drawRect(0f, h * 0.28f, w, h, paint)
        }
    }

    /** Text picks its colour from wpThumb, so it holds the fades too. Run after the photo or a fade changes. */
    private fun refreshFades() {
        wpThumb = wpRaw?.let { raw ->
            if (!fadesOn) raw else raw.copy(Bitmap.Config.ARGB_8888, true).also { paintFades(Canvas(it), it.width.toFloat(), it.height.toFloat()) }
        }
        fades.invalidate()
        fitInk()
    }

    private fun stepFade(key: String, dir: Int) {
        prefs.edit().putInt(key, (fade(key) + FADE_STEP * dir).coerceIn(0, 100)).apply()
        refreshFades()
    }

    /** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    private fun pickWallpaper(forDark: Boolean) {
        val row = if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val current = currentWallpaper(forDark)
        val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }, page(getString(R.string.choose_from_tv)) { pickFolder(forDark) }) +
            choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
            if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo_row)) { removeWallpaper(own, forDark) })
        showPage(if (forDark) getString(R.string.dark_wallpaper) else getString(R.string.light_wallpaper), rows, 2 + choices.indexOfFirst { it.second == current }) {
            renderSettings(row)
        }
    }

    private fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choice)
            .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
        Bing.dropIfUnused(applicationContext, prefs) // choosing another wallpaper deletes the Bing picture at once
        if (choice == Bing.CHOICE) Bing.refresh(applicationContext, prefs, force = true)
        if (forDark == dark) loadWallpaper()
    }

    private val bingChanged: () -> Unit = { if (currentWallpaper() == Bing.CHOICE) loadWallpaper() }

    // ---------- folder wallpapers ----------

    private var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    private val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    private fun photoKey(forDark: Boolean = dark) = if (forDark) "wp_photo_dark" else "wp_photo_light"
    private fun photoAtKey(forDark: Boolean = dark) = if (forDark) "wp_photo_at_dark" else "wp_photo_at_light"
    private fun photoEvery() = prefs.getInt("wp_every", 30)

    private fun pickPhotoEvery() {
        val rows = PHOTO_EVERY.map { (minutes, name) ->
            page(getString(name), if (minutes == photoEvery()) CHOSEN else "") {
                prefs.edit().putInt("wp_every", minutes).apply()
                ui.removeCallbacks(wallpaperTick)
                ui.post(wallpaperTick)
                renderSettings(R.string.folder_photo_changes)
            }
        }
        showPage(getString(R.string.folder_photo_changes), rows, PHOTO_EVERY.keys.indexOf(photoEvery())) { renderSettings(R.string.folder_photo_changes) }
    }

    // ---------- remote keys ----------
    // Settings > Remote keys: press a key on the remote to pick it, then choose what it does. The work is done by
    // the Home key service (see Remap), which is why the page asks for it to be on.

    private fun keysSummary() = Remap.all(this).size.let { resources.getQuantityString(R.plurals.n_keys, it, it) }

    private fun pickKeys(focus: Int = 0) {
        val keys = Remap.all(this).entries.toList()
        showPage(getString(R.string.section_keys),
            listOf(page(getString(R.string.key_add)) { learnKey() }) + keys.map { (id, e) ->
                page(Remap.name(this, id), Remap.describe(this, e.short.ifEmpty { e.long })) { keyPage(id) }
            }, focus) { renderSettings(R.string.section_keys) }
    }

    /** Waits for the next key press (Back and the arrows are left alone so the page can always be left). */
    private fun learnKey() {
        if (HomeOverride.running == null) return Toast.makeText(this, getString(R.string.home_on_hint), Toast.LENGTH_LONG).show()
        val hint = label(getString(R.string.key_press_prompt), 15f).apply {
            maxLines = 5
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        Remap.learn = { e ->
            val id = Remap.id(e) // read now: the framework recycles the event as soon as the service returns
            ui.post { Remap.learn = null; keyPage(id) }
        }
        showPage(getString(R.string.key_add), emptyList(), top = hint) { Remap.learn = null; pickKeys() }
        pageExit = { Remap.learn = null }
    }

    private fun keyPage(id: String, focus: Int = 0) {
        val e = Remap.all(this)[id] ?: Remap.Entry("", "")
        showPage(Remap.name(this, id), listOf(
            page(getString(R.string.key_short), Remap.describe(this, e.short)) { pickAction(id, false) },
            page(getString(R.string.key_long), Remap.describe(this, e.long)) { pickAction(id, true) },
            page(getString(R.string.key_remove)) { Remap.put(this, id, "", ""); pickKeys() },
        ), focus) { pickKeys() }
    }

    /** Saves [action] as what [id] does on a press (or a hold) and returns to the key's page. */
    private fun setAction(id: String, hold: Boolean, action: String) {
        val e = Remap.all(this)[id] ?: Remap.Entry("", "")
        if (hold) Remap.put(this, id, e.short, action) else Remap.put(this, id, action, e.long)
        keyPage(id, if (hold) 1 else 0)
    }

    private fun pickAction(id: String, hold: Boolean) {
        val e = Remap.all(this)[id] ?: Remap.Entry("", "")
        val now = if (hold) e.long else e.short
        fun row(action: String, name: String) = page(name, if (action == now) CHOSEN else "") { setAction(id, hold, action) }
        val rows = listOf(
            row("", getString(R.string.key_unset)),
            row("none", getString(R.string.key_block)),
            page(getString(R.string.key_open_app), if (now.startsWith("app:") || now.startsWith("hdmi:")) Remap.describe(this, now) else "") {
                pickApp(id, hold, now)
            },
        ) + Remap.MEDIA.map { (k, v) -> row("media:$k", getString(v.second)) } +
            Remap.SYS.map { (k, v) -> row("sys:$k", getString(v.second)) } +
            Remap.OPEN.map { (k, v) -> row("open:$k", getString(v.second)) }
        showPage(getString(if (hold) R.string.key_long else R.string.key_short), rows) { keyPage(id, if (hold) 1 else 0) }
    }

    private fun pickApp(id: String, hold: Boolean, now: String) {
        // Every input counts, with or without a signal: sortedApps() has only the ones pinned to the row.
        showPage(getString(R.string.key_open_app), (sources + sortedApps().filter { !it.pkg.startsWith(HDMI) }).map { a ->
            val action = if (a.pkg.startsWith(HDMI)) "hdmi:" + a.pkg.removePrefix(HDMI) else "app:" + a.pkg
            page(a.label, if (action == now) CHOSEN else "") { setAction(id, hold, action) }
        }) { pickAction(id, hold) }
    }

    /** While the home screen shows, a folder wallpaper moves on to its next photo when its time is up. */
    private val wallpaperTick = object : Runnable {
        override fun run() {
            val every = photoEvery() * 60_000L
            if (!currentWallpaper().startsWith("folder:")) return
            val wait = prefs.getLong(photoAtKey(), 0) + every - System.currentTimeMillis()
            if (wait <= 0) nextPhoto()
            ui.postDelayed(this, if (wait <= 0) every else wait)
        }
    }

    private fun nextPhoto() {
        prefs.edit().remove(photoKey()).putLong(photoAtKey(), System.currentTimeMillis()).apply()
        loadWallpaper()
    }

    /** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
    private fun folderPhoto(choice: String): Long? {
        val ids = folderPhotos(contentResolver, choice)
        val all = ids.toHashSet()
        prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
        deck.removeAll { it !in all } // deleted, or another folder's
        if (deck.isEmpty()) deck.addAll(ids.shuffled())
        return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
    }

    /** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
    private fun photoFolders(): List<Triple<String, String, Int>> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = (c.getString(1) ?: "Photos") to (found[id]?.second ?: 0) + 1
            }
        }
        return found.map { (id, v) -> Triple(id, v.first, v.second) }
    }

    private fun pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
        setWallpaper(forDark, choice)
        renderSettings(if (forDark) R.string.dark_wallpaper else R.string.light_wallpaper)
    }

    /** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
    private fun pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
        if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
            afterPhotoAccess = { pickFolder(back, onPick) }
            return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
        }
        val folders = photoFolders()
        if (folders.isEmpty()) {
            return toast(getString(R.string.no_photos), long = true)
        }
        val rows = folders.map { (id, name, n) ->
            page(name, resources.getQuantityString(R.plurals.photo_count, n, n)) { onPick("folder:$id:$name") }
        }
        showPage(getString(R.string.choose_folder), rows, back = back)
    }

    private fun removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
        showPage(getString(R.string.remove_photo_title), own.map { (name, choice) ->
            page(name) {
                showPage(getString(R.string.delete_photo, name), listOf(
                    page(getString(R.string.cancel)) { removeWallpaper(own, forDark) },
                    page(getString(R.string.delete)) {
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                            if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                        }.apply()
                        loadWallpaper()
                        pickWallpaper(forDark)
                    },
                )) { removeWallpaper(own, forDark) }
            }
        }) { pickWallpaper(forDark) }
    }

    private var upload: UploadServer? = null

    /**
     * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
     * wallpaper and show it at once. The server runs only while this panel is open.
     */
    private fun uploadWallpaper(forDark: Boolean) {
        val ip = lanAddress() ?: return toast(getString(R.string.connect_network), long = true)
        val dir = wallpaperDir() ?: return
        val status = label(getString(R.string.waiting_photo), 14f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        val server = try {
            UploadServer((1000..9999).random().toString(), File(cacheDir, "upload.tmp")) { file ->
                val saved = importWallpaper(file, dir) ?: return@UploadServer null
                ui.post {
                    setWallpaper(forDark, "file:${saved.name}")
                    status.text = getString(R.string.photo_added, saved.nameWithoutExtension)
                }
                saved.nameWithoutExtension
            }
        } catch (e: IOException) {
            return toast(getString(R.string.upload_failed), long = true)
        }
        upload = server
        val url = "http://$ip:${server.port}/${server.code}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(200))) })
            addView(label(url, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.scan_hint), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
            })
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
        }
        showPage(getString(R.string.add_from_your_phone), listOf(page(getString(R.string.done)) { pickWallpaper(forDark) }), top = box) { pickWallpaper(forDark) }
        pageExit = {
            server.stop()
            if (upload === server) upload = null
        }
    }

    /** The TV's own address on the home network (IPv4: easy to read and type). */
    private fun lanAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    private val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    private val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    private fun openLauncherSettings(at: Int = lastSettings) {
        showPanel()
        renderSettings(at)
    }

    private fun showPanel() {
        panelFrom = currentFocus
        // Dark: true black, set apart from the home screen by a hairline on its left edge.
        showPanelGlass()
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        for (id in listOf(R.id.settingsCrumb, R.id.settingsHelp)) findViewById<TextView>(id).apply { setTextColor(fg); secondary(0.6f) }
        settingsScrim.background = spotlight.apply { reset() }
        pages.clear()
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
    }

    /** The panel is frosted glass over the wallpaper when Glass is on, and the plain surface otherwise. */
    private fun showPanelGlass() {
        val panel = findViewById<GlassBox>(R.id.settingsPanel)
        if (frosted()) {
            panel.background = null
            panel.setWillNotDraw(false)
            styleGlass(panel.glass, dp(22).toFloat(), flatRight = true)
            settingsScrim.setBackgroundColor(0x55000000)
        } else {
            panel.setWillNotDraw(true)
            panel.background = if (!dark) ColorDrawable(0xFFF7F8FA.toInt())
                else LayerDrawable(arrayOf(ColorDrawable(DARK_BG), ColorDrawable(PANEL_EDGE))).apply {
                    setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
                    setLayerWidth(1, 1)
                }
            settingsScrim.setBackgroundColor(0x99000000.toInt())
        }
    }

    private fun closeLauncherSettings() {
        pageExit?.invoke()
        pageExit = null
        pageBack = null
        settingsScrim.visibility = View.GONE
        homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
        (panelFrom?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.launcherSettings)).requestFocus()
        panelFrom = null
    }

    private var panelFrom: View? = null // focus to go back to when the panel closes

    /**
     * Plume settings, as a short list of sections that each open a page. [at] is the string id of the row to
     * focus: a row inside a section opens that section on it; a section or a top-level row focuses it in the
     * main list. Rows are found by name, so adding one never moves the others.
     */
    private fun renderSettings(at: Int = 0) {
        val theme = prefs.getString("theme", "light")!!
        val sort = prefs.getString("sort", "manual")!!
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
        val weather = prefs.getString("weather", "off")!!
        val unit = if (weather == "off") prefs.getString("wx_unit", "c")!! else weather
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } }
        val chFlip = prefs.getStringSet("ch_flip", emptySet())!!
        val channelsOn = (if (WATCH_NEXT in chFlip) 0 else 1) + appChannels().distinctBy { (_, p, n) -> channelKey(p, n) }.count { (_, p, n) -> channelOn(n, channelKey(p, n), chFlip) }
        val names = rowNames()
        // Rows with more than two values also step back with Left; two-value rows flip either way.
        val steps = mutableMapOf<Int, (Int) -> Unit>(
            R.string.icon_style to { d ->
                prefs.edit().putString("icon_style", ICON_STYLES.keys.toList().step(iconStyle(), d)).apply()
                renderApps()
                renderSettings(R.string.icon_style)
            },
            R.string.theme to { d ->
                prefs.edit().putString("theme", listOf("light", "dark", "auto").step(theme, d)).putBoolean("reopen_settings", true).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            R.string.row_position to { d ->
                prefs.edit().putString("row_align", ROW_ALIGN.keys.toList().step(rowAlign(), d)).apply()
                alignRow()
                renderSettings(R.string.row_position)
            },
            R.string.fade_top to { d -> stepFade("fade_top", d); renderSettings(R.string.fade_top) },
            R.string.fade_bottom to { d -> stepFade("fade_bottom", d); renderSettings(R.string.fade_bottom) },
            R.string.app_names to { d ->
                prefs.edit().putString("names", listOf("focus", "always", "never").step(names, d)).apply()
                renderApps()
                renderSettings(R.string.app_names)
            },
            R.string.show_row to { d ->
                prefs.edit().putString("row_show", ROW_SHOW.keys.toList().step(rowShow(), d)).apply()
                placeRow(animate = false)
                renderSettings(R.string.show_row)
            },
            R.string.cards_rows to { d ->
                prefs.edit().putString("cards_rows", CARDS_ROWS.keys.toList().step(cardsMode(), d)).apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(R.string.cards_rows)
            },
            R.string.apps_when_hidden to { _ ->
                prefs.edit().putBoolean("row_drop", !rowDrops()).apply()
                placeRow(animate = false)
                renderSettings(R.string.apps_when_hidden)
            },
            R.string.show_icons to { _ ->
                prefs.edit().putBoolean("cw_colour", !cwColour()).apply()
                watchKey = null // rebuild the cards with the new source icons
                showWatchNext(Cards(cardSources, cardCurrent, watchList))
                renderSettings(R.string.show_icons)
            },
        )
        val rows = linkedMapOf<Int, Triple<String, String, () -> Unit>>(
            R.string.edit_apps_row to Triple(getString(R.string.edit_apps_row), "") { closeLauncherSettings(); startEdit(grab = null) },
            R.string.theme to Triple(getString(R.string.theme), getString(mapOf("light" to R.string.theme_light, "dark" to R.string.theme_dark, "auto" to R.string.theme_auto)[theme] ?: R.string.theme_light)) {
                steps[R.string.theme]!!(1)
            },
            R.string.light_wallpaper to Triple(getString(R.string.light_wallpaper), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            R.string.dark_wallpaper to Triple(getString(R.string.dark_wallpaper), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            R.string.glass to Triple(getString(R.string.glass), toggle(glassOn())) {
                prefs.edit().putBoolean("glass", !glassOn()).putBoolean("reopen_settings", true).apply()
                recreate() // tile colours are applied once per activity; reopen the panel after
            },
            // Select steps up like Right, and past 100% starts again at 0%.
            R.string.fade_top to Triple(getString(R.string.fade_top), "$SLIDER${fade("fade_top")}") { steps[R.string.fade_top]!!(if (fade("fade_top") == 100) -100 / FADE_STEP else 1) },
            R.string.fade_bottom to Triple(getString(R.string.fade_bottom), "$SLIDER${fade("fade_bottom")}") { steps[R.string.fade_bottom]!!(if (fade("fade_bottom") == 100) -100 / FADE_STEP else 1) },
            R.string.app_animations to Triple(getString(R.string.app_animations), toggle(animationsOn())) {
                prefs.edit().putBoolean("app_anim", !animationsOn()).apply()
                renderSettings(R.string.app_animations)
            },
            R.string.folder_photo_changes to Triple(getString(R.string.folder_photo_changes), getString(PHOTO_EVERY[photoEvery()]!!)) { pickPhotoEvery() },
            R.string.sort_apps to Triple(getString(R.string.sort_apps), if (sort == "manual") getString(R.string.manual) else getString(R.string.most_used)) {
                prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(R.string.sort_apps)
            },
            R.string.apps_in_row to Triple(getString(R.string.apps_in_row), if (rowHidden == 0) getString(R.string.all) else getString(R.string.n_of_m, apps.size - rowHidden, apps.size)) { pickRowApps() },
            R.string.row_position to Triple(getString(R.string.row_position), getString(ROW_ALIGN[rowAlign()]!!.first)) {
                steps[R.string.row_position]!!(1)
            },
            R.string.app_names to Triple(getString(R.string.app_names), mapOf("focus" to getString(R.string.on_focus), "always" to getString(R.string.always), "never" to getString(R.string.never))[names]!!) {
                steps[R.string.app_names]!!(1)
            },
            R.string.icon_style to Triple(getString(R.string.icon_style), getString(ICON_STYLES.getValue(iconStyle()))) {
                steps[R.string.icon_style]!!(1)
            },
            R.string.restore_hidden to Triple(getString(R.string.restore_hidden), if (hidden == 0) getString(R.string.none_hidden) else resources.getQuantityString(R.plurals.show_again, hidden, hidden)) {
                prefs.edit().remove("hidden_watch").apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(R.string.restore_hidden)
            },
            R.string.cw_apps to Triple(getString(R.string.cw_apps), if (cwOff == 0) getString(R.string.all_on) else getString(R.string.n_off, cwOff)) { pickWatchSources() },
            R.string.cw_channels to Triple(getString(R.string.cw_channels), getString(R.string.n_on, channelsOn)) { pickChannels() },
            R.string.row_name to Triple(getString(R.string.row_name), rowName()) { pickRowName() },
            R.string.show_icons to Triple(getString(R.string.show_icons), getString(if (cwColour()) R.string.colour else R.string.monochrome)) {
                steps[R.string.show_icons]!!(1)
            },
            R.string.show_row to Triple(getString(R.string.show_row), getString(ROW_SHOW.getValue(rowShow()))) { steps[R.string.show_row]!!(1) },
            R.string.cards_rows to Triple(getString(R.string.cards_rows), getString(CARDS_ROWS.getValue(cardsMode()))) { steps[R.string.cards_rows]!!(1) },
            R.string.apps_when_hidden to Triple(getString(R.string.apps_when_hidden), getString(if (rowDrops()) R.string.moves_down else R.string.stays_put)) {
                steps[R.string.apps_when_hidden]!!(1)
            },
            // "weather" is off/c/f as before; "wx_unit" keeps the unit while it's off.
            R.string.weather to Triple(getString(R.string.weather), toggle(weather != "off")) {
                if (weather == "off") prefs.edit().putString("weather", unit).apply()
                else prefs.edit().putString("wx_unit", weather).putString("weather", "off").apply()
                showWeather()
                ui.removeCallbacks(weatherTick)
                ui.post(weatherTick)
                renderSettings(R.string.weather)
            },
            R.string.weather_units to Triple(getString(R.string.weather_units), if (unit == "f") "°F" else "°C") {
                val next = if (unit == "f") "c" else "f"
                prefs.edit().putString("wx_unit", next).putString("weather", if (weather == "off") "off" else next).apply()
                showWeather()
                renderSettings(R.string.weather_units)
            },
            R.string.weather_location to Triple(getString(R.string.weather_location), if (manualCity()) prefs.getString("geo_city", null) ?: "" else "Automatic") { pickCity() },
            R.string.screensaver to Triple(getString(R.string.screensaver), when {
                downloading -> getString(R.string.downloading_gallery)
                !galleryOn() -> screensaverName()
                else -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.gallery)
            }) { pickScreensaver() },
            R.string.test_screensaver to Triple(getString(R.string.test_screensaver), "") { testScreensaver() },
            R.string.screensaver_timing to Triple(getString(R.string.screensaver_timing), "") { openScreensaverSettings() },
            R.string.setup_home to Triple(getString(R.string.setup_home), "") { closeLauncherSettings(); startHomeSetup() },
            R.string.home_button to Triple(getString(R.string.home_button), toggle(homeServiceOn())) { toggleHomeButton() },
            R.string.smooth_volume to Triple(getString(R.string.smooth_volume), toggle(prefs.getBoolean("smooth_volume", false))) {
                prefs.edit().putBoolean("smooth_volume", !prefs.getBoolean("smooth_volume", false)).commit()
                HomeOverride.running?.applyKeyFilter()
                renderSettings(R.string.smooth_volume)
            },
            R.string.default_launcher to Triple(getString(R.string.default_launcher), if (isDefaultHome()) getString(R.string.plume_press_change) else getString(R.string.press_to_set)) { makeDefaultHome() },
            R.string.updates to Triple(getString(R.string.updates), when {
                updating -> getString(R.string.updating)
                checking -> getString(R.string.checking)
                availableUpdate() != null -> getString(R.string.update_available_row, availableUpdate())
                notesUnseen() -> getString(R.string.whats_new_title, myVersion())
                else -> getString(R.string.up_to_date_row, myVersion())
            }) { showUpdates() },
            R.string.lock_layout to Triple(getString(R.string.lock_layout), toggle(lockOn())) {
                if (!lockOn()) return@Triple setLock()
                prefs.edit().remove("lock_pin").apply()
                renderSettings(R.string.lock_layout)
            },
        )
        for (k in listOf(R.string.sort_apps, R.string.weather_units)) steps[k] = { rows[k]!!.third() }
        // Like the layout: behind the PIN. unlock() closes the panel after the PIN, so open it again.
        rows[R.string.section_keys] = page(getString(R.string.section_keys), "${keysSummary()}  ›") {
            unlock(back = { renderSettings(R.string.section_keys) }) {
                if (settingsScrim.visibility != View.VISIBLE) showPanel()
                pickKeys()
            }
        }
        val on = { b: Boolean -> getString(if (b) R.string.on else R.string.off) }
        // The fades are for any photo; the timer only for folders, which change photo.
        val walls = listOf(currentWallpaper(false), currentWallpaper(true))
        val photo = walls.any { !it.startsWith("res:") && !it.startsWith("plain:") }
        val folder = walls.any { it.startsWith("folder:") }
        // In the order things sit on the home screen, top to bottom, then what isn't on it.
        val sections = listOf(
            Triple(R.string.section_appearance, "${rows[R.string.theme]!!.second} · ${wallpaperName(currentWallpaper(dark))}",
                listOfNotNull(R.string.theme, R.string.light_wallpaper, R.string.dark_wallpaper, R.string.fade_top.takeIf { photo },
                    R.string.fade_bottom.takeIf { photo }, R.string.glass, R.string.app_animations,
                    R.string.folder_photo_changes.takeIf { folder })),
            Triple(R.string.section_clock_weather, if (weather == "off") on(false) else "${rows[R.string.weather_units]!!.second} · ${rows[R.string.weather_location]!!.second}",
                listOf(R.string.weather, R.string.weather_units, R.string.weather_location)),
            Triple(R.string.section_app_row, "${rows[R.string.apps_in_row]!!.second} · ${rows[R.string.row_position]!!.second}",
                listOf(R.string.edit_apps_row, R.string.apps_in_row, R.string.sort_apps, R.string.icon_style, R.string.app_names, R.string.row_position)),
            // "Apps when row hides" only means something once the row can hide.
            Triple(R.string.bottom_row, rowName(), listOfNotNull(R.string.show_row, R.string.apps_when_hidden.takeIf { rowShow() != "always" },
                R.string.row_name, R.string.show_icons, R.string.cw_apps, R.string.cw_channels, R.string.cards_rows, R.string.restore_hidden)),
            Triple(R.string.section_screensaver, rows[R.string.screensaver]!!.second, listOf(R.string.screensaver, R.string.test_screensaver, R.string.screensaver_timing)),
            Triple(R.string.section_remote_home, on(homeServiceOn() || isDefaultHome()),
                listOf(R.string.home_button, R.string.smooth_volume, R.string.default_launcher, R.string.setup_home, R.string.section_keys)),
            Triple(R.string.section_lock, on(lockOn()), listOf(R.string.lock_layout)),
        )
        val section = sections.firstOrNull { at in it.third }
        if (section != null) {
            lastSettings = section.first
            val keys = section.third
            val noteText = when {
                section.first == R.string.section_lock -> getString(R.string.lock_note)
                section.first == R.string.section_appearance && theme == "auto" -> autoNote()
                else -> null
            }
            val note = if (noteText == null) null else label(noteText, 15f).apply {
                maxLines = 5
                setPadding(dp(14), dp(4), dp(14), dp(10))
            }
            // Two or three choices show side by side; the row's value is the picked one's name.
            val choices = mapOf(
                R.string.theme to listOf(R.string.theme_light to R.drawable.ic_sun, R.string.theme_dark to R.drawable.ic_moon,
                    R.string.theme_auto to R.drawable.ic_auto).map { (s, i) -> getString(s) to i },
                R.string.sort_apps to listOf(R.string.manual, R.string.most_used).map { getString(it) to null },
                R.string.app_names to listOf(R.string.on_focus, R.string.always, R.string.never).map { getString(it) to null },
                R.string.row_position to ROW_ALIGN.values.map { getString(it.first) to null },
                R.string.show_row to ROW_SHOW.values.map { getString(it) to null },
                R.string.cards_rows to CARDS_ROWS.values.map { getString(it) to null },
                R.string.apps_when_hidden to listOf(R.string.stays_put, R.string.moves_down).map { getString(it) to null },
                R.string.show_icons to listOf(R.string.monochrome, R.string.colour).map { getString(it) to null },
                R.string.weather_units to listOf("°C" to null, "°F" to null),
            )
            val walls = mapOf(R.string.light_wallpaper to false, R.string.dark_wallpaper to true)
            val styles = keys.map { k ->
                val forDark = walls[k]
                RowStyle(choices = choices[k], help = ROW_HELP[k]?.let { getString(it) }, area = SECTION_AREAS[section.first],
                    thumb = forDark?.let { currentWallpaper(it) to it }, sub = forDark?.let { wallpaperName(currentWallpaper(it)) })
            }
            // A wallpaper row shows its picture and name instead of the name alone.
            val shown = keys.map { k -> rows[k]!!.let { if (k in walls) page(it.first, "›", it.third) else it } }
            return showPage(getString(section.first), shown, keys.indexOf(at), top = note, steps = keys.map { steps[it] }, styles = styles,
                crumb = getString(R.string.plume_settings)) {
                renderSettings(section.first)
            }
        }
        // Until Home opens Plume one way or the other, a card on top leads to the setup; not after Home was turned off on purpose.
        val top = listOfNotNull(if (homeSetUp() || prefs.getBoolean("home_off", false)) null
                else Triple(R.string.finish_setup, page(getString(R.string.finish_setup), "›") { closeLauncherSettings(); startHomeSetup() },
                    RowStyle(R.drawable.ic_home, getString(R.string.finish_setup_value), help = getString(R.string.help_finish_setup)))) +
            sections.map { (key, value, keys) ->
                Triple(key, page(getString(key), "›") { renderSettings(keys[0]) }, RowStyle(SECTION_ICONS[key], value, help = ROW_HELP[key]?.let { getString(it) }, area = SECTION_AREAS[key]))
            } +
            Triple(R.string.updates, rows[R.string.updates]!!.let { (t, v, a) -> page(t, "›", a) },
                RowStyle(R.drawable.ic_update, rows[R.string.updates]!!.second, help = getString(R.string.help_updates))) +
            listOfNotNull(if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
                else Triple(R.string.preview_home_setup, page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() }, null))
        showPage(getString(R.string.plume_settings), top.map { it.second }, maxOf(0, top.indexOfFirst { it.first == at }), styles = top.map { it.third }, back = null)
    }

    private var lastSettings = 0 // section used last, focused when settings open again

    /** What Auto does here today, under Wallpaper & theme. Times in the TV's 12- or 24-hour format. */
    private fun autoNote(): String {
        val time = DateFormat.getTimeFormat(this)
        val place = sunPlace(prefs) ?: return getString(R.string.auto_note_clock,
            time.format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0) }.time),
            time.format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0) }.time))
        val city = prefs.getString("geo_city", null).orEmpty().ifEmpty { getString(R.string.your_area) }
        // Today's noon, not now: just after midnight, now's nearest sun day is still yesterday's.
        val noon = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }.timeInMillis
        return when (val d = Sun.day(place.first, place.second, noon)) {
            Sun.Day.AlwaysUp -> getString(R.string.auto_note_up, city)
            Sun.Day.AlwaysDown -> getString(R.string.auto_note_down, city)
            is Sun.Day.Times -> getString(R.string.auto_note_sun, city, time.format(Date(d.rise)), time.format(Date(d.set)))
        }
    }

    private fun <T> List<T>.step(current: T, dir: Int) = this[((indexOf(current) + dir) % size + size) % size]

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Plume's look. Back goes up one page; from the main list it closes the panel.

    private var pageBack: (() -> Unit)? = null
    private var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)

    private fun page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

    private fun showPage(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null,
                         steps: List<((Int) -> Unit)?> = emptyList(), styles: List<RowStyle?> = emptyList(), crumb: String? = null,
                         back: (() -> Unit)?) {
        pageExit?.invoke()
        pageExit = null
        pageBack = back
        // Deeper pages slide in from the right, ones already visited (Back) from the left; a redraw of the same page stays put.
        // With the crumb, so a section and a page inside it with the same name (Screensaver) count as two pages.
        val key = "$crumb › $title"
        val dir = when {
            pages.lastOrNull() == key -> 0
            key in pages -> -1.also { while (pages.last() != key) pages.removeAt(pages.size - 1) }
            else -> (if (pages.isEmpty()) 0 else 1).also { pages.add(key) }
        }
        if (dir != 0) for (v in listOf<View>(settingsList, findViewById(R.id.settingsTitle), findViewById(R.id.settingsCrumb))) {
            v.translationX = dp(32) * dir.toFloat()
            v.alpha = 0f
            v.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(DECELERATE).start()
        }
        findViewById<TextView>(R.id.settingsTitle).text = title
        findViewById<TextView>(R.id.settingsCrumb).apply {
            text = crumb?.let { "$it  ›" }
            visibility = if (crumb == null) View.GONE else View.VISIBLE
        }
        showHelp(null)
        settingsList.removeAllViews()
        top?.let { settingsList.addView(it) }
        val first = settingsList.childCount
        rows.forEachIndexed { i, (name, value, action) ->
            val style = styles.getOrNull(i)
            settingsList.addView(settingsRow(name, value, steps.getOrNull(i), style, action),
                LinearLayout.LayoutParams(MATCH_PARENT, dp(if (style?.sub != null) 54 else 48)).apply { topMargin = dp(4) })
        }
        // Focus wraps: Up from the first row goes to the last, Down from the last to the first.
        (settingsList.parent as View).scrollTo(0, 0)
        if (rows.isEmpty()) return // a page that's all [top], like the PIN boxes, focuses itself
        val firstRow = settingsList.getChildAt(first)
        val lastRow = settingsList.getChildAt(first + rows.size - 1)
        firstRow.nextFocusUpId = lastRow.id
        lastRow.nextFocusDownId = firstRow.id
        settingsList.getChildAt(first + focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    // ---------- layout lock ----------
    // A PIN in front of Plume settings, the Wi-Fi and Android settings icons, and everything that changes the home
    // screen; apps still open as usual. It keeps the layout safe from small hands, it doesn't keep apps from kids:
    // voice search and the remote's Settings button get past it, so it's called a layout lock, not parental controls.

    private fun lockOn() = prefs.contains("lock_pin")

    private var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    private var pinFails = 0
    private var pinWaitUntil = 0L // ponytail: in memory, so restarting Plume resets the wait; fine for kids at a remote
    private var pinBox: View? = null

    private fun locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

    /** Runs [then] at once when unlocked, otherwise after the right PIN. [back] is where Back goes from the PIN page. */
    private fun unlock(back: (() -> Unit)? = null, then: () -> Unit) {
        if (!locked()) return then()
        if (settingsScrim.visibility != View.VISIBLE) showPanel() // from inside the panel, keep where its focus returns to
        pinPage(getString(R.string.enter_pin), back = back) { pin ->
            val now = SystemClock.elapsedRealtime()
            if (now < pinWaitUntil) return@pinPage getString(R.string.too_many_tries, (pinWaitUntil - now) / 1000 + 1)
            if (!pinMatches(prefs.getString("lock_pin", null)!!, pin)) {
                if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.wrong_pin)
                pinFails = 0
                pinWaitUntil = now + 30_000L
                return@pinPage getString(R.string.too_many_tries, 30)
            }
            pinFails = 0
            unlockedUntil = now + UNLOCKED_MS
            val under = pages.dropLast(1) // the pages below the PIN; if then() opens the panel again, Back still slides left
            closeLauncherSettings()
            then()
            if (settingsScrim.visibility == View.VISIBLE) pages.addAll(0, under)
            null
        }
    }

    private fun setLock(): Unit = pinPage(getString(R.string.choose_pin), { renderSettings(R.string.lock_layout) }) { first ->
        pinPage(getString(R.string.enter_again), { renderSettings(R.string.lock_layout) }) { again ->
            if (again != first) {
                toast(getString(R.string.pins_mismatch), long = true)
                setLock()
                return@pinPage null
            }
            prefs.edit().putString("lock_pin", hashPin(first)).apply()
            unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
            renderSettings(R.string.lock_layout)
            toast(getString(R.string.layout_locked), long = true)
            null
        }
        null
    }

    /**
     * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
     * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
     */
    private fun pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
        val digits = arrayOfNulls<Int>(4)
        var at = 0
        val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
        val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        }
        fun draw() = boxes.forEachIndexed { i, b ->
            // Only the box being changed shows its digit.
            b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
            b.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                if (i == at) setStroke(dp(2), fg)
            }
            if (dark) b.background = rimCard(dp(8), i == at)
        }
        fun submit() {
            val error = done(digits.joinToString("")) ?: return
            digits.fill(null)
            at = 0
            draw()
            status.text = error
            ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
        }
        row.setOnKeyListener { _, code, e ->
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> null
            }
            if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                    else if (digits[at] != null) at = minOf(3, at + 1)
                else -> {
                    digits[at] = digit
                    if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                    at++
                }
            }
            draw()
            true
        }
        draw()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
            addView(row)
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
            addView(label(getString(R.string.pin_hint), 12f).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                alpha = 0.6f
                setPadding(0, dp(8), 0, 0)
            })
            if (lockOn()) addView(label(getString(R.string.pin_forgot), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
                setPadding(0, dp(24), 0, 0)
            })
        }
        showPage(title, emptyList(), top = box, back = back)
        pinBox = row
        row.requestFocus()
    }

    /** A settings row; with [step], Left and Right change its value in place, and ‹ › show that while focused. */
    private val pages = ArrayList<String>() // crumb and title of the settings pages open, from the main list down
    private val spotlight by lazy { Spotlight() }

    /**
     * The settings panel's dim over the home screen, with a soft window over [area]'s part of it: the part the
     * selected section changes. It glides from part to part; null dims evenly. Animators follow the TV's animation scale.
     */
    private inner class Spotlight : Drawable() {
        private val hole = RectF()
        private var open = 0f // 0: even dim, 1: the window fully clear
        private var anim: ValueAnimator? = null
        private val path = Path()

        fun reset() {
            anim?.cancel()
            open = 0f
            invalidateSelf()
        }

        fun show(target: RectF?) {
            anim?.cancel()
            val from = RectF(hole)
            val fromOpen = open
            // Coming from an even dim, the window opens in place rather than flying in from the last spot.
            if (target != null && open == 0f) from.set(target)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = DECELERATE
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    if (target != null) hole.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
                        from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t)
                    open = fromOpen + ((if (target == null) 0f else 1f) - fromOpen) * t
                    invalidateSelf()
                }
                start()
            }
        }

        override fun draw(c: Canvas) {
            val dim = SCRIM_ALPHA shl 24
            if (open == 0f) return c.drawColor(dim)
            path.reset()
            path.addRoundRect(hole, dp(16).toFloat(), dp(16).toFloat(), Path.Direction.CW)
            c.save()
            c.clipOutPath(path)
            c.drawColor(dim)
            c.restore()
            c.save()
            c.clipPath(path)
            c.drawColor((SCRIM_ALPHA * (1 - open)).toInt() shl 24) // the window clears as it opens
            c.restore()
        }

        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Where [area] sits on the screen, with some room around it; null when it has no part on screen. */
    private fun areaBounds(area: Int?): RectF? {
        val views = when (area) {
            // Past the edges by the window's corner radius, so the round corners fall off screen and no corner stays dim.
            AREA_ALL -> return RectF(0f, 0f, settingsScrim.width.toFloat(), settingsScrim.height.toFloat()).apply { inset(-dp(16).toFloat(), -dp(16).toFloat()) }
            AREA_CLOCK -> listOf(findViewById<View>(R.id.clock).parent as View)
            AREA_APPS -> listOf(findViewById(R.id.appScroll))
            // "On Down" hides the row by sliding it out, still VISIBLE, so isShown below can't tell.
            AREA_SHOWS -> if (rowShow() == "down" && !rowOut) return null
                else listOf(findViewById(R.id.watchTitle), findViewById<View>(R.id.watchScroll))
            else -> return null
        }.filter { it.isShown && it.height > 0 }
        if (views.isEmpty()) return null
        val at = IntArray(2)
        settingsScrim.getLocationInWindow(at)
        val (ox, oy) = at[0] to at[1]
        return views.map { v -> v.getLocationInWindow(at); RectF((at[0] - ox).toFloat(), (at[1] - oy).toFloat(), (at[0] - ox + v.width).toFloat(), (at[1] - oy + v.height).toFloat()) }
            .reduce { a, b -> a.apply { union(b) } }.apply { inset(-dp(14).toFloat(), -dp(10).toFloat()) }
    }

    /**
     * Extras for a settings row: an [icon] in front, a second line ([sub]), [choices] shown side by side (name and
     * icon; the row's value says which is picked), a wallpaper picture ([thumb], a wallpaper choice) and a [help] line.
     */
    private class RowStyle(val icon: Int? = null, val sub: String? = null, val choices: List<Pair<String, Int?>>? = null,
                           val thumb: Pair<String, Boolean>? = null, val help: String? = null, val area: Int? = null)

    private fun settingsRow(title: String, value: String, step: ((Int) -> Unit)? = null, style: RowStyle? = null, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            val icon = style?.icon?.let { res -> ImageView(context).apply { setImageResource(res); setColorFilter(if (dark) text2() else fg); if (!dark) alpha = 0.7f } }
            icon?.let { addView(it, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) }) }
            val name = label(title, 15f).apply { if (dark) setTextColor(text2()) }
            addView(style?.sub?.let { sub ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(label(sub, 13f).apply { secondary(0.6f) })
                }
            } ?: name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val pct = value.takeIf { it.startsWith(SLIDER) }?.removePrefix(SLIDER)?.toInt()
            val text = pct?.let { "$it%" } ?: value
            if (pct != null) addView(slider(pct), LinearLayout.LayoutParams(dp(SLIDER_DP), dp(14)).apply { marginStart = dp(12) })
            val shown = if (style?.choices != null || value == TOGGLE_ON || value == TOGGLE_OFF) null else label(text, 14f).apply { secondary(0.6f) }
            val wrap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            style?.thumb?.let { (choice, forDark) -> addView(wallpaperThumb(choice, forDark), LinearLayout.LayoutParams(dp(64), dp(36)).apply { marginStart = dp(12) }) }
            when {
                style?.choices != null -> addView(choices(style.choices, value), wrap.apply { marginStart = dp(12) })
                // A slider's value gets a fixed width, room for "‹  100%  ›", so the slider stays put as it changes and on focus.
                pct != null -> addView(shown!!.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(SLIDER_VALUE_DP), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                shown == null -> addView(switch(value == TOGGLE_ON), LinearLayout.LayoutParams(dp(36), dp(20)))
                else -> addView(shown, wrap.apply { if (value.isNotEmpty()) marginStart = dp(16) }) // room between a long title and its value
            }
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                // Focus: a lit rimCard in dark (and the title brightens), a white pill in light.
                if (dark) background = if (focused) rimCard(dp(8), true) else bg
                else bg.colors = if (focused) intArrayOf(Color.WHITE, Color.WHITE) else intArrayOf(0, 0)
                // The title glides to bright rather than snapping, so moving through the list feels smooth.
                if (dark) ValueAnimator.ofArgb(name.currentTextColor, if (focused) DARK_TEXT else text2()).apply {
                    duration = 160
                    interpolator = DECELERATE
                    addUpdateListener { a ->
                        val c = a.animatedValue as Int
                        name.setTextColor(c)
                        icon?.setColorFilter(c)
                    }
                }.start()
                if (step != null) shown?.text = if (focused) "‹  $text  ›" else text
                if (focused) {
                    showHelp(style?.help)
                    // Posted: when the panel first opens, the scrim has no size yet in this frame.
                    settingsScrim.post { if (hasFocus()) spotlight.show(areaBounds(style?.area)) }
                }
            }
            tag = step // dispatchKeyEvent calls it for Left (-1) and Right (+1)
        }
    }

    /** Every choice in a row at once, the picked one filled; Left and Right still step through them. */
    private fun choices(options: List<Pair<String, Int?>>, picked: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setStroke(1, fg and 0xFFFFFF or 0x33000000)
        }
        for ((text, iconRes) in options) addView(label(text, 13f).apply {
            val on = text == picked
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            if (on) {
                setTextColor(if (dark) DARK_BG else Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(if (dark) DARK_TEXT else fg) }
            } else secondary(0.6f)
            iconRes?.let { res ->
                setCompoundDrawablesRelative(getDrawable(res)!!.mutate().apply { setBounds(0, 0, dp(14), dp(14)); setTint(currentTextColor) }, null, null, null)
                compoundDrawablePadding = dp(4)
            }
        })
    }

    /** A small picture of a wallpaper (plain ones are their colour), decoded small off the UI thread. */
    private fun wallpaperThumb(choice: String, forDark: Boolean) = ImageView(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (choice.startsWith("plain:")) (if (forDark) DARK_BG else 0xFFF3F5F7.toInt()) else if (dark) DARK_SURFACE else 0xFFE4E7EB.toInt())
            setStroke(1, fg and 0xFFFFFF or 0x33000000)
        }
        clipToOutline = true
        if (!choice.startsWith("plain:")) {
            // A picture icon while it loads, and if it can't (Bing not downloaded yet, file gone).
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setImageResource(R.drawable.ic_wallpaper)
            setColorFilter(fg and 0xFFFFFF or 0x66000000)
        }
        if (!choice.startsWith("plain:")) io.execute {
            val bmp = try {
                val small = BitmapFactory.Options().apply { inSampleSize = 16 } // 1920 px wide comes out 120: plenty for 64 dp
                when {
                    choice.startsWith("res:") -> BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { BitmapFactory.decodeResource(resources, it, small) }
                    choice == Bing.CHOICE -> BitmapFactory.decodeFile(Bing.file(this@MainActivity).path, small)
                    choice.startsWith("file:") -> BitmapFactory.decodeFile(File(wallpaperDir(), choice.removePrefix("file:")).path, small)
                    // A folder shows the photo it's on now (the other theme's has none yet: its first photo); Android has a ready thumbnail.
                    choice.startsWith("folder:") -> (prefs.getLong(photoKey(forDark), -1).takeIf { it >= 0 } ?: folderPhotos(contentResolver, choice).firstOrNull())?.let {
                        if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(ContentUris.withAppendedId(PHOTOS, it), android.util.Size(dp(64) * 2, dp(36) * 2), null)
                        else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(contentResolver, it, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) ui.post {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
                clearColorFilter()
                setImageBitmap(bmp)
            }
        }
    }

    private fun showHelp(text: String?) = findViewById<TextView>(R.id.settingsHelp).apply {
        this.text = text // the line keeps its 3 lines when empty, so the list above doesn't jump as focus moves
    }

    private fun toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

    /** A mono slider, like [switch]: a faint track, filled up to [pct] in the text colour, with a knob at the end. */
    private fun slider(pct: Int) = FrameLayout(this).apply {
        val faint = fg and 0xFFFFFF or 0x66000000
        fun bar(color: Int) = View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) } }
        addView(bar(faint), FrameLayout.LayoutParams(MATCH_PARENT, dp(4), Gravity.CENTER_VERTICAL))
        addView(bar(fg), FrameLayout.LayoutParams(dp(SLIDER_DP) * pct / 100, dp(4), Gravity.CENTER_VERTICAL))
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fg) }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL).apply { marginStart = dp(SLIDER_DP - 14) * pct / 100 })
    }

    /** A mono switch for on/off rows: filled track with the knob right when on, outlined and left when off. */
    private fun switch(on: Boolean) = FrameLayout(this).apply {
        val faint = fg and 0xFFFFFF or 0x66000000
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (on) setColor(fg) else setStroke(dp(2), faint)
        }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (!on) faint else if (dark) DARK_BG else Color.WHITE)
            }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    private fun refreshApps() {
        appsDirty = false
        io.execute {
            val list = try {
                loadApps()
            } catch (e: Exception) {
                return@execute
            }
            val inputs = hdmiInputs()
            ui.post {
                if (inputs.isNotEmpty() && !prefs.getBoolean("inputs_moved", false)) moveInputsToSources(inputs)
                apps = list
                APPS = list
                sources = inputs
                SOURCES = inputs
                findViewById<View>(R.id.sourcesButton).visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE
                renderApps()
            }
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .map { a ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                    load = { a.loadIcon(pm) },
                )
            }
    }

    /**
     * One entry per HDMI port, keyed by the port's input id. A device that names itself over CEC (the PS5 shows up
     * as "PlayStation 5") takes over its port's entry; it's listed while asleep too, and opening it wakes it.
     * A port without one is named as in TV settings ("HDMI 2") and says so when it has no signal.
     */
    private fun hdmiInputs(): List<App> = try {
        val tv = getSystemService(TvInputManager::class.java)
        val inputs = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(this) }
        val devices = inputs.filter { it.parentId != null }
        val audio = devices.filter { isAudioDevice(it.loadLabel(this).toString()) }.map { it.parentId }.toSet()
        // Devices first, so distinctBy keeps the device over its port.
        (devices + inputs.filter { it.parentId == null }).filter { (it.parentId ?: it.id) !in audio }.map { input ->
            val port = input.parentId ?: input.id
            val named = input.parentId != null
            val label = (if (named) input.loadLabel(this) else input.loadCustomLabel(this) ?: input.loadLabel(this)).toString()
            App(
                HDMI + port,
                label,
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                getDrawable(HDMI_ICONS[hdmiIcon(label, named)]!!)!!,
                offline = !named && tv.getInputState(port) != TvInputManager.INPUT_STATE_CONNECTED,
            )
        }.distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
        override fun onInputStateChanged(id: String, state: Int) = refreshApps()
    }

    /** HDMI inputs pinned to the home row by hand (hold OK in the Sources list): shown with or without a signal. */
    private fun pinnedInputs() = prefs.getStringSet("inputs_pinned", emptySet())!!

    /** The apps, and the inputs pinned to the row: the entries the row and All apps are made of. */
    private fun listed(): List<App> = apps + sources.filter { it.pkg in pinnedInputs() }

    /** Once: after an update, the inputs in the row stay pinned, and the inputs drop out of row_hidden, where only apps go now. */
    private fun moveInputsToSources(inputs: List<App>) {
        val hidden = rowHiddenSet()
        val info = packageManager.getPackageInfo(packageName, 0)
        val pins = if (info.firstInstallTime == info.lastUpdateTime) pinnedInputs() // a new install: no tile to keep
            else pinsAfterSourcesUpdate(inputs.map { it.pkg to it.offline }, hidden, pinnedInputs())
        prefs.edit()
            .putStringSet("inputs_pinned", pins)
            .putStringSet("row_hidden", hidden.filterNot { it.startsWith(HDMI) }.toSet())
            .putBoolean("inputs_moved", true)
            .apply()
    }

    private fun togglePin(src: App) {
        val pinned = pinnedInputs()
        val on = src.pkg !in pinned
        prefs.edit().putStringSet("inputs_pinned", if (on) pinned + src.pkg else pinned - src.pkg).apply()
        renderApps()
        Toast.makeText(this, getString(if (on) R.string.input_added else R.string.input_removed), Toast.LENGTH_SHORT).show()
    }

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return listed().sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // An input just pinned goes first, so it's visible without scrolling.
        return listed().sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    private fun rowAlign() = prefs.getString("row_align", "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

    /**
     * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
     * centred, the scroller would push its first tiles out of reach.
     */
    private fun alignRow() {
        val content = appRow.parent as View
        (content.layoutParams as FrameLayout.LayoutParams).gravity =
            if (appTileCount() > ROW_TILES) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
        content.requestLayout()
    }

    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        // First fill after a cold start: Android already gave the default focus to the top bar's first icon.
        val firstFill = appRow.childCount == 0 && currentFocus?.parent === wifi.parent
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow || firstFill
        val gap = dp(GRID_GAP)
        val width = rowTileWidth()
        val tileH = if (isBannerStyle(iconStyle())) width * 9 / 16 else dp(76) // 16:9 like a banner
        // The row sits on the bottom of its space. A banner row is shorter, so lift it by half the difference: its
        // centre stays where the card row's is, between the clock and Continue Watching.
        findViewById<View>(R.id.appScroll).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(-24) + (dp(76) - tileH) / 2
            requestLayout()
        }
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        val tiles = sortedApps().filter { inRow(it.pkg, hidden) }.map { appTile(it, labeled = false) }
        if (tiles.isEmpty()) return showNames()
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, tileH).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own.onFocusChange(v, focused)
                if (focused) lastApp = v
                showRowLabel(v, focused)
            }
        }
        showNames()
        alignRow()
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            toast(getString(R.string.tip_move), long = true)
        }
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    private fun rowNames() = prefs.getString("names", "focus")!!

    /** "mono", "colour" or "banner". The old on/off "colour_icons" decides until a style has been picked. */
    private fun iconStyle() = iconStyleOf(prefs.getString("icon_style", null), prefs.getBoolean("colour_icons", false))

    private fun colourIcons() = iconStyle().let { it == "colour" || it == "banner" } // a monochrome banner row keeps the marks grey too

    /** A brand or app colour made deep enough to carry a white mark. */
    private fun deepen(c: Int) = Color.rgb((Color.red(c) * 0.6f).toInt(), (Color.green(c) * 0.6f).toInt(), (Color.blue(c) * 0.6f).toInt())

    /** Width of a home row tile: eight across the screen. */
    private fun rowTileWidth() = (resources.displayMetrics.widthPixels - dp(96) - dp(GRID_GAP) * (ROW_TILES - 1)) / ROW_TILES

    /**
     * Apple TV style: the app's own banner fills the tile, rounded. Only when the banner is at least as wide as the
     * tile, or it would be upscaled and soft (4K); null then, and the tile shows the app's mark as usual.
     */
    private fun bannerTile(a: App): View? {
        if (a.pkg.startsWith(HDMI)) return null
        val banner = try {
            packageManager.getActivityBanner(a.launch)
        } catch (e: Exception) {
            null
        } ?: return null
        val w = rowTileWidth()
        if (banner.intrinsicWidth in 1 until w) return null
        // Drawn once, centre-cropped, at the tile's exact size: a row of banners then costs ~80 KB each, not the
        // banner's own size, and the full-size banner isn't kept.
        val h = w * 9 / 16
        val bw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
        val bh = banner.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = maxOf(w.toFloat() / bw, h.toFloat() / bh)
        val dw = (bw * scale).toInt()
        val dh = (bh * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        (banner as? BitmapDrawable)?.isFilterBitmap = true
        banner.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
        banner.draw(Canvas(bmp))
        // Monochrome banner: grey at rest, in colour while selected, as the marks brighten in dark mode.
        val grey = iconStyle() == "bannermono"
        val rest = if (dark) 0.8f else 1f
        val pic = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            if (grey) { colorFilter = GREYSCALE; alpha = rest }
        }
        // A colour banner has nothing else to tell the selected tile from the others: the rest are dimmed a little,
        // and the selected one gets a ring in the text colour. Both fade over the same 160 ms as the lift.
        val dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = if (grey) 0f else BANNER_DIM }
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(3), fg)
                setColor(Color.TRANSPARENT)
            }
            alpha = 0f
        }
        val col = FrameLayout(this).apply {
            addView(pic, MATCH_PARENT, MATCH_PARENT)
            addView(dim, MATCH_PARENT, MATCH_PARENT)
            addView(ring, MATCH_PARENT, MATCH_PARENT)
            tag = a.pkg
            setOnClickListener { launch(a) }
        }
        style(col, dp(8), card = false, lift = 1.12f) { focused ->
            ring.animate().alpha(if (focused) 1f else 0f).setDuration(160).start()
            if (!grey) dim.animate().alpha(if (focused) 0f else BANNER_DIM).setDuration(160).start()
            if (!grey) return@style
            pic.colorFilter = if (focused) null else GREYSCALE
            pic.animate().alpha(if (focused) 1f else rest).setDuration(160).start()
        }
        return col
    }

    /** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
    private fun appTile(a: App, labeled: Boolean = true): View {
        if (!labeled && isBannerStyle(iconStyle())) bannerTile(a)?.let { it.contentDescription = a.label; return it }
        val logo = LOGOS[a.pkg]
        val custom = customIcon(a.pkg)
        val name = a.label.takeIf { labeled }
        // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
        val colour = colourIcons()
        // A banner style, an app with no banner: its own colour behind its mark in white, so the row doesn't mix pictures
        // and grey squares. HDMI tiles keep the plain mark.
        val onColour = !labeled && isBannerStyle(iconStyle()) && !a.pkg.startsWith(HDMI)
        val t = when {
            custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
            a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg,
                tint = BRAND_COLOURS[hdmiIcon(a.label, true)]?.takeIf { colour }) { launch(a) }
            logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
                wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14),
                tint = if (onColour && BRAND_COLOURS[a.pkg] != null) Color.WHITE else BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
                .also { if (onColour) BRAND_COLOURS[a.pkg]?.let { c -> it.backgroundTintList = ColorStateList.valueOf(deepen(c)) } }
            else -> cachedGlyph(a)?.let { g ->
                val accent = cachedAccent(a)
                tile(name, g, true, a.pkg, tint = if (onColour && accent != null) Color.WHITE else if (colour) accent else null) { launch(a) }
                    .also { if (onColour && accent != null) it.backgroundTintList = ColorStateList.valueOf(deepen(accent)) }
            }
                ?: if (colour || onColour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
                // A copy: greying the shared icon itself would keep it grey after switching to colour.
                else tile(name, (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
        }
        t.contentDescription = a.label
        return t
    }

    /** "App names: Always": a name under every tile, same width and gap as the tiles, so they line up. */
    private fun showNames() {
        val row = findViewById<LinearLayout>(R.id.nameRow)
        val always = rowNames() == "always"
        row.visibility = if (always) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowLabel).visibility = if (always) View.GONE else View.VISIBLE
        row.removeAllViews()
        if (!always) return
        for (i in 0 until appRow.childCount) {
            val t = appRow.getChildAt(i)
            row.addView(fittingLabel(t.contentDescription.toString(), 13f).apply {
                gravity = Gravity.CENTER
                alpha = if (dark) 0.85f else 1f
            }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
        }
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    private fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<FittingLabel>(R.id.rowLabel)
        if (rowNames() == "always") { // the names under every tile: only the selected tile's name scrolls, if it is too wide
            val names = findViewById<LinearLayout>(R.id.nameRow)
            val at = appRow.indexOfChild(v)
            for (i in 0 until names.childCount) (names.getChildAt(i) as? FittingLabel)?.setActive(focused && !editing && i == at)
        }
        if (focused && !editing && rowNames() == "focus") {
            // Name and place change together, or moving quickly shows the next name under the previous tile for a frame.
            // Posted only on first render, when focus lands before layout and every width is still 0. A post that runs
            // after focus moved on is skipped, or it would show this old name while a newer tile is selected.
            val place = {
                label.text = v.contentDescription
                label.box = v.width // the name stays inside its tile: when it is wider it scrolls
                label.translationX = v.left + v.width / 2f - label.width / 2f
                label.setActive(true)
            }
            if (v.width > 0 && label.width > 0) place() else v.post { if (v.isFocused) place() }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        } else {
            ui.post {
                if (currentFocus?.parent !== appRow || editing) {
                    label.animate().alpha(0f).setDuration(120).start()
                    label.setActive(false)
                }
            }
        }
    }

    /** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */
    private fun tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, tint: Int? = null, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mono) setColorFilter(tint ?: fg)
        }
        val text = label?.let {
            fittingLabel(it, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
            }
        }
        // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
        val restLogo = if (dark) 0.8f else 1f
        val restText = if (dark) 0.65f else 1f
        img.alpha = restLogo
        text?.alpha = restText
        style(col, dp(8), card = true) { focused ->
            text?.setActive(focused) // a name too wide for the tile scrolls while it is selected
            if (!dark) return@style
            img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
            text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
        }
        if (text == null) {
            // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
            // (crop the empty top and bottom) or they shrink to a sliver.
            if (wordmarkInset != null) {
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
            } else {
                val v = if (isBannerStyle(iconStyle())) dp(8) else dp(20) // banner rows are shorter
                img.setPadding(dp(16), v, dp(16), v)
            }
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
            col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        col.tag = tag
        col.setOnClickListener { onClick() }
        return col
    }

    /**
     * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
     * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
     * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
     * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
     */
    private fun glyph(icon: Drawable): Bitmap? {
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
        val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
        fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
        fg.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
        val alpha = FloatArray(px.size) { i ->
            val c = px[i]
            Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val peak = alpha.max().takeIf { it > 0f } ?: return null
        // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
        for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
        val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
        val l = on.minOf { it % size }; val r = on.maxOf { it % size }
        val t = on.minOf { it / size }; val b = on.maxOf { it / size }
        val side = maxOf(r - l, b - t) + 1
        val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
        val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    /** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
    private fun cachedGlyph(a: App): Drawable? {
        val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    /**
     * The colour an icon is mostly, for tinting its mark: the biggest hue among its vivid pixels (backplate
     * included, which is where most apps keep their colour). Null if it's all greys, or too close to the
     * card colour to read on it; the mark then stays the theme's mono colour.
     */
    private fun accent(icon: Drawable): Int? {
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.constantState?.newDrawable(resources)?.apply { setBounds(0, 0, size, size) }?.draw(Canvas(bmp)) ?: return null
        val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
        bmp.recycle()
        val hsv = FloatArray(3)
        val buckets = px.filter { Color.alpha(it) > 200 }
            .filter { Color.colorToHSV(it, hsv); hsv[1] > 0.35f && hsv[2] > 0.3f }
            .groupBy { Color.colorToHSV(it, hsv); (hsv[0] / 30).toInt() } // 12 hue buckets
        val top = buckets.values.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 } ?: return null
        return Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
    }

    /** accent() per app, kept like GLYPHS; the contrast check is per theme, so it runs on every use. */
    private fun cachedAccent(a: App): Int? {
        val c = if (a.pkg in ACCENTS) ACCENTS[a.pkg] else accent(a.icon).also { ACCENTS[a.pkg] = it }
        val lum = c?.let { Color.luminance(it) } ?: return null
        return c.takeIf { if (dark) lum > 0.08f else lum < 0.6f }
    }

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    private fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener in style() is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: Left/Right step a row's value (its tag, see settingsRow) and
        // never leak focus to the home screen behind it. The PIN boxes and the city search box use them themselves.
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            @Suppress("UNCHECKED_CAST")
            if (e.action == KeyEvent.ACTION_DOWN) (currentFocus?.tag as? (Int) -> Unit)?.invoke(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        // By title, the title is a pill you select: Left and Right (or OK) show another source.
        if (currentFocus === watchTitle && (confirm || e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) cycleSource(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        if (editing && editKey(e, confirm)) return true
        // Per channel: Down on a card shows the next row, Up the one before (Up on the first goes to the apps).
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && cardsMode() == ShowRows.PER_CHANNEL && settingsScrim.visibility != View.VISIBLE &&
            allApps.visibility != View.VISIBLE && currentFocus?.getTag(R.id.watchRow) is Prog &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
            pageSource(if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
        ) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
     * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
     * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
     * let Android handle the key.
     */
    private fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val cards = if (watchScroll.visibility != View.VISIBLE) null
            else alive(lastCard) ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        val title = watchTitle.takeIf { it.isFocusable && it.visibility == View.VISIBLE }
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: cards ?: v // the apps row can be empty (all hidden)
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == R.id.wifi) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_DOWN -> title ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (appRow.indexOfChild(v) == 0) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (appRow.indexOfChild(v) == appRow.childCount - 1) v else null
                else -> null
            }
            v === watchTitle -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> apps ?: top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                else -> null
            }
            v.getTag(R.id.watchRow) is Prog -> {
                val i = watchRow.indexOfChild(v.parent as View)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> title ?: apps ?: top
                    KeyEvent.KEYCODE_DPAD_DOWN -> v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == watchRow.childCount - 1) v else null
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    private fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag).apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow -> appMenu(v)
            v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
        }
    }

    /** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
    private fun appMenu(v: View) {
        val pkg = v.tag as String
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
        ))
        holdMenu(v, listOfNotNull(
            getString(R.string.move) to { startEdit(grab = v, once = true) },
            getString(R.string.remove_from_row) to {
                val i = appRow.indexOfChild(v)
                // An input just unpins: it is in the Sources list anyway, so there is nothing to hide.
                if (pkg.startsWith(HDMI)) prefs.edit().putStringSet("inputs_pinned", pinnedInputs() - pkg).apply()
                else prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).apply()
                renderApps()
                appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
                toast(getString(R.string.removed_from_row))
            },
            if (pkg.startsWith(HDMI)) null else getString(R.string.app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
            if (canUninstall(pkg)) getString(R.string.uninstall) to { uninstall(pkg) } else null,
        ))
    }

    /** Apps the user installed, and system apps that have an update on top. Never Plume or an HDMI tile. */
    private fun canUninstall(pkg: String): Boolean {
        if (pkg == packageName || pkg.startsWith(HDMI)) return false
        return try {
            val flags = packageManager.getApplicationInfo(pkg, 0).flags
            flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        } catch (_: Exception) { false }
    }

    private fun uninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.cant_uninstall), Toast.LENGTH_SHORT).show()
        }
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    private fun watchMenu(v: View, p: Prog) {
        holdMenu(v, listOfNotNull(
            if (locked()) null else getString(R.string.remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.let { pkg ->
                getString(R.string.open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    /** A small Plume-styled menu just above [anchor] (under it when [below]); Back closes it. [rows] builds its rows. */
    private fun popupMenu(anchor: View, below: Boolean = false, rows: (dismiss: () -> Unit) -> List<View>) {
        val list: LinearLayout = if (frosted()) glassSurface(dp(14).toFloat()) else LinearLayout(this).apply {
            background = if (dark) rimCard(dp(12), false, solid = true) else GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
            elevation = if (dark) 0f else dp(8).toFloat()
        }
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(6), dp(6), dp(6), dp(6))
        val menu = PopupWindow(list, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        rows { menu.dismiss() }.forEachIndexed { i, row ->
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(2)
            })
            row.minimumHeight = dp(40)
        }
        // As wide as the longest item, never narrower than the tile or card it opens from.
        // A very long translation wraps onto two lines instead of stretching across the screen.
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        menu.width = list.measuredWidth.coerceIn(anchor.width, maxOf(anchor.width, dp(360)))
        list.measure(View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = if (dark) 0f else dp(8).toFloat()
        // Over the anchor's left edge, but kept inside the row's 48 dp side margin near the right of the screen.
        val x = minOf(at[0], resources.displayMetrics.widthPixels - dp(48) - menu.width)
        val y = if (below) at[1] + anchor.height + dp(8) else maxOf(dp(8), at[1] - list.measuredHeight - dp(8))
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        list.getChildAt(0).requestFocus()
    }

    private fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) =
        popupMenu(anchor) { dismiss -> items.map { (name, action) -> settingsRow(name, "") { dismiss(); action() } } }

    /** The TV's inputs under the Sources icon, as on any TV: the logo of what is plugged in, its name, no signal if none. */
    private fun showSources(anchor: View) {
        if (sources.isEmpty()) return
        popupMenu(anchor, below = true) { dismiss ->
            val pinned = pinnedInputs()
            sources.sortedBy { it.label.lowercase() }.map { src ->
                val state = listOfNotNull(
                    if (src.offline) getString(R.string.no_signal) else null,
                    if (src.pkg in pinned) getString(R.string.in_row) else null,
                ).joinToString(" · ")
                settingsRow(src.label, state) { dismiss(); launch(src) }.also { row ->
                    (row as LinearLayout).addView(ImageView(this).apply {
                        setImageDrawable(src.icon)
                        setColorFilter(if (dark) DARK_TEXT_2 else fg)
                    }, 0, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                    row.setOnLongClickListener { dismiss(); unlock { togglePin(src) }; true } // hold OK: in or out of the home row
                }
            } + label(getString(R.string.sources_hint), 12f).apply {
                secondary(0.6f)
                setPadding(dp(14), dp(8), dp(14), dp(4))
                isSingleLine = false // short, and it wraps rather than widen the list
                maxWidth = dp(180)
            }
        }
    }

    private fun appTileCount() = appRow.childCount

    private var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    private fun startEdit(grab: View?, once: Boolean = false) {
        editing = true
        moveOnce = once
        appsTitle.text = if (once) getString(R.string.moving_hint) else getString(R.string.editing_hint)
        appsTitle.visibility = View.VISIBLE
        (grab ?: appRow.getChildAt(0)).requestFocus()
        setGrabbed(grab)
    }

    private fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
        currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
                if (moveOnce) endEdit() else setGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    showNames()
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
    private fun rowHiddenSet() = prefs.getStringSet("row_hidden", emptySet())!!

    private fun pickRowApps() {
        val list = sortedApps().filter { !it.pkg.startsWith(HDMI) } // inputs are pinned from the Sources list
        toggles(getString(R.string.apps_in_row), list.map { it.label to it.pkg }, "row_hidden", getString(R.string.shown), getString(R.string.hidden)) {
            renderApps()
            renderSettings(R.string.apps_in_row)
        }
    }

    /** A page of on/off rows saved as the set of keys that are off; [done] runs on Back. */
    /** [pref] holds the keys switched from where they start: on, or off for the keys in [startsOff]. */
    private fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0,
                        startsOff: Set<String> = emptySet(), done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        showPage(title, items.mapIndexed { i, (name, key) ->
            page(name, if ((key in offSet) != (key in startsOff)) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i, startsOff, done)
            }
        }, focus, back = done)
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        val from = currentFocus?.takeIf { it.tag == a.pkg } // the tile that was pressed, not one a remote key opened it from
        try {
            if (a.pkg.startsWith(HDMI)) Remap.openInput(this, a.pkg.removePrefix(HDMI)) else startApp(a.launch, from)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startApp(it, from) }
        }
    }

    private fun animationsOn() = prefs.getBoolean("app_anim", true)

    private var openedFrom: WeakReference<View>? = null // the tile or card an app was opened from, until we come back

    /** Starts an app. With [from] and the animations on, the app opens out of that tile and the row settles on return. */
    private fun startApp(intent: Intent, from: View?) {
        val source = from?.takeIf { animationsOn() && it.isAttachedToWindow && it.width > 0 }
        val options = source?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        startActivity(intent, options)
        openedFrom = source?.let { WeakReference(it) }
    }

    /**
     * Back from an app: the row of the tile it came from settles in, a little larger and dimmer at first. It settles
     * around that tile when it is on screen, and around the middle of the row's window when Home has moved the row.
     */
    private fun settleRow(tile: View) {
        // A Continue watching card sits in a column of its own: the row is the one that holds the columns.
        val row = (if (tile.getTag(R.id.watchRow) is Prog) watchRow else tile.parent) as? ViewGroup ?: return
        row.animate().cancel()
        val at = Rect(0, 0, tile.width, tile.height)
        row.offsetDescendantRectToMyCoords(tile, at) // the tile's place in the row, whatever sits between them
        var pivot = at.exactCenterX()
        val scroll = generateSequence(row.parent) { it.parent }.firstOrNull { it is HorizontalScrollView } as? HorizontalScrollView
        if (scroll != null && (at.left < scroll.scrollX || at.right > scroll.scrollX + scroll.width)) pivot = scroll.scrollX + scroll.width / 2f
        row.pivotX = pivot
        row.pivotY = at.exactCenterY()
        row.scaleX = 1.12f
        row.scaleY = 1.12f
        row.alpha = 0.4f
        // A moment late: the app's own window is still fading out over us for the first part.
        row.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(90).setDuration(420).setInterpolator(DECELERATE).start()
    }

    // ---------- all apps ----------

    private fun openAllApps() {
        allAppsGrid.removeAllViews()
        val gap = dp(10)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
        for (a in sortedApps()) {
            val t = appTile(a) // holding OK here pins it to the row (see onHold)
            allAppsGrid.addView(t, GridLayout.LayoutParams().apply {
                this.width = width
                height = dp(76)
                setMargins(0, 0, gap, gap)
            })
        }
        allApps.visibility = View.VISIBLE
        homeFocusable(false)
        allAppsGrid.getChildAt(0)?.requestFocus()
    }

    private fun closeAllApps() {
        allApps.visibility = View.GONE
        homeFocusable(true)
        renderApps()
        findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    private fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    private fun galleryOn() = currentScreensaver() == gallery()

    private fun screensaverName(component: String = currentScreensaver()): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.system_default)
        }
    }

    /** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
    private fun photoScreensaver() = prefs.getString("ss_folder", null)

    private fun pickScreensaver(focus: Int = 0) {
        val paintings = galleryOn() && photoScreensaver() == null
        val photos = galleryOn() && photoScreensaver() != null
        val other = if (galleryOn()) prefs.getString("prev_screensaver", "").orEmpty() else currentScreensaver()
        showPage(getString(R.string.screensaver), listOf(
            page(getString(R.string.gallery_paintings), if (paintings) CHOSEN else if (downloading) getString(R.string.downloading) else PAINTINGS_MB) {
                useGallery(null)
                pickScreensaver(0)
            },
            page(getString(R.string.your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
                pickFolder({ pickScreensaver(1) }) { choice ->
                    useGallery(choice)
                    pickScreensaver(1)
                }
            },
            page(screensaverName(other), if (galleryOn()) "" else CHOSEN) {
                if (galleryOn()) stopGallery()
                pickScreensaver(2)
            },
        ), focus) { renderSettings(R.string.screensaver) }
    }

    /** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
    private fun useGallery(folder: String?) {
        prefs.edit().putString("ss_folder", folder).apply()
        if (folder == null && !paintingsReady(this)) return downloadPaintings { useGallery(null) }
        if (folder != null) paintingsDir(this).deleteRecursively() // not needed for photos: give the space back
        if (galleryOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission Plume can't switch it itself; Android's own list has getString(R.string.gallery).
            toast(getString(R.string.choose_gallery_hint), long = true, system = true)
            return openScreensaverSettings()
        }
        prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to whatever screensaver was set before Gallery. */
    private fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
        paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    private fun openScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        toast(getString(R.string.downloading_paintings, PAINTINGS_MB))
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                android.util.Log.w("Plume", "paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else toast(getString(R.string.paintings_failed), long = true)
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.screensaver)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    private fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            toast(getString(R.string.cant_dream))
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK streams straight into an install session, so there's no temporary file to clean up.

    @Volatile private var checking = false
    @Volatile private var updating = false

    private fun myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The newer version a check found, if any (null again once it's installed). */
    private fun availableUpdate() = prefs.getString("upd_ver", null)?.takeIf { newerVersion(it, myVersion()) }

    private fun checkUpdate(force: Boolean) {
        if (checking || !force && System.currentTimeMillis() - prefs.getLong("upd_time", 0) < 24 * 3600_000L) return
        checking = true
        if (force) renderSettings(R.string.updates)
        io.execute {
            val latest = try {
                getJson("https://api.github.com/repos/$REPO/releases/latest").getString("tag_name").removePrefix("v")
            } catch (e: Exception) {
                android.util.Log.w("Plume", "update check failed ($e)")
                null
            }
            if (latest != null) prefs.edit().putString("upd_ver", latest).putLong("upd_time", System.currentTimeMillis()).apply()
            ui.post {
                checking = false
                val ver = availableUpdate()
                if (ver != null && (force || prefs.getString("upd_told", null) != ver)) {
                    prefs.edit().putString("upd_told", ver).apply()
                    if (!force) toast(getString(R.string.update_available_toast, ver), long = true)
                } else if (force) {
                    toast(if (latest == null) getString(R.string.update_check_failed) else getString(R.string.up_to_date_toast))
                }
                updateBadge()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    /** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
    private fun showUpdates() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val ver = availableUpdate()
        showPage(getString(R.string.whats_new_title, myVersion()), listOf(
            if (ver != null) page(getString(R.string.install_update, ver)) { installUpdate(ver) }
            else page(getString(R.string.check_updates)) { checkUpdate(force = true) },
        ), back = { renderSettings(R.string.updates) })
        // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
        getString(R.string.whats_new).split('\n').forEach {
            if (it.startsWith("#")) {
                settingsList.addView(label(it.substring(1), 13f).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(14), dp(18), dp(14), dp(2))
                })
                return@forEach
            }
            settingsList.addView(label("", 13f).apply {
                text = noteText(it)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.7f
                isFocusable = true
                setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
                setPadding(dp(14), dp(10), dp(14), dp(2))
            })
        }
        settingsList.getChildAt(0).nextFocusDownId = View.NO_ID // showPage stops Down at its last row
        settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
    }

    /** A What's new entry with a bullet, its **marked** words bold and the marks removed. */
    private fun noteText(entry: String): SpannableString {
        val parts = entry.split("**")
        val s = SpannableString(parts.joinToString(""))
        var at = 0
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) s.setSpan(StyleSpan(Typeface.BOLD), at, at + part.length, 0)
            at += part.length
        }
        s.setSpan(BulletSpan(dp(10), fg), 0, s.length, 0)
        return s
    }

    /** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
    private fun notesUnseen(): Boolean {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime && prefs.getString("seen_notes", null) != info.versionName
    }

    private val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /** A dot on the Plume settings icon while an update waits or its notes are unread. */
    private fun updateBadge() {
        val icon = findViewById<ImageView>(R.id.launcherSettings)
        icon.overlay.clear()
        if (availableUpdate() == null && !notesUnseen()) return
        val size = dp(9)
        badge.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
        icon.overlay.add(badge)
    }

    private fun installUpdate(ver: String) {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            toast(getString(R.string.allow_installs), long = true, system = true)
            return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        }
        updating = true
        renderSettings(R.string.updates)
        val installer = packageManager.packageInstaller
        io.execute {
            val ok = try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    try {
                        openStream("https://github.com/$REPO/releases/download/v$ver/Plume.apk").use { input ->
                            session.openWrite("Plume.apk", 0, -1).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    } catch (e: Exception) {
                        session.abandon()
                        throw e
                    }
                    val done = PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java).setAction(UPDATE_STATUS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(done.intentSender)
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("Plume", "update download failed ($e)")
                false
            }
            ui.post {
                if (!ok) {
                    updating = false
                    toast(getString(R.string.update_download_failed), long = true)
                }
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    /** The install session reports back here: show Android's confirm box, or say why it failed. */
    private fun installStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { startActivity(it) }
            return
        }
        updating = false
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            android.util.Log.w("Plume", "update install failed: $status $why")
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                toast(getString(R.string.update_install_failed), long = true)
            }
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
    }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    private fun refreshWeather() {
        if (prefs.getString("weather", "off") == "off") return
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        io.execute {
            try {
                if (!manualCity() && System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
                    val geo = getJson("https://ipwho.is/?fields=success,city,latitude,longitude")
                    if (geo.optBoolean("success")) prefs.edit()
                        .putString("geo_city", geo.getString("city"))
                        .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                        .putLong("geo_time", System.currentTimeMillis()).apply()
                }
                if (!prefs.contains("geo_lat")) return@execute
                val now = getJson("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
                    "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code").getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", now.getInt("weather_code"))
                    .putLong("wx_time", System.currentTimeMillis()).apply()
                ui.post { showWeather() }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "weather failed ($e)") // keep the last reading; next tick retries
            }
        }
    }

    // An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
    // Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

    /** A one-line text box for the top of a settings page, inside its own holder; [done] gets the trimmed text. */
    private fun textField(text: String, hint: String, flags: Int, ime: Int, done: (String) -> Unit): EditText {
        val field = EditText(this).apply {
            id = View.generateViewId()
            setText(text)
            setSelection(text.length)
            this.hint = hint
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or flags
            imeOptions = ime
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            setOnEditorActionListener { v, _, _ -> done(v.text.toString().trim()); true }
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        }
        return field
    }

    private fun manualCity() = prefs.getBoolean("geo_manual", false)

    private class City(val name: String, val where: String, val lat: Float, val lon: Float)

    /** Automatic first, then the search box; a search lists what it found under them. */
    private fun pickCity(query: String = "", found: List<City>? = null, status: String = "") {
        val field = textField(query, getString(R.string.city_hint), InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_SEARCH) { searchCity(it) }
        val top = (field.parent as LinearLayout).apply {
            if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
        }
        val rows = listOf(page(getString(R.string.automatic_ip), if (manualCity()) "" else CHOSEN) {
            prefs.edit().remove("geo_manual").remove("geo_time").remove("wx_time").apply()
            refreshWeather()
            renderSettings(R.string.weather_location)
        }) + found.orEmpty().map { c ->
            page(c.name, c.where) {
                prefs.edit().putBoolean("geo_manual", true).putString("geo_city", c.name)
                    .putFloat("geo_lat", c.lat).putFloat("geo_lon", c.lon).remove("wx_time").apply()
                refreshWeather()
                renderSettings(R.string.weather_location)
            }
        }
        showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else 1, top = top) { renderSettings(R.string.weather_location) }
        // showPage pins Up on the first row to itself; here Up goes to the search box.
        settingsList.getChildAt(1).nextFocusUpId = field.id
        if (found.isNullOrEmpty()) field.requestFocus()
        // Results in: put the keyboard away, it would cover them.
        else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun searchCity(query: String) {
        if (query.isEmpty()) return
        pickCity(query, status = getString(R.string.searching))
        io.execute {
            val found = try {
                val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=${java.util.Locale.getDefault().language}&name=${URLEncoder.encode(query, "UTF-8")}")
                val list = r.optJSONArray("results")
                (0 until (list?.length() ?: 0)).map { i ->
                    val c = list!!.getJSONObject(i)
                    City(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                        c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                // Only if the page is still open: Back may have left it while the search ran.
                if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
                when {
                    found == null -> pickCity(query, status = getString(R.string.search_failed))
                    found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                    else -> pickCity(query, found)
                }
            }
        }
    }

    private fun showWeather() {
        val view = findViewById<TextView>(R.id.weather)
        val unit = prefs.getString("weather", "off")
        if (unit == "off" || !prefs.contains("wx_temp")) {
            view.visibility = View.GONE
            return
        }
        val c = prefs.getFloat("wx_temp", 0f)
        val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
        view.text = listOfNotNull("$temp° ${weatherWord(prefs.getInt("wx_code", 0))}".trim(), prefs.getString("geo_city", null))
            .joinToString("  ·  ")
        view.visibility = View.VISIBLE
        view.post { fitInk() }
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> getString(R.string.wx_clear)
        1, 2 -> getString(R.string.wx_partly_cloudy)
        3 -> getString(R.string.wx_cloudy)
        45, 48 -> getString(R.string.wx_fog)
        in 51..57 -> getString(R.string.wx_drizzle)
        in 61..67, in 80..82 -> getString(R.string.wx_rain)
        in 71..77, 85, 86 -> getString(R.string.wx_snow)
        in 95..99 -> getString(R.string.wx_thunderstorm)
        else -> ""
    }

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(Cards(emptyList(), null, emptyList()))
            return
        }
        io.execute {
            val cards = try {
                queryWatchNext()
            } catch (e: Exception) {
                Cards(emptyList(), null, emptyList())
            }
            ui.post { showWatchNext(cards) }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Apps' own home-screen channels the user switched on (see [channelOn]); ones named "Continue Watching"
     *    are on to start with. Stremio publishes one (with progress and an autoPlay resume link) instead of
     *    using Watch Next, so signing in to Stremio on the TV is all it takes. Channel rows sort by their own
     *    last-watched time when the app sets one, else by the time the app was last opened from Plume, see [channelSortKey].
     * Newest first across apps, each app's [PER_APP] newest ahead of the rest, so one app with ten shows can't bury the others.
     */
    private fun queryWatchNext(): Cards {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNextOn = WATCH_NEXT !in prefs.getStringSet("ch_flip", emptySet())!!
        val watchNext = if (watchNextOn) readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden) else emptyList()
        val on = continueChannels()
        autoRowName = if (watchNextOn) null
            else on.map { it.third }.distinct().singleOrNull()?.takeUnless { it.equals("Continue Watching", ignoreCase = true) }
        val off = prefs.getStringSet("cw_off", emptySet())!!
        if (cardsMode() != ShowRows.MIXED) return splitCards(watchNext, on, hidden, off)
        val channels = on.flatMap { (id) ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden, "ch$id")
        }
        val all = watchNext + channels
        publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
        return Cards(emptyList(), null, newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.group }
            .take(ROW_CARDS))
    }

    /**
     * By title and Per channel: a list of cards for Watch Next and one for each channel that is on, each in its own order
     * (the same rules as the mixed row: hidden shows and turned-off apps left out, no repeats, at most [ROW_CARDS]).
     * A source with nothing in it is left out. [ShowRows.ordered] puts Watch Next first, then the Continue Watching channels.
     */
    private fun splitCards(watchNext: List<Pair<Long, Prog>>, on: List<Triple<Long, String, String>>, hidden: Set<String>, off: Set<String>): Cards {
        val label = apps.associate { it.pkg to it.label }
        val found = ArrayList<Pair<ShowRows.Source, List<Prog>>>()
        val seen = HashSet<String>()
        fun add(key: String, title: String, kind: ShowRows.Kind, items: List<Prog>) {
            seen += items.mapNotNull { it.pkg }
            val shown = items.filter { it.pkg !in off }.distinctBy { it.intent ?: it.title }.take(ROW_CARDS)
            if (shown.isNotEmpty()) found += ShowRows.Source(key, title, kind) to shown
        }
        add(WATCH_NEXT, getString(R.string.watch_next), ShowRows.Kind.WATCH_NEXT, newestPerApp(watchNext, PER_APP) { it.group })
        for ((id, p, n) in on.distinctBy { (_, p, n) -> channelKey(p, n) }) {
            val kind = if (n.equals("Continue Watching", ignoreCase = true)) ShowRows.Kind.CONTINUE else ShowRows.Kind.OTHER
            add(channelKey(p, n), if (n == label[p] || label[p].isNullOrBlank()) n else "${label[p]} · $n", kind,
                readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden, "ch$id").map { it.second })
        }
        publishingPkgs = seen
        val rows = ShowRows.ordered(found.map { it.first }).map { s -> found.first { it.first === s } }
        val at = ShowRows.current(rows.map { it.first.key }, prefs.getString("cards_src", null))
        val current = rows.firstOrNull { it.first.key == at }
        return Cards(rows.map { CardSource(it.first.key, it.first.title) }, current?.let { CardSource(it.first.key, it.first.title) }, current?.second ?: emptyList())
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    private fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            toast(getString(R.string.no_cw_apps))
            return
        }
        toggles(getString(R.string.cw_apps), sources.map { it.label to it.pkg }, "cw_off", getString(R.string.on), getString(R.string.off)) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_apps)
        }
    }

    /** Every other app's home-screen channel as (id, package, name); empty where Android won't let us read them. */
    private fun appChannels(): List<Triple<Long, String, String>> = try {
        val out = ArrayList<Triple<Long, String, String>>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                val p = c.getString(pkg) ?: continue
                val n = c.getString(name) ?: continue
                if (p != packageName) out += Triple(c.getLong(id), p, n)
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    private fun channelKey(pkg: String, name: String) = "$pkg/$name"

    private fun continueChannels(): List<Triple<Long, String, String>> {
        val flipped = prefs.getStringSet("ch_flip", emptySet())!!
        return appChannels().filter { (_, p, n) -> channelOn(n, channelKey(p, n), flipped) }
    }

    /** The one channel on, unless it's a "Continue Watching" one: the bottom row is named after it. */
    @Volatile private var autoRowName: String? = null

    /** The bottom row's name: the user's own, else the one channel on, else Continue Watching. */
    private fun rowName() = prefs.getString("row_name", null).orEmpty().ifEmpty { autoRowName ?: getString(R.string.continue_watching) }

    private fun pickRowName() {
        val field = textField(prefs.getString("row_name", null).orEmpty(), autoRowName ?: getString(R.string.continue_watching),
            InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_DONE) { name ->
            prefs.edit().putString("row_name", name).apply()
            showTitle()
            renderSettings(R.string.row_name)
        }
        val auto = page(getString(R.string.automatic), if (prefs.getString("row_name", null).isNullOrEmpty()) CHOSEN else "") {
            prefs.edit().remove("row_name").apply()
            showTitle()
            renderSettings(R.string.row_name)
        }
        // Opens on Automatic, so the keyboard only comes up when you go Up to the box to type.
        showPage(getString(R.string.row_name), listOf(auto), top = field.parent as View) { renderSettings(R.string.row_name) }
        settingsList.getChildAt(1).nextFocusUpId = field.id
    }

    /** Pick what fills the bottom row: Android TV Watch Next and any app channel on this TV now ("Plex · On Deck"). */
    private fun pickChannels() {
        val channels = appChannels().distinctBy { (_, p, n) -> channelKey(p, n) }
        val label = apps.associate { it.pkg to it.label }
        fun app(p: String) = label[p] ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { p }
        val items = listOf(getString(R.string.watch_next) to WATCH_NEXT) +
            channels.map { (_, p, n) -> (if (n == app(p)) n else "${app(p)} · $n") to channelKey(p, n) }.sortedBy { it.first.lowercase() }
        val startsOff = channels.filter { (_, p, n) -> !channelOn(n, channelKey(p, n), emptySet()) }.map { (_, p, n) -> channelKey(p, n) }.toSet()
        toggles(getString(R.string.cw_channels), items, "ch_flip", getString(R.string.on), getString(R.string.off), startsOff = startsOff) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_channels)
        }
    }

    /** Pairs of (sort key, item), see [watchSortKey]; a [group] is a channel, its rows sorted by [channelSortKey] and [byWeight]. */
    private fun readPrograms(uri: Uri, hidden: Set<String>, group: String? = null): List<Pair<Long, Prog>> {
        val out = ArrayList<Triple<Long, Long, Prog>>()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val title = str("title") ?: continue
                if (hideKey(str("package_name"), title) in hidden) continue
                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                val sub = listOfNotNull(
                    if (season != null && episode != null) getString(R.string.season_episode, season, episode) else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val pkg = str("package_name")
                val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), pkg, group ?: pkg)
                val engaged = num("last_engagement_time_utc_millis")
                out += Triple(num("weight"),
                    if (group == null) watchSortKey(num("watch_next_type"), engaged) else channelSortKey(engaged, prefs.getLong("t_$pkg", 0)), prog)
            }
        }
        return byWeight(out)
    }

    /** "mixed" (every source in one row), "title" (one row, switched with its title) or "stack" (one row per source, Down and Up). */
    private fun cardsMode() = ShowRows.mode(prefs.getString("cards_rows", null))

    /** By title with at least two sources: the title is a pill you can select. */
    private fun canCycle() = cardsMode() == ShowRows.BY_TITLE && cardSources.size > 1

    /** By title: Left or Right on the title shows the next or the previous source, round the list. */
    private fun cycleSource(d: Int) {
        switchTo(ShowRows.around(cardSources.map { it.key }, cardCurrent?.key, d) ?: return)
    }

    /** Per channel: Down on a card shows the next row (+1), Up the one before (-1). False when there is none that way. */
    private fun pageSource(d: Int): Boolean {
        switchTo(ShowRows.beside(cardSources.map { it.key }, cardCurrent?.key, d) ?: return false)
        return true
    }

    private fun switchTo(key: String) {
        prefs.edit().putString("cards_src", key).apply()
        switching = true
        refreshWatchNext()
    }

    /** What the row is called: its own name when mixed, else the source on show; Per channel adds an arrow to each other row. */
    private fun rowTitle(): String {
        val cur = cardCurrent
        if (cardsMode() == ShowRows.MIXED || cur == null) return rowName()
        val at = cardSources.indexOfFirst { it.key == cur.key }
        return if (cardsMode() == ShowRows.PER_CHANNEL && cardSources.size > 1)
            cur.title + "  " + (if (at > 0) "↑" else "") + (if (at < cardSources.size - 1) "↓" else "")
        else cur.title
    }

    /**
     * The row's title is a pill when it can be selected (By title): the lit glass of a selected tile, with an arrow on each side. The
     * arrows are always in the text, clear when it has no focus, so the pill keeps its size and the title does not move. It is padded
     * in and pulled back out with negative margins, so the text sits where the plain title sits.
     */
    private fun setUpTitle() {
        style(watchTitle, dp(8), card = true, lift = 1.04f) { focused ->
            if (focused) glass.add(watchTitle, dp(8).toFloat()) else {
                glass.remove(watchTitle)
                watchTitle.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() } // nothing to see at rest
            }
            watchTitle.animate().alpha(if (focused) 1f else titleAlpha).setDuration(160).start()
            showTitle()
        }
        glass.remove(watchTitle) // style() lets the glass paint under every card; the pill only has it while selected
        watchTitle.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        watchTitle.isFocusable = false
        watchTitle.isSingleLine = true
        watchTitle.setPadding(dp(10), dp(4), dp(10), dp(4))
        (watchTitle.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin -= dp(4)
            bottomMargin -= dp(4)
        }
    }

    /** Shows the row's title. With [fade] the old text dips out and the new one in, as the cards do. */
    private fun showTitle(fade: Boolean = false) {
        val cycle = canCycle()
        watchTitle.isFocusable = cycle
        val arrows = if (cycle) watchTitle.paint.measureText("‹  ") else 0f
        val lp = watchTitle.layoutParams as LinearLayout.LayoutParams
        val start = -(dp(10) + arrows.toInt())
        if (lp.marginStart != start) { lp.marginStart = start; watchTitle.layoutParams = lp }
        // A name too long for the row is cut here, not by the view, so the closing arrow stays.
        val room = ((resources.displayMetrics.widthPixels - dp(96)) * 2 / 3 - dp(20) - arrows * 2).coerceAtLeast(dp(60).toFloat())
        val name = TextUtils.ellipsize(rowTitle(), watchTitle.paint, room, TextUtils.TruncateAt.END)
        val text: CharSequence = if (!cycle) name else SpannableString("‹  $name  ›").also {
            if (!watchTitle.isFocused) { // the arrows are only there while it is selected
                it.setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, 1, 0)
                it.setSpan(ForegroundColorSpan(Color.TRANSPARENT), it.length - 1, it.length, 0)
            }
        }
        ui.removeCallbacks(swapTitle)
        watchTitle.animate().cancel()
        if (!fade || watchTitle.text.toString() == text.toString()) {
            watchTitle.text = text
            return
        }
        // Not withEndAction: another animation on the title (the row being placed, the focus) cancels it, and then the text never changed.
        titleNext = text
        watchTitle.animate().alpha(0f).setDuration(SWAP_OUT_MS).start()
        ui.postDelayed(swapTitle, SWAP_OUT_MS)
    }

    private var titleNext: CharSequence = ""

    private val swapTitle = Runnable {
        watchTitle.animate().cancel()
        watchTitle.text = titleNext
        watchTitle.animate().alpha(if (watchTitle.isFocused) 1f else titleAlpha).setDuration(SWAP_IN_MS).start()
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        toast(getString(R.string.hidden_from_cw))
        refreshWatchNext()
    }

    /** Small source-app mark for the details line: its logo when we have one (brand colour with colour icons), else its icon (greyscale unless colour). */
    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        val colour = cwColour()
        val tint = BRAND_COLOURS[pkg]?.takeIf { colour } ?: fg
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(tint) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { if (!colour) colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hm, min / 60, min % 60) else getString(R.string.time_left_m, min)
    }

    private fun showWatchNext(cards: Cards) {
        val list = cards.list
        val key = cards.current?.key.orEmpty() + "|" + cards.sources.size + "|" + list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        // Another source of the same row: the title and the cards stay where they are and change their content.
        val inPlace = switching && watchRow.childCount > 0 && list.isNotEmpty() && cardCurrent != null
        switching = false
        watchKey = key
        watchList = list
        cardSources = cards.sources
        cardCurrent = cards.current
        showTitle(fade = inPlace)
        if (inPlace) return swapCards()
        val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
        watchRow.removeAllViews()
        placeRow(animate = false)
        // ~16 ms per card here and only four show at once: the first four now, the next four on the next frame.
        addCardsTo(3)
        watchRow.post { fitInk() }
        ui.post { if (watchKey == key) addCardsTo(CARDS_AHEAD - 1) } // unless a newer list replaced this one
        if (focusedAt >= 0) {
            ((watchRow.getChildAt(focusedAt.coerceAtMost(watchRow.childCount - 1)) as? LinearLayout)?.getChildAt(0)
                ?: appRow.getChildAt(0))?.requestFocus()
        }
    }

    private var watchList = emptyList<Prog>()

    /** "always", "down" (hidden until Down reaches it) or "never". */
    private fun rowShow() = prefs.getString("row_show", "always")!!.takeIf { it in ROW_SHOW } ?: "always"

    /** While the bottom row is hidden, the app row drops into its space (true) or stays where it is. */
    private fun rowDrops() = prefs.getBoolean("row_drop", false)

    /** "Icons" in the shows row: source marks in colour (true) or monochrome, apart from the app row's icon style. */
    private fun cwColour() = prefs.getBoolean("cw_colour", false)

    /**
     * Puts the bottom row and the app row where "Show row" wants them. Both rows are built once and only
     * slide (translationY on a layer), so the move costs no layout or redraw, even on a slow TV.
     */
    private fun placeRow(animate: Boolean) {
        val show = rowShow()
        val has = watchList.isNotEmpty()
        val keepSpace = show != "always" && !rowDrops() // "Stays put": the apps keep their place, even with no shows
        // With no shows, an invisible stand-in card holds the row's height, so the apps don't fall to the bottom.
        if (!has && keepSpace && watchRow.childCount == 0) watchRow.addView(watchCard(Prog(" ", " ", 0, null, null, null), false))
        else if (!has && !keepSpace && watchRow.childCount > 0) watchRow.removeAllViews()
        val vis = when {
            !has -> if (keepSpace) View.INVISIBLE else View.GONE
            show == "never" -> if (rowDrops()) View.GONE else View.INVISIBLE // invisible keeps its space
            else -> View.VISIBLE
        }
        if (watchScroll.visibility != vis) { watchTitle.visibility = vis; watchScroll.visibility = vis }
        val hidden = show == "down" && has && !rowOut
        // From where the app row sits above the bottom row to where it would sit without it.
        val drop = (watchScroll.bottom - appScroll.bottom).toFloat().coerceAtLeast(0f)
        val appY = if (hidden && rowDrops()) drop else 0f
        val rowY = if (!hidden) 0f else if (rowDrops()) drop else dp(24).toFloat()
        fun move(v: View, y: Float, a: Float) {
            if (v.translationY == y && v.alpha == a) return // a layer for a view that doesn't move only costs frames
            if (animate) v.animate().translationY(y).alpha(a).setDuration(250).setInterpolator(DECELERATE).start()
            else { v.animate().cancel(); v.translationY = y; v.alpha = a }
        }
        move(appsTitle, appY, appsTitle.alpha)
        move(appScroll, appY, 1f)
        move(watchTitle, rowY, if (hidden) 0f else if (watchTitle.isFocused) 1f else titleAlpha)
        move(watchScroll, rowY, if (hidden) 0f else 1f)
    }

    /**
     * Cards exist up to index [last] only: the four on screen and the four after them, more as focus moves on.
     * Every card costs drawing time on each frame of the home screen (20 dropped ~6% of frames on the test
     * TV, 8 about 2%), on screen or not, so the rest of the list waits until focus gets near.
     */
    private fun addCardsTo(last: Int) {
        while (watchRow.childCount <= minOf(last, watchList.size - 1)) {
            watchRow.addView(watchCard(watchList[watchRow.childCount], watchRow.childCount > 0))
        }
    }

    /**
     * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
     * title and details (source, episode, time left) go underneath.
     */
    private fun watchCard(p: Prog, gapBefore: Boolean): View {
        val gap = dp(GRID_GAP)
        val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gapBefore) marginStart = gap
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) DARK_SURFACE else 0xFFDDE1E5.toInt())
        }
        // Long titles scroll while their poster is focused instead of staying cut off.
        val title = label(p.title, 14f).apply {
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            ink(this, watchInk)
        }
        val frame = FrameLayout(this)
        style(frame, dp(8), card = true, lift = 1.1f) { focused ->
            title.isSelected = focused
            if (focused) {
                lastCard = frame
                addCardsTo(watchRow.indexOfChild(col) + CARDS_AHEAD)
            }
        }
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        frame.setOnClickListener { (frame.getTag(R.id.watchRow) as? Prog)?.let { open(it) } }
        // 2:1 keeps logos and badges that providers bake into 16:9 art.
        col.addView(frame, LinearLayout.LayoutParams(w, w / 2))
        // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = progressBarDrawable()
        }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(8) })
        col.addView(title)
        col.addView(label(p.sub, 12f).apply {
            ink(this, watchInk)
            alpha = 0.8f
            setPadding(0, dp(2), 0, 0)
            compoundDrawablePadding = dp(6)
        })
        bindCard(col, p, fade = false)
        return col
    }

    /**
     * Puts [p] in a card: its poster, progress, title and details. With [fade] each part dips out and back in on its own, so
     * the card stays where it is while its content changes (the whole card is not faded: a focused card is lifted past its edge).
     */
    private fun bindCard(col: ViewGroup, p: Prog, fade: Boolean) {
        val frame = col.getChildAt(0) as ViewGroup
        val img = frame.getChildAt(0) as ImageView
        val bar = col.getChildAt(1) as ProgressBar
        val title = col.getChildAt(2) as TextView
        val sub = col.getChildAt(3) as TextView
        frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
        frame.contentDescription = p.title
        val icon = sourceIcon(p.pkg)
        val fill = {
            bar.progress = p.progress
            bar.visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
            title.text = p.title
            sub.text = p.sub
            icon?.setBounds(0, 0, dp(12), dp(12))
            sub.setCompoundDrawablesRelative(icon, null, null, null)
            // Split by source, a card without details keeps their space, or the row would change height with the source.
            sub.visibility = if (p.sub.isNotEmpty() || icon != null) View.VISIBLE else if (cardsMode() == ShowRows.MIXED) View.GONE else View.INVISIBLE
            img.setImageDrawable(null)
            loadPoster(img, p.image)
        }
        if (!fade) return fill()
        // Out together, the content changes once they are all clear, then in together.
        val parts = listOf(img to 1f, bar to 1f, title to 1f, sub to 0.8f)
        for ((v, _) in parts) {
            v.animate().cancel()
            val out = v.animate().alpha(0f).setDuration(SWAP_OUT_MS)
            if (v === img) out.withEndAction {
                fill()
                for ((p2, rest) in parts) p2.animate().alpha(rest).setDuration(SWAP_IN_MS).start()
            }
            out.start()
        }
    }

    /**
     * Another source of the same row: the cards that are there change their content in place (title, poster, progress), the
     * ones the new source has more of fade in and the ones it has fewer of fade out. Nothing slides and the row keeps its place.
     */
    private fun swapCards() {
        for (i in watchRow.childCount - 1 downTo 0) if (watchRow.getChildAt(i).tag == FADING) watchRow.removeViewAt(i)
        val kept = minOf(watchRow.childCount, watchList.size)
        for (i in 0 until kept) bindCard(watchRow.getChildAt(i) as ViewGroup, watchList[i], fade = true)
        for (i in watchRow.childCount - 1 downTo kept) {
            val col = watchRow.getChildAt(i)
            if (col.hasFocus()) (watchRow.getChildAt(kept - 1) as ViewGroup).getChildAt(0).requestFocus()
            col.tag = FADING
            col.animate().alpha(0f).setDuration(SWAP_OUT_MS).withEndAction { watchRow.removeView(col) }.start()
        }
        val before = watchRow.childCount
        addCardsTo(3)
        for (i in before until watchRow.childCount) watchRow.getChildAt(i).apply { alpha = 0f; animate().alpha(1f).setDuration(SWAP_IN_MS).start() }
        placeRow(animate = false)
        watchRow.post { fitInk() }
        val key = watchKey
        ui.post { if (watchKey == key) addCardsTo(CARDS_AHEAD - 1) }
    }

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (dark) 0x40FFFFFF else 0x26000000),
            ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
        val from = currentFocus?.takeIf { it.getTag(R.id.watchRow) is Prog }
        try {
            startApp(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), from)
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startApp(it, from) }
        }
    }

    private fun loadPoster(v: ImageView, uri: String?) {
        if (uri == null) return
        posters.get(uri)?.let { v.setImageBitmap(it); return }
        v.tag = uri
        if (!loading.add(uri)) return // already on its way
        posterIo.execute {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post { if (v.tag == uri) v.setImageBitmap(bmp) }
        }
    }

    /** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
    private fun retryPosters() {
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.drawable == null) loadPoster(img, img.tag as? String)
        }
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    private fun decode(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
     * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
     * Only transform/alpha/colour change, so it stays on the GPU and costs no re-layout.
     */
    private fun style(v: View, radius: Int, card: Boolean, lift: Float = 1.08f, onFocus: ((Boolean) -> Unit)? = null) {
        fun gradient(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radius.toFloat()
            setDither(true)
        }
        val sheenColors = intArrayOf(0x40FFFFFF, 0x00FFFFFF)
        val sheen = Baked("sheen $radius", gradient(sheenColors)).apply { alpha = 0 }
        val rest = if (card) Baked("card $radius ${cardRest.contentToString()}", gradient(cardRest)) else null
        val lit = if (card) Baked("card $radius ${cardLit.contentToString()}", gradient(cardLit)) else null
        val frosted = card && glassActive()
        if (frosted) glass.add(v, radius.toFloat())
        v.background = if (dark && card) rimCard(radius, false, frosted = frosted) else rest ?: GradientDrawable().apply { cornerRadius = radius.toFloat() }
        if (!dark) v.foreground = sheen
        v.clipToOutline = true
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheen.alpha = if (focused) 255 else 0
            if (card && dark) v.background = rimCard(radius, focused, frosted = frosted)
            else if (card) v.background = if (focused) lit else rest
            if (frosted) glass.kick(260) // the lift moves the tile: its blur follows
            v.animate()
                .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    /**
     * A dark-mode card on true black: near-black glass with a hairline rim, brighter along the top edge as if
     * lit from above. [lit] is the focused look: brighter glass and rim. [solid]: no see-through, for menus and
     * dialogs, which sit over other things and have to stay readable (light mode's are solid too).
     */
    private fun rimCard(radius: Int, lit: Boolean, solid: Boolean = false, frosted: Boolean = false): Drawable {
        val glass = (if (lit) DARK_CARD_LIT else DARK_CARD).let { c ->
            if (solid) IntArray(c.size) { c[it] or 0xFF000000.toInt() }
            else if (frosted) IntArray(c.size) { c[it] and 0xFFFFFF or ((if (lit) 0x66 else 0x40) shl 24) } // 25% at rest, 40% selected, over the blur
            else c
        }
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, glass).apply {
            cornerRadius = radius.toFloat()
            setStroke(1, if (lit) RIM_SIDE_LIT else RIM_SIDE) // 1px, not 1dp: a hairline stays crisp
            setDither(true) // near black the gradient has ~8 steps; dithered, they don't show as bands
        }
        return Baked("rim $radius $lit $solid $frosted", LayerDrawable(arrayOf(body, ColorDrawable(if (lit) RIM_TOP_LIT else RIM_TOP))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
            setLayerInsetLeft(1, radius)
            setLayerInsetRight(1, radius)
        })
    }

    /**
     * Draws [src] once into a bitmap, then only copies that bitmap. Drawn in software, so the dithering is real
     * and free afterwards; dithered on the GPU it costs every pixel on every frame, which tripled app-row jank on
     * the TV. Same look and size share one bitmap, so a row of tiles costs one.
     */
    private inner class Baked(private val key: String, private val src: Drawable) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var bmp: Bitmap? = null
        override fun onBoundsChange(b: Rect) {
            src.setBounds(0, 0, b.width(), b.height())
            bmp = if (b.isEmpty) null else baked.getOrPut("$key ${b.width()}x${b.height()}") {
                Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { src.draw(Canvas(it)) }
            }
        }
        override fun draw(c: Canvas) { bmp?.let { c.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), paint) } }
        override fun getOutline(o: Outline) { src.getOutline(o); o.offset(bounds.left, bounds.top) }
        override fun setAlpha(a: Int) { if (paint.alpha != a) { paint.alpha = a; invalidateSelf() } }
        override fun getAlpha() = paint.alpha
        override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
        // A tinted tile (Banner style, an app with no banner) is one solid colour in the card's shape.
        override fun setTintList(t: ColorStateList?) { paint.colorFilter = t?.let { PorterDuffColorFilter(it.defaultColor, PorterDuff.Mode.SRC_IN) }; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    // ponytail: never evicted; fine while only a few tile sizes exist, cap it if views start animating their size
    private val baked = HashMap<String, Bitmap>()

    /** Supporting text: the secondary grey in dark mode, [lightAlpha] of the main colour in light mode. */
    private fun TextView.secondary(lightAlpha: Float) = if (dark) setTextColor(text2()) else alpha = lightAlpha

    /** A name that stays inside its box: it scrolls slowly while its tile is selected if it is too wide (see FittingLabel). */
    private fun fittingLabel(text: String, size: Float) = FittingLabel(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        var fakeDevice: String? = null // debug builds: see fakeSetup()
        var fakeSdk = 0
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val SCRIM_ALPHA = 0x99 // the settings panel's dim over the home screen
        const val AREA_ALL = 0
        const val AREA_CLOCK = 1
        const val AREA_APPS = 2
        const val AREA_SHOWS = 3
        /** The part of the home screen each section changes, lit by the spotlight; other sections dim it all. */
        val SECTION_AREAS = mapOf(
            R.string.section_appearance to AREA_ALL, R.string.section_clock_weather to AREA_CLOCK,
            R.string.section_app_row to AREA_APPS, R.string.bottom_row to AREA_SHOWS,
        )
        val SECTION_ICONS = mapOf(
            R.string.section_appearance to R.drawable.ic_wallpaper, R.string.section_clock_weather to R.drawable.ic_clock,
            R.string.section_app_row to R.drawable.ic_apps, R.string.bottom_row to R.drawable.ic_shows,
            R.string.section_screensaver to R.drawable.ic_screensaver, R.string.section_remote_home to R.drawable.ic_remote,
            R.string.section_lock to R.drawable.ic_lock,
        )
        /** The help line shown at the bottom of Plume settings while a row is selected. */
        val ROW_HELP = mapOf(
            R.string.section_appearance to R.string.help_appearance, R.string.section_clock_weather to R.string.help_clock_weather,
            R.string.section_app_row to R.string.help_apps_row, R.string.bottom_row to R.string.help_shows_row,
            R.string.section_screensaver to R.string.help_screensaver, R.string.section_remote_home to R.string.help_remote_home,
            R.string.section_lock to R.string.help_lock,
            R.string.fade_top to R.string.help_fade_top, R.string.fade_bottom to R.string.help_fade_bottom, R.string.weather_location to R.string.help_location,
            R.string.edit_apps_row to R.string.help_rearrange, R.string.apps_in_row to R.string.help_choose_apps,
            R.string.sort_apps to R.string.help_order, R.string.icon_style to R.string.help_icon_style,
            R.string.apps_when_hidden to R.string.help_apps_when_hidden, R.string.row_name to R.string.help_row_name,
            R.string.show_icons to R.string.help_show_icons,
            R.string.cw_apps to R.string.help_from_apps, R.string.cw_channels to R.string.help_channels, R.string.cards_rows to R.string.help_cards_rows,
            R.string.restore_hidden to R.string.help_hidden_shows, R.string.screensaver_timing to R.string.help_start_after,
            R.string.home_button to R.string.help_home_button, R.string.smooth_volume to R.string.help_smooth_volume,
            R.string.default_launcher to R.string.help_default_launcher,
            R.string.setup_home to R.string.help_run_setup, R.string.section_keys to R.string.help_remote_keys,
        )
        val ROW_SHOW = linkedMapOf("always" to R.string.always, "down" to R.string.on_down, "never" to R.string.never) // "Show row"
        val CARDS_ROWS = linkedMapOf(ShowRows.MIXED to R.string.cards_mixed, ShowRows.BY_TITLE to R.string.cards_title, ShowRows.PER_CHANNEL to R.string.cards_each) // "Rows"
        const val BADGE = 0xFFFF453A.toInt()
        // Dark mode tokens, for AMOLED: true black, and near-black cards that read as cards because their edge
        // catches light (brightest along the top), the way light-mode cards use a soft shadow.
        const val DARK_BG = 0xFF000000.toInt() // home ground, settings panel, inset boxes
        const val DARK_SURFACE = 0xFF0B0B0B.toInt() // placeholders behind images
        const val DARK_TEXT = 0xFFF5F5F5.toInt()
        const val DARK_TEXT_2 = 0xFF7A7A7A.toInt() // values, unfocused rows, supporting text
        // Translucent (70% at rest, 80% selected), so the wallpaper shows through the tiles like frosted glass.
        /** Android's own Watch Next list, listed with the channels so it can be turned off too. */
        const val WATCH_NEXT = "watchnext"
        val DARK_CARD = intArrayOf(0xB30B0B0B.toInt(), 0xB3040404.toInt())
        val DARK_CARD_LIT = intArrayOf(0xCC141414.toInt(), 0xCC070707.toInt())
        const val RIM_TOP = 0x17FFFFFF // white 9%
        const val RIM_SIDE = 0x0AFFFFFF // white 4%
        const val RIM_TOP_LIT = 0x38FFFFFF // white 22%
        const val RIM_SIDE_LIT = 0x17FFFFFF
        const val PANEL_EDGE = 0x0FFFFFFF // white 6%, the settings panel's left edge
        const val HELP_URL = "https://github.com/aradhyas8/Plume_Launcher#home-button-on-android-13-and-newer"
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val SLIDER = "\u0000%" // row value drawn as a slider: SLIDER + 0-100
        const val FADE_STEP = 5 // percent per Left/Right on a fade slider
        const val SLIDER_DP = 96 // a slider's width
        const val SLIDER_VALUE_DP = 72 // width of the value beside a slider
        const val BANNER_DIM = 0.3f // black laid over a colour banner at rest, so the selected one stands out
        const val DARK_DIM = 115 // alpha of the black laid over the Bing picture in dark mode (45%)
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app ahead of the rest
        const val ROW_CARDS = 20 // the most cards a row holds
        const val SWAP_OUT_MS = 90L // another source of the shows row: what is there fades out ...
        const val SWAP_IN_MS = 150L // ... and the new content fades in
        const val FADING = "fading" // the tag of a card that is fading out
        const val CARDS_AHEAD = 8 // Continue Watching cards built past the focused one (see addCardsTo)
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = linkedMapOf(
            1 to R.string.every_1, 5 to R.string.every_5, 10 to R.string.every_10, 15 to R.string.every_15, 30 to R.string.every_30,
            60 to R.string.every_60, 1440 to R.string.every_1440,
        )
        const val CHOSEN = "✓"
        const val REPO = "aradhyas8/Plume_Launcher"
        const val UPDATE_STATUS = "dev.plume.UPDATE_STATUS"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8
        /** Icon style setting -> its name. */
        val ICON_STYLES = linkedMapOf(ICON_STYLE_KEYS[0] to R.string.monochrome, ICON_STYLE_KEYS[1] to R.string.colour, ICON_STYLE_KEYS[2] to R.string.banner, ICON_STYLE_KEYS[3] to R.string.banner_mono)
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        /** Shown names for the wallpaper keys saved in prefs (the keys stay English). */
        val WALLPAPER_NAMES = mapOf(
            "Rockies (day)" to R.string.wp_rockies_day, "Rockies (dusk)" to R.string.wp_rockies_dusk,
            "Pure black" to R.string.wp_pure_black, "Soft white" to R.string.wp_soft_white,
        )
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        @Volatile var APPS: List<App>? = null
        @Volatile var SOURCES: List<App>? = null
        /** Mono marks made by glyph(), by package; null = icon has no usable symbol. Main thread only. */
        val GLYPHS = HashMap<String, Bitmap?>()
        /** accent() colours by package; null = no clear colour. Main thread only. */
        val ACCENTS = HashMap<String, Int?>()

        /**
         * Colour mode tints for the bundled marks: Simple Icons' brand hex, except where that's too dark to
         * read on dark cards (PlayStation, Prime Video) or grey (Google Play), where the brand's brighter blue/green is used.
         */
        val BRAND_COLOURS = mapOf(
            "playstation" to 0xFF0070D1.toInt(),
            "xbox" to 0xFF107C10.toInt(),
            "nintendoswitch" to 0xFFE60012.toInt(),
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.stremio.one" to 0xFF8D3DAF.toInt(),
            "io.gh.reisxd.tizentube.cobalt" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tvmusic" to 0xFFFF0000.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "org.videolan.vlc" to 0xFFFF8800.toInt(),
            "com.android.vending" to 0xFF01875F.toInt(),
        )

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** HDMI tile icons by hdmiIcon key: brand marks (Simple Icons, CC0), the rest from Material Symbols (Apache 2.0). */
        val HDMI_ICONS = mapOf(
            "playstation" to R.drawable.logo_playstation,
            "xbox" to R.drawable.logo_xbox,
            "nintendoswitch" to R.drawable.logo_nintendoswitch,
            "appletv" to R.drawable.logo_appletv,
            "nvidia" to R.drawable.logo_nvidia,
            "chromecast" to R.drawable.logo_chromecast,
            "settop" to R.drawable.ic_settop,
            "playback" to R.drawable.ic_playback,
            "hdmi" to R.drawable.ic_hdmi,
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}

/** White or near-black over a background of luminance [lum], whichever has the higher contrast (WCAG). */
internal fun inkFor(lum: Float): Int {
    val dark = 0xFF111214.toInt()
    val darkLum = 0.0063f // luminance of #111214
    return if (1.05f / (lum + 0.05f) >= (lum + 0.05f) / (darkLum + 0.05f)) Color.WHITE else dark
}
