package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScenesTest {
    @Test fun builtInScenesHaveDistinctSafeFilesAndSecureSources() {
        assertEquals(4, VIDEO_SCENES.count { it.source == "NASA Earth" })
        assertEquals(4, VIDEO_SCENES.count { it.source == "Apple Aerial" })
        assertEquals(4, VIDEO_SCENES.count { it.source == "Amazon Fire TV" })
        assertEquals(VIDEO_SCENES.size, VIDEO_SCENES.map { it.file }.toSet().size)
        assertTrue(VIDEO_SCENES.all { it.file.matches(Regex("[A-Za-z0-9_]+\\.mp4")) && it.url.startsWith("https://") })
        assertEquals("nasa_wholeEarthNight_1080.mp4", VIDEO_SCENES.first().file)
    }
}
