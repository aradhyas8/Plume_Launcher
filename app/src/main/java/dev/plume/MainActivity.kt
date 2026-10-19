package dev.plume

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONObject

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}

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

/** Allowed to start an activity from the background because the Home button service is bound. */
class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class MainActivity : Activity() {

    private class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable)
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?)

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
    private var appsDirty = APPS == null
    private var watchKey: String? = null
    private var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    private var lastApp: View? = null
    private var lastCard: View? = null
    private var editing = false
    private var moveOne = false // editing started from an app's Move: OK places it and ends editing
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE
    private var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    private var cardLit = cardRest

    private lateinit var appRow: LinearLayout
    private lateinit var watchRows: LinearLayout // one section (title, scroller, row) per app
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: ScrollView
    private lateinit var allApps: View
    private lateinit var allAppsGrid: GridLayout
    private lateinit var wifi: ImageView

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it); ACCENTS.remove(it) } // an update may bring a new icon
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
        appRow = findViewById(R.id.appRow)
        watchRows = findViewById(R.id.watchRows)
        watchScroll = findViewById(R.id.watchScroll)
        // Clip to its own bounds (its parent leaves clipping off for focus lift), so rows below the
        // visible height stay hidden until scrolled to.
        watchScroll.outlineProvider = ViewOutlineProvider.BOUNDS
        watchScroll.clipToOutline = true
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        applyTheme()
        loadWallpaper()

        // Top bar: network, apps on home, All apps, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            R.id.addAppsButton to { pickRowApps(fromSettings = false) },
            R.id.allAppsButton to { openAllApps() },
            R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
            R.id.launcherSettings to { openLauncherSettings() },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ring.setColor(if (!focused) 0 else if (dark) 0xFF2A2A32.toInt() else Color.WHITE)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
        }
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(focus = 1) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: open settings on the Home button row, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { openLauncherSettings(focus = HOME_BUTTON_ROW) }
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
        ensureHomeService()
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
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && !paintingsReady(this)) downloadPaintings {}
        checkUpdate(force = false)
    }

    /** Auto theme flips at 7:00 and 19:00 even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (wantDark() != dark) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        refreshWatchNext()
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(DEFAULT_HOME_ROW)
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
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
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission).
     */
    private fun ensureHomeService() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED || homeServiceOn()) return
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

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        if (homeServiceOn()) return
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(HOME_BUTTON_ROW)
        Toast.makeText(this, R.string.turn_on_home_service, Toast.LENGTH_LONG).show()
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
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
        } else if (!isDefaultHome()) {
            openSettings(Settings.ACTION_HOME_SETTINGS)
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        watchSections().forEach { scrollToStart(watchRowOf(it).parent as HorizontalScrollView) }
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = prefersDark(prefs)

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) 0xFFF5F5F7.toInt() else 0xFF111214.toInt()
        // Dark cards are matte black glass on a true-black ground: lit faintly from the top and fading to
        // near-black, no outline (outlines read as a grey grid). Focus brightens the glass, never to grey.
        // Light cards are frosted grey glass, so they still read on white wallpapers; focus turns them white.
        cardRest = if (dark) intArrayOf(0xFF16161B.toInt(), 0xFF060607.toInt()) else intArrayOf(0xD9E2E5E9.toInt(), 0xD9D8DCE1.toInt())
        cardLit = if (dark) intArrayOf(0xFF26262E.toInt(), 0xFF0C0C0F.toInt()) else intArrayOf(Color.WHITE, Color.WHITE)
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) Color.BLACK else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        allApps.setBackgroundColor(ground)
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather)) {
            findViewById<TextView>(id).setTextColor(fg)
        }
        wifi.setColorFilter(fg)
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/dev.plume/files/wallpapers/
    // (adb push, no permission needed) and get the fade painted once when loaded.
    // Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList())

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null) ?: defaultWallpaper(forDark)

    /** Display name: built-in ones are translated (their pref values stay English); photos keep their file name. */
    private fun wallpaperName(choice: String) = WALLPAPER_NAMES[choice]?.let { getString(it) }
        ?: choice.substringAfter(':').substringBeforeLast('.')

    private fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            view.setImageDrawable(null)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } ?: decodeWallpaper(fallback)
            ui.post { view.setImageBitmap(bmp) }
        }
    }

    private fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        val file = File(wallpaperDir(), choice.removePrefix("file:"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true // the fade is painted onto it
        }) ?: return null
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
            shader = LinearGradient(0f, h * 0.28f, 0f, h * 0.5f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        })
        // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
        // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.3f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /** Wallpapers list, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    private fun pickWallpaper(forDark: Boolean) {
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val items = listOf(getString(R.string.add_from_phone)) + choices.map { it.first } +
            if (own.isEmpty()) emptyList() else listOf(getString(R.string.remove_a_photo))
        val current = 1 + choices.indexOfFirst { it.second == currentWallpaper(forDark) }
        AlertDialog.Builder(this)
            .setTitle(if (forDark) R.string.wp_dark else R.string.wp_light)
            .setSingleChoiceItems(items.toTypedArray(), current) { d, i ->
                d.dismiss()
                when {
                    i == 0 -> uploadWallpaper(forDark)
                    i == choices.size + 1 -> removeWallpaper(own)
                    else -> setWallpaper(forDark, choices[i - 1].second)
                }
            }
            .show()
    }

    private fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choice).apply()
        if (forDark == dark) loadWallpaper()
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(if (forDark) 3 else 2)
    }

    private fun removeWallpaper(own: List<Pair<String, String>>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_photo_title)
            .setItems(own.map { it.first }.toTypedArray()) { _, i ->
                val (name, choice) = own[i]
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_photo, name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                            if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                        }.apply()
                        loadWallpaper()
                        renderSettings(2)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private var upload: UploadServer? = null

    /**
     * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
     * wallpaper and show it at once. The server runs only while this panel is open.
     */
    private fun uploadWallpaper(forDark: Boolean) {
        val ip = lanAddress() ?: return Toast.makeText(this, R.string.need_network, Toast.LENGTH_LONG).show()
        val dir = wallpaperDir() ?: return
        val status = label(getString(R.string.waiting_for_photo), 14f).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
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
            return Toast.makeText(this, R.string.upload_failed, Toast.LENGTH_LONG).show()
        }
        upload = server
        val url = "http://$ip:${server.port}/${server.code}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(220))) })
            addView(label(url, 15f).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.upload_hint), 13f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                alpha = 0.7f
                maxLines = 3
            })
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.upload_title)
            .setView(box)
            .setNegativeButton(R.string.done, null)
            .setOnDismissListener {
                server.stop()
                if (upload === server) upload = null
            }
            .show()
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

    private fun openLauncherSettings(focus: Int = 0) {
        findViewById<View>(R.id.settingsPanel).setBackgroundColor(if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
        renderSettings(focus)
    }

    private fun closeLauncherSettings() {
        settingsScrim.visibility = View.GONE
        homeFocusable(true)
        findViewById<View>(R.id.launcherSettings).requestFocus()
    }

    private fun renderSettings(focus: Int) {
        val theme = prefs.getString("theme", "light")!!
        val sort = prefs.getString("sort", "manual")!!
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
        val weather = prefs.getString("weather", "off")!!
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } }
        val names = rowNames()
        val colour = colourIcons()
        val rows = listOf<Triple<String, String, () -> Unit>>(
            Triple(getString(R.string.s_edit_row), "") { closeLauncherSettings(); startEdit(grab = null) },
            Triple(getString(R.string.s_theme), getString(mapOf("light" to R.string.theme_light, "dark" to R.string.theme_dark, "auto" to R.string.theme_auto)[theme] ?: R.string.theme_light)) {
                prefs.edit().putString("theme", mapOf("light" to "dark", "dark" to "auto", "auto" to "light")[theme])
                    .putBoolean("reopen_settings", true).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            Triple(getString(R.string.wp_light), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            Triple(getString(R.string.wp_dark), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            Triple(getString(R.string.s_sort), getString(if (sort == "manual") R.string.sort_manual else R.string.sort_usage)) {
                prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(4)
            },
            Triple(getString(R.string.apps_in_row), if (rowHidden == 0) getString(R.string.all) else getString(R.string.n_of_m, apps.size - rowHidden, apps.size)) { pickRowApps() },
            Triple(getString(R.string.s_names), getString(mapOf("focus" to R.string.names_focus, "always" to R.string.names_always, "never" to R.string.names_never)[names]!!)) {
                prefs.edit().putString("names", mapOf("focus" to "always", "always" to "never", "never" to "focus")[names]).apply()
                renderApps()
                renderSettings(6)
            },
            Triple(getString(R.string.s_icons), getString(if (colour) R.string.icons_colour else R.string.icons_mono)) {
                prefs.edit().putBoolean("colour_icons", !colour).apply()
                renderApps()
                renderSettings(7)
            },
            Triple(getString(R.string.s_restore_shows), if (hidden == 0) getString(R.string.none_hidden) else getString(R.string.n_hidden, hidden)) {
                prefs.edit().remove("hidden_watch").apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(8)
            },
            Triple(getString(R.string.s_cw_apps), if (cwOff == 0) getString(R.string.all_on) else getString(R.string.n_off, cwOff)) { pickWatchSources() },
            Triple(getString(R.string.s_weather), mapOf("off" to getString(R.string.off), "c" to "°C", "f" to "°F")[weather]!!) {
                prefs.edit().putString("weather", mapOf("off" to "c", "c" to "f", "f" to "off")[weather]).apply()
                showWeather()
                ui.removeCallbacks(weatherTick)
                ui.post(weatherTick)
                renderSettings(10)
            },
            Triple(getString(R.string.s_screensaver), when {
                downloading -> getString(R.string.downloading_gallery)
                galleryOn() -> getString(R.string.gallery)
                else -> getString(R.string.screensaver_value, screensaverName(), PAINTINGS_MB)
            }) {
                toggleGallery()
                renderSettings(SCREENSAVER_ROW)
            },
            Triple(getString(R.string.s_test_screensaver), "") { testScreensaver() },
            Triple(getString(R.string.s_home_button), getString(if (homeServiceOn()) R.string.on else R.string.off_press_to_turn_on)) { turnOnHomeButton() },
            Triple(getString(R.string.s_default_launcher), if (isDefaultHome()) "Plume" else getString(R.string.press_to_set)) { makeDefaultHome() },
            Triple(getString(R.string.s_updates), when {
                updating -> getString(R.string.updating)
                checking -> getString(R.string.checking)
                availableUpdate() != null -> getString(R.string.update_available, availableUpdate())
                else -> getString(R.string.up_to_date_version, myVersion())
            }) { availableUpdate()?.let { installUpdate(it) } ?: checkUpdate(force = true) },
        )
        settingsList.removeAllViews()
        rows.forEachIndexed { i, (title, value, action) ->
            settingsList.addView(settingsRow(title, value, action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                topMargin = dp(4)
            })
        }
        // Keep focus inside the panel: Up from the first row and Down from the last go nowhere.
        settingsList.getChildAt(0).let { it.nextFocusUpId = it.id }
        settingsList.getChildAt(rows.size - 1).let { it.nextFocusDownId = it.id }
        settingsList.getChildAt(focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    private fun settingsRow(title: String, value: String, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            addView(label(title, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(value, 13f).apply { alpha = 0.6f })
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                bg.colors = if (!focused) intArrayOf(0, 0) else if (dark) cardLit else intArrayOf(Color.WHITE, Color.WHITE)
            }
        }
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
            ui.post {
                apps = list
                APPS = list
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
                )
            } + consoleInputs()
    }

    /**
     * Devices on HDMI that name themselves over CEC (the PS5 shows up as "PlayStation 5"), as app tiles.
     * Opening one switches the TV to that HDMI input, which also wakes the device over CEC.
     */
    private fun consoleInputs(): List<App> = try {
        getSystemService(TvInputManager::class.java).tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId != null }
            .map { input ->
                val label = input.loadLabel(this).toString()
                val ps = label.contains("PlayStation", ignoreCase = true)
                App(
                    HDMI + input.parentId,
                    label,
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.parentId))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    if (ps) getDrawable(R.drawable.logo_playstation)!! else input.loadIcon(this) ?: getDrawable(R.drawable.ic_apps)!!,
                )
            }
            .distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
    }

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        // On a cold start the top bar grabs focus before the apps load: the first render takes it back.
        val takeFocus = appRow.childCount == 0 || currentFocus == null || currentFocus?.parent === appRow
        val gap = dp(GRID_GAP)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * (ROW_TILES - 1)) / ROW_TILES
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        val tiles = sortedApps().filter { it.pkg !in hidden }.map { appTile(it, labeled = false) }
        if (tiles.isEmpty()) return showNames()
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, dp(76)).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own.onFocusChange(v, focused)
                if (focused) lastApp = v
                showRowLabel(v, focused)
            }
        }
        showNames()
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            Toast.makeText(this, R.string.tip_hold, Toast.LENGTH_LONG).show()
        }
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    private fun rowNames() = prefs.getString("names", "focus")!!

    /** Each app's own shipped icon instead of the mono mark, in the row and All apps. */
    private fun colourIcons() = prefs.getBoolean("colour_icons", false)

    /** Hold OK on a row app: a short menu instead of a hidden mode, so moving and removing are both one click away. */
    private fun appMenu(v: View, pkg: String) {
        val items = listOf<Pair<String, () -> Unit>>(
            getString(R.string.menu_move) to { startEdit(grab = v) },
            getString(R.string.menu_remove) to {
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).apply()
                renderApps()
                Toast.makeText(this, R.string.removed_from_row, Toast.LENGTH_SHORT).show()
            },
            getString(R.string.menu_open) to { apps.firstOrNull { it.pkg == pkg }?.let { launch(it) } },
        )
        AlertDialog.Builder(this)
            .setTitle(apps.firstOrNull { it.pkg == pkg }?.label ?: v.contentDescription ?: pkg)
            .setItems(items.map { it.first }.toTypedArray()) { d, i -> d.dismiss(); items[i].second() }
            .show()
    }

    /** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
    private fun appTile(a: App, labeled: Boolean = true): View {
        val logo = LOGOS[a.pkg]
        val custom = customIcon(a.pkg)
        val name = a.label.takeIf { labeled }
        // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
        val colour = colourIcons()
        val t = when {
            custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
            a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg,
                tint = BRAND_COLOURS["playstation"]?.takeIf { colour && a.label.contains("PlayStation", ignoreCase = true) }) { launch(a) }
            logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
                wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14),
                tint = BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
            else -> cachedGlyph(a)?.let { tile(name, it, true, a.pkg, tint = if (colour) cachedAccent(a) else null) { launch(a) } }
                ?: if (colour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
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
            row.addView(label(t.contentDescription.toString(), 13f).apply {
                gravity = Gravity.CENTER
                alpha = if (dark) 0.85f else 1f
            }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
        }
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    private fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<TextView>(R.id.rowLabel)
        if (focused && !editing && rowNames() == "focus") {
            label.text = v.contentDescription
            // Posted: on first render focus lands before layout, when every width is still 0.
            // Relative to where the label is laid out (the left in LTR, the right in RTL).
            v.post { label.translationX = v.left + v.width / 2f - label.left - label.width / 2f }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        } else {
            ui.post { if (currentFocus?.parent !== appRow || editing) label.animate().alpha(0f).setDuration(120).start() }
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
            label(it, 13f).apply {
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
                img.setPadding(dp(16), dp(20), dp(16), dp(20))
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
        // The settings panel is a vertical list: don't let Left/Right leak focus to the home screen behind it.
        if (settingsScrim.visibility == View.VISIBLE &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) return true
        if (editing && editKey(e, confirm)) return true
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
        // Down from the apps lands in the first Continue Watching row, where you last were in it.
        val firstRow = watchSections().firstOrNull()?.let { watchRowOf(it) }
        val cards = if (watchScroll.visibility != View.VISIBLE) null
            else alive(lastCard)?.takeIf { it.parent?.parent === firstRow } ?: watchCardAt(0, 0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        // "Start" and "end" of a row: Left and Right, swapped when the layout is mirrored (RTL).
        val start = startKey()
        val end = endKey()
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: cards ?: v // the apps row can be empty (all hidden)
                KeyEvent.KEYCODE_DPAD_UP -> v
                start -> if (v.id == R.id.wifi) v else null
                end -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                start -> if (appRow.indexOfChild(v) == 0) v else null
                end -> if (appRow.indexOfChild(v) == appRow.childCount - 1) v else null
                else -> null
            }
            v.getTag(R.id.watchRows) is Prog -> {
                // Up/Down move between the per-app rows (same position, clamped); Up from the first goes to the apps.
                val row = v.parent.parent as ViewGroup
                val i = row.indexOfChild(v.parent as View)
                val r = watchSections().indexOfFirst { watchRowOf(it) === row }
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (r <= 0) apps ?: top else watchCardAt(r - 1, i) ?: v
                    KeyEvent.KEYCODE_DPAD_DOWN -> watchCardAt(r + 1, i) ?: v
                    // Past the first card shows the row's ▲ ▼ (▼ on the top row, where ▲ can't move it).
                    start -> if (i == 0) sectionOf(v)?.let { arrowOf(it, if (r == 0) 1 else 0) } ?: v else null
                    end -> if (i == row.childCount - 1) v else null
                    else -> null
                }
            }
            v.tag == ARROW -> { // ▲ ▼: Up/Down switch between the two, toward the row goes back to its cards
                val section = sectionOf(v) ?: return v
                val up = arrowOf(section, 0)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> up
                    KeyEvent.KEYCODE_DPAD_DOWN -> arrowOf(section, 1)
                    start -> v
                    end -> watchCardAt(watchRows.indexOfChild(section), 0) ?: v
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
            allApps.visibility == View.VISIBLE && tag != null -> { // grid: pin to the front of the row (unhiding it)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag).apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow && tag != null -> appMenu(v, tag)
            v.getTag(R.id.watchRows) is Prog -> hideWatch(v.getTag(R.id.watchRows) as Prog)
        }
    }

    private fun appTileCount() = appRow.childCount

    private fun startEdit(grab: View?) {
        editing = true
        moveOne = grab != null
        appsTitle.setText(if (moveOne) R.string.moving_hint else R.string.editing_hint)
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
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
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
            // Moving one app (from its hold menu): OK places it and we're done.
            if (e.action == KeyEvent.ACTION_UP && moveOne) endEdit()
            else if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) setGrabbed(if (grabbed === v) null else v)
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == startKey()) i - 1 else i + 1
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

    /** Also opened from the "+" in the top bar ([fromSettings] false), where there's no panel to refresh. */
    private fun pickRowApps(fromSettings: Boolean = true) {
        val list = sortedApps()
        val hidden = rowHiddenSet().toMutableSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.apps_in_row)
            .setMultiChoiceItems(list.map { it.label }.toTypedArray(), list.map { it.pkg !in hidden }.toBooleanArray()) { _, i, on ->
                if (on) hidden -= list[i].pkg else hidden += list[i].pkg
                prefs.edit().putStringSet("row_hidden", hidden.toSet()).apply()
            }
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener {
                renderApps()
                if (fromSettings) renderSettings(5)
            }
            .show()
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
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
                marginEnd = gap // the gap follows the reading direction
                bottomMargin = gap
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

    private fun screensaverName(): String {
        val pkg = ComponentName.unflattenFromString(currentScreensaver())?.packageName ?: return getString(R.string.off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.system_default)
        }
    }

    private fun toggleGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.needs_adb, Toast.LENGTH_LONG).show()
            return
        }
        val cr = contentResolver
        if (galleryOn()) {
            Settings.Secure.putString(cr, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
            paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
        } else if (!paintingsReady(this)) {
            downloadPaintings { toggleGallery() }
        } else {
            prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
            Settings.Secure.putString(cr, "screensaver_components", gallery())
            Settings.Secure.putInt(cr, "screensaver_enabled", 1)
        }
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, getString(R.string.downloading_paintings, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
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
                if (ok) then() else Toast.makeText(this, R.string.paintings_failed, Toast.LENGTH_LONG).show()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(SCREENSAVER_ROW)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    private fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_dream_now, Toast.LENGTH_SHORT).show()
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
        if (force) renderSettings(UPDATE_ROW)
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
                    if (!force) Toast.makeText(this, getString(R.string.update_toast, ver), Toast.LENGTH_LONG).show()
                } else if (force) {
                    Toast.makeText(this, if (latest == null) R.string.update_check_failed else R.string.up_to_date, Toast.LENGTH_SHORT).show()
                }
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(UPDATE_ROW)
            }
        }
    }

    private fun installUpdate(ver: String) {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.allow_installs, Toast.LENGTH_LONG).show()
            return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        }
        updating = true
        renderSettings(UPDATE_ROW)
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
                    Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(UPDATE_ROW)
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
                Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(UPDATE_ROW)
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
                if (System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
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
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> R.string.wx_clear
        1, 2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67, in 80..82 -> R.string.wx_rain
        in 71..77, 85, 86 -> R.string.wx_snow
        in 95..99 -> R.string.wx_thunderstorm
        else -> null
    }?.let { getString(it) }.orEmpty()

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(emptyList())
            return
        }
        io.execute {
            val list = try {
                queryWatchNext()
            } catch (e: Exception) {
                emptyList()
            }
            ui.post { showWatchNext(list) }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
     *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
     *    on the TV is all it takes. Channel rows carry no timestamps, so they keep the app's order.
     */
    private fun queryWatchNext(): List<List<Prog>> {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
            .sortedByDescending { it.first }.map { it.second }
        val channels = continueChannels().flatMap { id ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { it.second }
        }
        val all = watchNext + channels
        publishingPkgs = all.mapNotNull { it.pkg }.toSet()
        val off = prefs.getStringSet("cw_off", emptySet())!!
        // One row per app: in the order set by moving rows (moveWatchRow), then by first appearance
        // (Watch Next apps, then channel ones).
        val order = prefs.getString("cw_order", "")!!.split(',')
        return all.filter { it.pkg !in off }.distinctBy { it.intent ?: it.title }.groupBy { it.pkg }.values
            .sortedBy { g -> order.indexOf(g.first().pkg.orEmpty()).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.take(PER_APP) }
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
            Toast.makeText(this, R.string.no_cw_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val off = prefs.getStringSet("cw_off", emptySet())!!.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.s_cw_apps)
            .setMultiChoiceItems(sources.map { it.label }.toTypedArray(), sources.map { it.pkg !in off }.toBooleanArray()) { _, i, on ->
                if (on) off -= sources[i].pkg else off += sources[i].pkg
                prefs.edit().putStringSet("cw_off", off.toSet()).apply()
            }
            .setPositiveButton(R.string.done, null)
            .setOnDismissListener {
                watchKey = null
                refreshWatchNext()
                renderSettings(9)
            }
            .show()
    }

    private fun continueChannels(): List<Long> {
        val ids = ArrayList<Long>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                if (c.getString(name)?.trim()?.lowercase() in CONTINUE_NAMES && c.getString(pkg) != packageName) {
                    ids += c.getLong(id)
                }
            }
        }
        return ids
    }

    /** Pairs of (last engagement time, item); time is 0 for channel rows, which don't have it. */
    private fun readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
        val out = ArrayList<Pair<Long, Prog>>()
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
                    if (season != null && episode != null) getString(R.string.season_episode, localDigits(season), localDigits(episode)) else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"))
                out += num("last_engagement_time_utc_millis") to prog
            }
        }
        return out
    }

    /** A season or episode number in the UI's digits (Arabic-Indic in Arabic, like the minutes left). */
    private fun localDigits(s: String) = s.toIntOrNull()?.let { String.format(java.util.Locale.getDefault(), "%d", it) } ?: s

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        Toast.makeText(this, R.string.hidden_from_cw, Toast.LENGTH_SHORT).show()
        refreshWatchNext()
    }

    /** Small source-app mark for the details line: black logo when we have one, else greyscale icon. */
    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hr, min / 60, min % 60) else getString(R.string.time_left_min, min)
    }

    /** One list per app; each becomes its own row under the app's name. */
    private fun showWatchNext(groups: List<List<Prog>>) {
        val key = groups.joinToString("||") { g -> g.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" } }
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        watchKey = key
        // Where focus was (row, card), to put it back after the rebuild.
        val focusCol = currentFocus?.takeIf { it.getTag(R.id.watchRows) is Prog }?.parent as? View
        val focusRow = focusCol?.parent as? ViewGroup
        val rowAt = focusRow?.let { watchSections().indexOfFirst { s -> watchRowOf(s) === it } } ?: -1
        val cardAt = focusRow?.indexOfChild(focusCol) ?: -1
        watchRows.removeAllViews()
        watchScroll.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        val rows = groups.mapIndexed { i, g -> watchSection(g.first().pkg, first = i == 0) }
        // ~16 ms per card and four show per row: build the first four of every row now, the rest a few per frame.
        val chunks = groups.map { it.chunked(4) }
        fun addCards(n: Int) {
            if (watchKey != key) return // a newer list replaced this one
            var more = false
            chunks.forEachIndexed { r, c ->
                c.getOrNull(n)?.forEach { rows[r].addView(watchCard(it, rows[r].childCount > 0)) }
                if (n + 1 < c.size) more = true
            }
            if (more) ui.post { addCards(n + 1) }
        }
        addCards(0)
        capWatchHeight()
        updateArrows()
        if (rowAt >= 0) (watchCardAt(rowAt.coerceAtMost(rows.size - 1), cardAt) ?: appRow.getChildAt(0))?.requestFocus()
    }

    private fun watchSections() = (0 until watchRows.childCount).map { watchRows.getChildAt(it) as ViewGroup }

    /**
     * Show one full row plus the next app's name (a hint that Down goes further), so the apps row
     * keeps its place instead of being pushed up against the clock.
     */
    private fun capWatchHeight() {
        watchScroll.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        watchScroll.requestLayout()
        if (watchRows.childCount > 1) watchRows.post {
            val next = watchSections().getOrNull(1) ?: return@post
            watchScroll.layoutParams.height = next.top + next.getChildAt(0).bottom + dp(4)
            watchScroll.requestLayout()
        }
    }

    /** A section is [title, frame[scroller, arrows[▲, ▼]]]; the scroller holds the row of cards. */
    private fun watchRowOf(section: ViewGroup) =
        ((section.getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as LinearLayout

    /** The focusable poster of card [i] in row [r] (clamped to the row's end), or null if there's no such row. */
    private fun watchCardAt(r: Int, i: Int): View? {
        val row = watchSections().getOrNull(r)?.let { watchRowOf(it) } ?: return null
        return (row.getChildAt(i.coerceIn(0, row.childCount - 1)) as? ViewGroup)?.getChildAt(0)
    }

    /** Adds one app's section (its name, then a scrolling row like the apps row) and returns the empty row. */
    private fun watchSection(pkg: String?, first: Boolean): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            tag = pkg // which app's row this is (saved as the row order)
        }
        val name = apps.firstOrNull { it.pkg == pkg }?.label ?: getString(R.string.continue_watching)
        section.addView(label(name, 14f).apply {
            alpha = 0.7f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (first) 6 else 14)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        // Same reach as the apps row: 16dp past each side and 24dp above/below for the focus lift.
        val scroller = SnapScrollView(this, null).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(row)
        }
        // ▲ and ▼ sit in the margin left of the first card (Projectivy style): Left from that card shows
        // them, and each OK moves the whole row one place. Hidden while the row's cards have focus.
        val gap = dp(GRID_GAP)
        val poster = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4 / 2
        val arrows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
        }
        for ((icon, step) in listOf(R.drawable.ic_arrow_up to -1, R.drawable.ic_arrow_down to 1)) {
            val ring = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
            arrows.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(fg)
                background = ring
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isFocusable = true
                tag = ARROW
                contentDescription = getString(if (step < 0) R.string.move_row_up else R.string.move_row_down)
                setOnFocusChangeListener { _, focused ->
                    ring.setColor(if (!focused) 0 else if (dark) 0xFF2A2A32.toInt() else Color.WHITE)
                    // Show both arrows while either has focus; posted so moving between them doesn't flicker.
                    ui.post { arrows.alpha = if (arrows.hasFocus()) 1f else 0f }
                    if (focused) watchScroll.post { watchScroll.smoothScrollTo(0, section.top) }
                }
                setOnClickListener { moveWatchRow(section, step, this) }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { if (step > 0) topMargin = dp(8) })
        }
        val frame = FrameLayout(this).apply { clipChildren = false }
        frame.addView(scroller, FrameLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // Frame starts 16dp left of the cards; the arrows go 24dp further out, 8dp clear of the first card.
        frame.addView(arrows, FrameLayout.LayoutParams(dp(32), dp(72)).apply {
            marginStart = -dp(24) // on the row's start side: left, or right when mirrored
            topMargin = dp(24) + (poster - dp(72)) / 2
        })
        section.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(-dp(16), -dp(16), -dp(16), -dp(24))
        })
        watchRows.addView(section)
        return row
    }

    /** A card, arrow or anything else inside a Continue Watching section → that section. */
    private fun sectionOf(v: View): ViewGroup? {
        var x: View = v
        while (true) {
            val p = x.parent as? View ?: return null
            if (p === watchRows) return x as ViewGroup
            x = p
        }
    }

    /** The ▲ (0) or ▼ (1) button of a section. */
    private fun arrowOf(section: ViewGroup, which: Int) =
        ((section.getChildAt(1) as ViewGroup).getChildAt(1) as ViewGroup).getChildAt(which)

    /** Fade the arrow that can't move its row (▲ on the top row, ▼ on the bottom one). */
    private fun updateArrows() {
        val sections = watchSections()
        sections.forEachIndexed { n, s ->
            arrowOf(s, 0).alpha = if (n == 0) 0.3f else 1f
            arrowOf(s, 1).alpha = if (n == sections.size - 1) 0.3f else 1f
        }
    }

    /** Moves the row one place up (-1) or down (+1); focus stays on the arrow that was pressed. */
    private fun moveWatchRow(section: ViewGroup, step: Int, arrow: View) {
        val i = watchRows.indexOfChild(section)
        val j = i + step
        if (j !in 0 until watchRows.childCount) return
        watchRows.removeViewAt(i)
        watchRows.addView(section, j)
        watchSections().forEachIndexed { n, s ->
            (s.getChildAt(0).layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (n == 0) 6 else 14)
        }
        // Scroll once the row is laid out in its new place (its top is stale until then).
        section.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                watchScroll.smoothScrollTo(0, t)
            }
        })
        arrow.requestFocus()
        updateArrows()
        prefs.edit().putString("cw_order", watchSections().joinToString(",") { (it.tag as String?).orEmpty() }).apply()
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
            setBackgroundColor(if (dark) 0xFF111113.toInt() else 0xFFDDE1E5.toInt())
        }
        // Long titles scroll while their poster is focused instead of staying cut off.
        val title = label(p.title, 14f).apply {
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        val frame = FrameLayout(this)
        style(frame, dp(8), card = true, lift = 1.1f) { focused ->
            title.isSelected = focused
            if (!focused) return@style
            lastCard = frame
            // Bring the whole section (its title too) to the top of the Continue Watching area.
            val section = sectionOf(frame) ?: return@style
            watchScroll.post { watchScroll.smoothScrollTo(0, section.top) }
        }
        frame.contentDescription = p.title
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        frame.setOnClickListener { open(p) }
        frame.setTag(R.id.watchRows, p) // holding OK hides it (see onHold)
        // 2:1 keeps logos and badges that providers bake into 16:9 art.
        col.addView(frame, LinearLayout.LayoutParams(w, w / 2))
        // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = p.progress
            // Laid out LTR so the platform style doesn't mirror it; progressBarDrawable picks the fill side.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            progressDrawable = progressBarDrawable()
            visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(8) })
        col.addView(title)
        val icon = sourceIcon(p.pkg)
        if (p.sub.isNotEmpty() || icon != null) {
            col.addView(label(p.sub, 12f).apply {
                alpha = 0.8f
                setPadding(0, dp(2), 0, 0)
                icon?.let {
                    it.setBounds(0, 0, dp(12), dp(12))
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            })
        }
        loadPoster(img, p.image)
        return col
    }

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (dark) 0x40FFFFFF else 0x26000000),
            // Fills from the start side: the right edge when mirrored (the bar itself is kept LTR, see watchCard).
            ClipDrawable(bar(fg), if (rtl()) Gravity.RIGHT else Gravity.LEFT, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        try {
            startActivity(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
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
        for (row in watchSections().map { watchRowOf(it) }) for (i in 0 until row.childCount) {
            val img = ((row.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
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
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius.toFloat()
            alpha = 0
        }
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (card) cardRest else intArrayOf(0, 0)).apply {
            cornerRadius = radius.toFloat()
        }
        v.background = bg
        if (!dark) v.foreground = sheen
        v.clipToOutline = true
        v.outlineSpotShadowColor = Color.BLACK
        v.outlineAmbientShadowColor = Color.BLACK
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheen.alpha = if (focused) 255 else 0
            if (card) bg.colors = if (focused) cardLit else cardRest
            v.animate()
                .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // Align by the layout's direction, not the text's: an English title sits at the right edge in RTL too.
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Right-to-left layout (Arabic, Hebrew, …): the screen is mirrored, and so are Left and Right. */
    private fun rtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** The remote key that moves toward the start of a row (Left, or Right when mirrored) and the one toward its end. */
    private fun startKey() = if (rtl()) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    private fun endKey() = if (rtl()) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

    /** Scrolls a row back to its first item: the left end, or the right end when mirrored. */
    private fun scrollToStart(row: HorizontalScrollView) {
        val content = row.getChildAt(0) ?: return
        row.scrollTo(if (rtl()) (content.width + row.paddingLeft + row.paddingRight - row.width).coerceAtLeast(0) else 0, 0)
    }

    private companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        const val SCREENSAVER_ROW = 11 // positions in the settings list (see renderSettings)
        const val HOME_BUTTON_ROW = 13
        const val DEFAULT_HOME_ROW = 14
        const val UPDATE_ROW = 15
        const val REPO = "aradhyas8/Plume_Launcher"
        const val UPDATE_STATUS = "dev.plume.UPDATE_STATUS"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val ARROW = "move-row" // tag of the ▲ ▼ buttons at the start of each Continue Watching row
        const val PER_APP = 12 // cards in each app's Continue Watching row
        // Channel names that mean "resume this": Stremio's, and SmartTube's history row (English and Arabic).
        val CONTINUE_NAMES = setOf("continue watching", "history", "watch history", "السجلّ", "السجل", "متابعة المشاهدة")
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        // Built-in wallpapers' translated names, by pref value (which stays English).
        val WALLPAPER_NAMES = mapOf(
            "res:Rockies (day)" to R.string.wp_rockies_day,
            "res:Rockies (dusk)" to R.string.wp_rockies_dusk,
            "plain:Pure black" to R.string.wp_pure_black,
            "plain:Soft white" to R.string.wp_soft_white,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        @Volatile var APPS: List<App>? = null
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
