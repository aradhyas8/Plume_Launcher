package dev.plume

import android.app.Instrumentation
import android.content.Context
import java.io.File

/** Framework JSON, storage, and preference checks. Uses separate test preferences and a separate file. */
class SettingsTransferCheck(private val runner: Instrumentation) {
    fun run() {
        val context = runner.targetContext
        val prefs = context.getSharedPreferences("settings-transfer-check", Context.MODE_PRIVATE)
        val file = File(SettingsTransfer.file(context).parentFile, "transfer-check.json")
        fun fails(action: () -> Unit) {
            var failed = false
            try { action() } catch (_: Exception) { failed = true }
            check(failed) { "Invalid import succeeded" }
        }
        try {
            val original = mapOf("theme" to "dark", "app_anim" to false, "row_hidden" to setOf("missing.app"),
                "wp_every" to 15, "geo_manual" to true, "geo_city" to "London", "geo_lat" to 51.5f, "geo_lon" to -0.1f)
            SettingsTransfer.save(file, original + mapOf("lock_pin" to "export-secret", "keymap" to "local"))
            val bytes = file.readBytes()
            check(!bytes.toString(Charsets.UTF_8).contains("export-secret"))
            check(SettingsTransfer.load(file) == original)
            prefs.edit().clear().putString("lock_pin", "destination-secret").putBoolean("setup_shown", true)
                .putString("clock_style", "bold").putString("ss_folder", "local-folder").putLong("upd_time", 123L).commit()
            SettingsTransfer.restore(prefs, SettingsTransfer.load(file))
            check(prefs.getString("theme", null) == "dark")
            check(prefs.getInt("wp_every", 0) == 15)
            check(prefs.getFloat("geo_lat", 0f) == 51.5f)
            check(prefs.getStringSet("row_hidden", emptySet()) == setOf("missing.app"))
            check(!prefs.contains("clock_style")) { "Missing supported setting did not reset" }
            check(prefs.getString("lock_pin", null) == "destination-secret")
            check(prefs.getBoolean("setup_shown", false))
            check(prefs.getString("ss_folder", null) == "local-folder")
            check(prefs.getLong("upd_time", 0) == 123L)
            val before = prefs.all
            for (bad in listOf("{", "{}", "[]", bytes.toString(Charsets.UTF_8) + "garbage",
                "{\"format\":\"plume-settings\",\"version\":2,\"settings\":{}}",
                "{\"format\":\"plume-settings\",\"version\":1,\"settings\":{\"clock_style\":\"bad\"}}",
                "{\"format\":\"plume-settings\",\"version\":1,\"settings\":{\"app_anim\":null}}")) {
                file.writeText(bad)
                fails { SettingsTransfer.restore(prefs, SettingsTransfer.load(file)) }
                check(prefs.all == before) { "Rejected file changed settings" }
            }
            fails { SettingsTransfer.decode(ByteArray(65_537)) }
            fails { SettingsTransfer.decode(("[".repeat(100) + "]".repeat(100)).toByteArray()) }
            SettingsTransfer.save(file, original)
            fails { SettingsTransfer.save(file, mapOf("clock_size" to "999")) }
            check(SettingsTransfer.load(file) == original) { "Failed save destroyed previous backup" }
            file.delete()
            fails { SettingsTransfer.load(file) }
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            context.deleteSharedPreferences("settings-transfer-check")
        }
    }
}
