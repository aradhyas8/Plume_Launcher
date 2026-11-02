package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class VideoScenesTest {
    @Test fun builtInScenesHaveDistinctSafeFilesAndSecureSources() {
        val file = listOf(File("src/main/res/raw/video_scenes.gz"), File("app/src/main/res/raw/video_scenes.gz")).first { it.exists() }
        assertTrue("video_scenes.gz must exist", file.exists())
        val scenes = FileInputStream(file).use { parseVideoScenes(it) }
        assertEquals(255, scenes.size)
        assertEquals(4, scenes.count { it.source == "NASA Earth" })
        assertEquals(139, scenes.count { it.source == "Apple Aerial" })
        assertEquals(112, scenes.count { it.source == "Amazon Fire TV" })
        assertEquals(scenes.size, scenes.map { it.file }.toSet().size)
        assertTrue(scenes.all { it.file.matches(Regex("[A-Za-z0-9_]+\\.mp4")) && it.url.startsWith("https://") })
        assertEquals("nasa_wholeEarthNight_1080.mp4", scenes.first().file)
    }
}
