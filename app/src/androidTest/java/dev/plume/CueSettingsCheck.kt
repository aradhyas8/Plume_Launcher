package dev.plume

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Isolated emulator check: -e class dev.plume.CueSettingsCheck. Uses a fake ESPN transport and local ADB peer. */
class CueSettingsCheck(private val runner: Instrumentation) {
    private class Response(val body: String?) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var worker: Thread
    }

    private val responses = LinkedBlockingQueue<Response>()

    fun run() = with(runner) {
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    if (url.host != "site.api.espn.com") throw IOException("Network disabled for Cue check")
                    val response = responses.poll() ?: throw IOException("Unexpected ESPN request")
                    return object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getInputStream(): java.io.InputStream {
                            response.worker = Thread.currentThread()
                            response.started.countDown()
                            check(response.release.await(5, TimeUnit.SECONDS)) { "Response was not released" }
                            return ByteArrayInputStream(response.body?.toByteArray() ?: throw IOException("Fixture failure"))
                        }
                    }
                }
            }
        }
        val prefs = targetContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        val saved = listOf("setup_shown", "isl_on", "isl_sports_max", CueSports.PREF_GAMES).associateWith { prefs.all[it] }
        var home: MainActivity? = null
        try {
            prefs.edit().putBoolean("setup_shown", true).putBoolean("isl_on", false).putInt("isl_sports_max", 1)
                .putStringSet(CueSports.PREF_GAMES, emptySet()).commit()
            home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = home
            await { activity.hasWindowFocus() }
            runOnMainSync { activity.findViewById<View>(R.id.launcherSettings).performClick() }
            checkLetterJumps(activity)
            checkExpiredGame(activity)
            checkSportsPages(activity)
            checkAdbDismissal(activity)
        } finally {
            runOnMainSync { home?.setupDialog?.dismiss(); home?.finish() }
            prefs.edit().apply {
                saved.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> remove(key)
                    }
                }
            }.commit()
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun sportsField(name: String) = CueSports::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun pick(activity: MainActivity, method: String, list: List<CueSports.Team>? = null, focus: Int = 0) {
        MainActivity::class.java.getDeclaredMethod(method, CueSports.League::class.java, Int::class.javaPrimitiveType,
            List::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(activity, CueSports.LEAGUES.first(), focus, list, { activity.pickGames() })
    }

    private fun checkLetterJumps(activity: MainActivity) = runner.runOnMainSync {
        @Suppress("UNCHECKED_CAST")
        val boards = sportsField("lastBoard").get(CueSports) as MutableMap<String, List<SportsGame>>
        val key = CueSports.LEAGUES.first().key
        val saved = boards[key]
        try {
            boards[key] = listOf(SportsGame("today", key, System.currentTimeMillis(), "in", false, "", "",
                SportsSide("a", "A", "Alpha", "0", null), SportsSide("b", "B", "Beta", "0", null)))
            val teams = listOf("Alpha", "Beta", "Charlie", "Delta").mapIndexed { i, name ->
                CueSports.Team(('a' + i).toString(), name, name.take(1), null)
            }
            fun jump(from: Int, keyCode: Int, expected: Int) {
                pick(activity, "pickTeams", teams, from)
                activity.currentFocus!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                val rows = activity.findViewById<ViewGroup>(R.id.settingsList)
                check(rows.getChildAt(expected).hasFocus()) { "Team jump selected the wrong row" }
            }
            jump(2, KeyEvent.KEYCODE_DPAD_LEFT, 2) // Previous letters contain Today rows only.
            jump(3, KeyEvent.KEYCODE_DPAD_LEFT, 2)
            jump(0, KeyEvent.KEYCODE_DPAD_RIGHT, 2)
            boards[key] = emptyList()
            jump(2, KeyEvent.KEYCODE_DPAD_LEFT, 1)
        } finally {
            if (saved == null) boards.remove(key) else boards[key] = saved
        }
    }

    private fun checkExpiredGame(activity: MainActivity) = runner.runOnMainSync {
        val shown = sportsField("shown")
        val app = sportsField("app")
        val savedShown = shown.get(CueSports)
        val savedApp = app.get(CueSports)
        val now = System.currentTimeMillis()
        val constructor = Class.forName("dev.plume.CueSports\$Shown").declaredConstructors.single().apply { isAccessible = true }
        fun game(id: String, state: String) = SportsGame(id, "football/nfl", now, state, state == "post", "", "",
            SportsSide("a", "A", "Alpha", "1", null), SportsSide("b", "B", "Beta", "0", null))
        try {
            app.set(CueSports, activity.applicationContext)
            shown.set(CueSports, listOf(
                constructor.newInstance(game("expired", "post"), SPORTS_PIN, now, 0L, now),
                constructor.newInstance(game("live", "in"), SPORTS_TEAM, now, 0L, Long.MAX_VALUE)))
            check(CueSports.items(now - 1).single().key == "sports:expired")
            check(CueSports.items(now).single().key == "sports:live") { "Expired game consumed the only slot" }
        } finally {
            shown.set(CueSports, savedShown)
            app.set(CueSports, savedApp)
        }
    }

    private fun checkSportsPages(activity: MainActivity) {
        val teams = """{"sports":[{"leagues":[{"teams":[{"team":{"id":"a","displayName":"Alpha","abbreviation":"A"}}]}]}]}"""
        val games = """{"events":[]}"""
        @Suppress("UNCHECKED_CAST")
        val boards = sportsField("lastBoard").get(CueSports) as MutableMap<String, List<SportsGame>>
        val key = CueSports.LEAGUES.first().key
        val saved = boards[key]
        try {
            boards[key] = emptyList() // Team requests do not need a second scoreboard fetch in this check.
            for (leave in 0..2) {
                for ((method, body) in listOf("pickTeams" to teams, "pickTeams" to null, "pickLeagueGames" to games, "pickLeagueGames" to null)) {
                    val response = Response(body)
                    repeat(if (method == "pickLeagueGames" && body != null) 2 else 1) { responses.add(response) }
                    runner.runOnMainSync { pick(activity, method) }
                    check(response.started.await(3, TimeUnit.SECONDS)) { "Sports request did not start" }
                    when (leave) {
                        1 -> runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        2 -> runner.runOnMainSync {
                            activity.pickGames()
                            activity.closeLauncherSettings()
                            activity.findViewById<View>(R.id.launcherSettings).performClick()
                            activity.pickGames()
                        }
                    }
                    if (leave == 1) runner.runOnMainSync {
                        check(activity.findViewById<TextView>(R.id.settingsTitle).text == activity.getString(R.string.isl_games)) {
                            "Back did not return to Games"
                        }
                    }
                    response.release.countDown()
                    response.worker.join(3_000)
                    check(!response.worker.isAlive) { "Sports request did not finish" }
                    check(responses.isEmpty()) { "Sports fixture did not serve every request" }
                    runner.waitForIdleSync()
                    runner.runOnMainSync {
                        val title = activity.findViewById<TextView>(R.id.settingsTitle).text
                        if (leave == 0) {
                            check(title == CueSports.LEAGUES.first().name)
                            val expected = when {
                                method == "pickTeams" && body != null -> "Alpha"
                                method == "pickTeams" -> activity.getString(R.string.isl_teams_failed)
                                body != null -> activity.getString(R.string.isl_league_none)
                                else -> activity.getString(R.string.isl_games_failed)
                            }
                            check(hasText(activity.findViewById(R.id.settingsList), expected)) { "Active sports response did not update its page" }
                        } else check(title == activity.getString(R.string.isl_games)) { "Abandoned sports callback reopened its page" }
                    }
                }
            }
        } finally {
            if (saved == null) boards.remove(key) else boards[key] = saved
        }
    }

    private fun hasText(view: View, text: String): Boolean = when (view) {
        is TextView -> view.text.toString() == text
        is ViewGroup -> (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
        else -> false
    }

    private fun checkAdbDismissal(activity: MainActivity) {
        // The emulator uses its own transport; this peer never contacts the host or the user's TV.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3_000
            val endpoint = field("cueAdbEndpoint")
            val saved = endpoint.get(activity)
            try {
                runner.runOnMainSync {
                    check(saved == InetSocketAddress("127.0.0.1", 5555))
                    endpoint.set(activity, server.localSocketAddress)
                }
                for (mode in 0..3) {
                    runner.runOnMainSync { call(activity, "cueGrantHere") }
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        lateinit var socket: Socket
                        runner.runOnMainSync {
                            socket = field("cueAdbSocket").get(activity) as Socket
                            when (mode) {
                                0 -> activity.setupDialog!!.cancel() // Back uses Dialog.cancel().
                                1 -> activity.setupDialog!!.dismiss()
                                2 -> activity.homePopup(0, "Replacement", null, null, "Done" to {})
                                else -> activity.finish()
                            }
                        }
                        await { socket.isClosed }
                        peer.getInputStream().readBytes() // Closing the request must release its blocked read.
                        val worker = Thread.getAllStackTraces().keys.firstOrNull { it.name == "cue-local-adb" }
                        worker?.join(3_000)
                        check(worker?.isAlive != true)
                        runner.waitForIdleSync()
                        runner.runOnMainSync {
                            check(field("cueAdbSocket").get(activity) == null)
                            check(field("cueAdbDialog").get(activity) == null)
                            when (mode) {
                                2 -> check(activity.setupDialog?.isShowing == true)
                                3 -> check(activity.isDestroyed)
                                else -> check(activity.setupDialog == null) { "Late ADB result reopened a dismissed sheet" }
                            }
                            activity.setupDialog?.dismiss()
                        }
                    }
                }
            } finally {
                runner.runOnMainSync { endpoint.set(activity, saved) }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        do {
            var done = false
            runner.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        error("Cue did not reach the expected state")
    }
}
