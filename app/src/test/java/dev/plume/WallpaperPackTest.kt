package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WallpaperPackTest {
    private val root = listOf(File("."), File("..")).first { File(it, "wallpapers").isDirectory }

    @Test fun everyDownloadHasADistinctWebpAsset() {
        val source = File(root, "app/src/main/java/dev/plume/MainActivity.kt").readText()
        val catalog = source.substringAfter("val PACK_WALLPAPERS = listOf(").substringBefore("const val PACK_URL")
        val files = Regex("Triple\\(\"([^\"]+\\.webp)\"").findAll(catalog).map { it.groupValues[1] }.toList()
        assertEquals(10, files.size)
        assertEquals(files.size, files.toSet().size)
        assertEquals(files.toSet(), File(root, "wallpapers").listFiles()!!.filter { it.extension == "webp" }.map { it.name }.toSet())
        assertEquals(6, Regex(", false\\)").findAll(catalog).count())
        assertEquals(4, Regex(", true\\)").findAll(catalog).count())
        for (name in files) {
            assertTrue(name.matches(Regex("[A-Za-z0-9_]+\\.webp")))
            val file = File(root, "wallpapers/$name")
            assertTrue("Missing download asset: $name", file.isFile)
            val header = file.inputStream().use { it.readNBytes(12) }.toString(Charsets.US_ASCII)
            assertTrue("Invalid WebP: $name", header.startsWith("RIFF") && header.endsWith("WEBP"))
        }
    }

}
