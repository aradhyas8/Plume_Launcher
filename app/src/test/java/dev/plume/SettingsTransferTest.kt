package dev.plume

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsTransferTest {
    @Test fun exportsOnlyPortablePreferencesAndAppIds() {
        val result = SettingsTransfer.portable(mapOf(
            "theme" to "dark", "row_name" to "My shows", "lock_pin" to "secret", "setup_shown" to true,
            "upd_time" to 123L, "home_off" to false, "keymap" to "k183s4", "ss_folder" to "folder:5:Photos",
            "wp_light" to "video:local.mp4", "order" to "com.netflix.ninja,hdmi:local/HW1,org.videolan.vlc",
            "row_hidden" to setOf("hdmi:local/HW1", "com.spotify.tv.android"), "geo_lat" to 20f,
        ))
        assertEquals(setOf("theme", "row_name", "order", "row_hidden"), result.keys)
        assertEquals("com.netflix.ninja,org.videolan.vlc", result["order"])
        assertEquals(setOf("com.spotify.tv.android"), result["row_hidden"])
    }

    @Test fun validatesNumbersAndConvertsToPreferenceTypes() {
        val result = SettingsTransfer.validate(mapOf("wp_every" to 30L, "geo_manual" to true,
            "geo_city" to "London", "geo_lat" to 51.5, "geo_lon" to -0.1))
        assertEquals(30, result["wp_every"])
        assertEquals(51.5f, result["geo_lat"])
        assertEquals(-0.1f, result["geo_lon"])
    }

    @Test fun rejectsInvalidKnownValuesBeforeImport() {
        for (values in listOf(mapOf("clock_style" to "unknown"), mapOf("clock_size" to "999"),
            mapOf("app_anim" to "true"), mapOf("wp_every" to 1.5), mapOf("geo_manual" to true),
            mapOf("geo_lat" to Double.NaN), mapOf("geo_lon" to 181), mapOf("row_hidden" to listOf(3)),
            mapOf("order" to "hdmi:local"), mapOf("row_name" to "bad\u0000text"))) {
            assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(values) }
        }
    }

    @Test fun ignoresUnknownKeysAndKeepsNoSecrets() {
        assertEquals(mapOf("theme" to "light"), SettingsTransfer.validate(mapOf(
            "theme" to "light", "future_option" to mapOf("a" to true), "lock_pin" to "secret")))
        assertTrue(SettingsTransfer.validate(emptyMap<String, Any>()).isEmpty())
    }

    @Test fun acceptsAllPublishedDisplayChoices() {
        for (style in ICON_STYLE_KEYS) assertEquals(style, SettingsTransfer.validate(mapOf("icon_style" to style))["icon_style"])
        for (align in listOf("left", "centre", "right")) assertEquals(align, SettingsTransfer.validate(mapOf("row_align" to align))["row_align"])
    }

    @Test fun failedCommitRestoresDestinationSettings() {
        val original = mapOf<String, Any>("theme" to "light", "clock_style" to "bold", "lock_pin" to "secret", "wx_time" to 123L)
        val stored = original.toMutableMap()
        var commits = 0
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> stored.toMap()
                "edit" -> {
                    val pending = stored.toMutableMap()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { editor, call, args ->
                        when (call.name) {
                            "remove" -> { pending.remove(args!![0]); editor }
                            "commit" -> { stored.clear(); stored.putAll(pending); ++commits > 1 }
                            else -> { pending[args!![0] as String] = args[1]; editor }
                        }
                    }
                }
                else -> error("Unexpected preference call")
            }
        } as SharedPreferences
        assertThrows(java.io.IOException::class.java) { SettingsTransfer.restore(prefs, mapOf("theme" to "dark")) }
        assertEquals(original, stored)
        assertEquals(2, commits)
    }
}
