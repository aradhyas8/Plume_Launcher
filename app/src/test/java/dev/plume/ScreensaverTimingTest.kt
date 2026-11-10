package dev.plume

import org.junit.Assert.*
import org.junit.Test

class ScreensaverTimingTest {
    @Test fun androidTimeoutEnablesDreamWithoutChangingSelection() {
        val settings = mutableMapOf<String, String?>("screensaver_components" to "other/.Dream")
        saveScreensaverDelay(10, false, settings::get) { key, value -> settings[key] = value; true }
        assertEquals("600000", settings["screen_off_timeout"])
        assertEquals("1", settings["screensaver_enabled"])
        assertEquals("other/.Dream", settings["screensaver_components"])
        assertFalse(settings.containsKey("contextual_screen_off_timeout"))
    }

    @Test fun fireTimeoutUpdatesBothClocksAndNeverKeepsThem() {
        val settings = mutableMapOf<String, String?>()
        val write = { key: String, value: String? -> settings[key] = value; true }
        saveScreensaverDelay(5, true, settings::get, write)
        assertEquals("300000", settings["screen_off_timeout"])
        assertEquals("300000", settings["contextual_screen_off_timeout"])
        saveScreensaverDelay(0, true, settings::get, write)
        assertEquals("0", settings["screensaver_enabled"])
        assertEquals("300000", settings["screen_off_timeout"])
        assertEquals("300000", settings["contextual_screen_off_timeout"])
    }

    @Test fun deniedWriteRestoresEarlierValuesIncludingAbsentKeys() {
        val original = mapOf("screen_off_timeout" to "600000", "screensaver_enabled" to "0")
        val settings = original.toMutableMap<String, String?>()
        assertThrows(IllegalStateException::class.java) {
            saveScreensaverDelay(5, true, settings::get) { key, value ->
                if (key == "screensaver_enabled" && value == "1") false
                else { if (value == null) settings.remove(key) else settings[key] = value; true }
            }
        }
        assertEquals(original, settings)
    }

    @Test fun silentReadbackFailureRestoresTimeout() {
        val original = mapOf("screen_off_timeout" to "600000", "screensaver_enabled" to "1")
        val settings = original.toMutableMap<String, String?>()
        assertThrows(IllegalStateException::class.java) {
            saveScreensaverDelay(5, false, settings::get) { key, value ->
                if (key != "screen_off_timeout" || value != "300000") settings[key] = value
                true
            }
        }
        assertEquals(original, settings)
    }

    @Test fun invalidDelayWritesNothing() {
        assertThrows(IllegalArgumentException::class.java) {
            saveScreensaverDelay(-1, false, { error("Unexpected read") }) { _, _ -> error("Unexpected write") }
        }
    }
}
