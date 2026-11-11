package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockFormatTest {
    @Test fun acceptsPresetsAndQuotedText() {
        for (format in ClockFormat.entries) for (preset in format.presets) assertTrue(format.accepts(preset))
        assertTrue(ClockFormat.DATE.accepts("EEEE 'the' d 'of' MMMM"))
        assertTrue(ClockFormat.TIME.accepts("h 'o''clock' a"))
    }

    @Test fun rejectsUnsupportedTokensAndBrokenPatterns() {
        for (pattern in listOf("", "YYYY-MM-DD", "yyyy-MM-dd 'bad", "'label'", "yyyy\nMM", "d".repeat(41))) {
            assertFalse(pattern, ClockFormat.DATE.accepts(pattern))
        }
        assertFalse(ClockFormat.TIME.accepts("yyyy HH:mm"))
    }

    @Test fun backupUsesTheSameValidationAsTheEditor() {
        val valid = mapOf("date_format" to "yyyy-MM-dd EEEE", "time_format" to "HH:mm")
        assertEquals(valid, SettingsTransfer.portable(valid))
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("time_format" to "YYYY")) }
    }
}
