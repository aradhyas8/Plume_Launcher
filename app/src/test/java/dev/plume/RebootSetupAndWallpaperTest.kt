package dev.plume

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootSetupAndWallpaperTest {

    @Test fun googleTvLauncherIsActiveOnlyWhenEnabled() {
        assertTrue(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertTrue(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(isGoogleTvLauncher(false, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertFalse(isGoogleTvLauncher(false, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertFalse(isGoogleTvLauncher(true, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
    }

    @Test fun emptyFolderAtBootReturnsNullAndPreservesDeck() {
        val deck = ArrayDeque(listOf(10L, 20L))
        val picked = pickFolderPhoto(emptyList(), currentSaved = 10L, deck = deck)
        assertNull(picked)
        assertEquals(listOf(10L, 20L), deck.toList())
    }

    @Test fun savedPhotoReturnsDirectlyWhenPresent() {
        val deck = ArrayDeque(listOf(20L))
        val picked = pickFolderPhoto(listOf(10L, 20L), currentSaved = 10L, deck = deck)
        assertEquals(10L, picked)
        assertEquals(listOf(20L), deck.toList())
    }

    @Test fun missingSavedPhotoPicksNextFromDeck() {
        val deck = ArrayDeque(listOf(10L, 20L))
        val picked = pickFolderPhoto(listOf(20L, 30L), currentSaved = 99L, deck = deck)
        assertEquals(20L, picked)
        assertTrue(deck.isEmpty())
    }

    @Test fun emptyDeckRefillsAndPopsFirst() {
        val deck = ArrayDeque<Long>()
        val picked = pickFolderPhoto(listOf(1L, 2L), currentSaved = null, deck = deck) { it }
        assertEquals(1L, picked)
        assertEquals(listOf(2L), deck.toList())
    }
}
