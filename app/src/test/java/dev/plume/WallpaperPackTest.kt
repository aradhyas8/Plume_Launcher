package dev.plume

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class WallpaperPackTest {
    private val root = listOf(File("."), File("..")).first { File(it, "wallpapers").isDirectory }
    private fun image(text: String) = ("RIFF0000WEBP" + text).toByteArray()
    private fun photo(file: String, data: ByteArray): PackPhoto {
        val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${data.size}\u0000".toByteArray())
        hash.update(data)
        return PackPhoto(file, data.size.toLong(), hash.digest().joinToString("") { "%02x".format(it) })
    }

    private fun inDirectory(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("plume-pack").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun everyDownloadHasADistinctWebpAsset() {
        val files = File(root, "wallpapers").listFiles()!!.filter { it.extension == "webp" }
        assertTrue(files.isNotEmpty())
        assertEquals(files.size, files.map { it.name }.toSet().size)
        for (file in files) {
            assertTrue(packFileName(file.name))
            val header = file.inputStream().use { it.readNBytes(12) }.toString(Charsets.US_ASCII)
            assertTrue("Invalid WebP: ${file.name}", header.startsWith("RIFF") && header.endsWith("WEBP"))
        }
    }

    @Test fun installedAssetMatchesItsGitBlobId() = inDirectory { dir ->
        val data = image("known")
        File(dir, "fixture.webp").writeBytes(data)
        // Expected ID comes from git hash-object, independent of the test's photo helper.
        assertTrue(PackPhoto("fixture.webp", data.size.toLong(), "aa5375f6b3cc98beb04e59e991093cf45c0adeee").current(dir))
    }

    @Test fun replacementKeepsIdentityAndUnrelatedFiles() = inDirectory { dir ->
        val name = "01_Alpine_Dawn.webp"
        File(dir, name).writeBytes(image("1080p"))
        File(dir, "future-photo.webp").writeText("keep this")
        val data = image("4K replacement")
        val entry = photo(name, data)
        assertFalse(entry.current(dir))
        assertTrue(entry.download(dir) { ByteArrayInputStream(data) })
        assertArrayEquals(data, File(dir, name).readBytes())
        assertEquals("keep this", File(dir, "future-photo.webp").readText())
        assertTrue(entry.current(dir))
        assertFalse(entry.download(dir) { error("An unchanged file must not download") })
        assertEquals(setOf(name, "future-photo.webp"), dir.list()!!.toSet())
    }

    @Test fun sameSizeReplacementUsesContentHash() = inDirectory { dir ->
        val old = image("old")
        val new = image("new")
        File(dir, "photo.webp").writeBytes(old)
        val entry = photo("photo.webp", new)
        assertFalse(entry.current(dir))
        entry.download(dir) { ByteArrayInputStream(new) }
        assertArrayEquals(new, File(dir, "photo.webp").readBytes())
    }

    @Test fun failedDownloadsPreserveTheInstalledPhoto() = inDirectory { dir ->
        val old = image("old")
        File(dir, "photo.webp").writeBytes(old)
        val data = image("replacement")
        val entry = photo("photo.webp", data)
        val attempts = listOf(data.copyOf(14), image("wrong bytes"), data + byteArrayOf(1))
        for (broken in attempts) {
            assertThrows(IOException::class.java) { entry.download(dir) { ByteArrayInputStream(broken) } }
            assertArrayEquals(old, File(dir, "photo.webp").readBytes())
            assertEquals(setOf("photo.webp"), dir.list()!!.toSet())
        }
        assertThrows(IOException::class.java) { entry.download(dir) { throw IOException("offline") } }
        assertArrayEquals(old, File(dir, "photo.webp").readBytes())
    }

    @Test fun failedNewDownloadLeavesNoPhotoOrTemporaryFile() = inDirectory { dir ->
        val entry = photo("new-photo.webp", image("new"))
        assertThrows(IOException::class.java) { entry.download(dir) { ByteArrayInputStream(byteArrayOf(1)) } }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun invalidNamesMetadataAndNonImagesAreRejected() = inDirectory { dir ->
        for (name in listOf("../photo.webp", "a/b.webp", "a\\b.webp", ".hidden.webp", "x.webp?secret", "x.jpg")) {
            assertFalse(packFileName(name))
            assertThrows(IllegalArgumentException::class.java) { PackPhoto(name, 20, "a".repeat(40)) }
        }
        assertThrows(IllegalArgumentException::class.java) { PackPhoto("photo.webp", 50_000_001, "a".repeat(40)) }
        assertThrows(IllegalArgumentException::class.java) { PackPhoto("photo.webp", 20, "invalid") }
        val data = "this is not a WebP image".toByteArray()
        assertThrows(IOException::class.java) { photo("photo.webp", data).download(dir) { ByteArrayInputStream(data) } }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun futureFilesGetReadableNamesWithoutAnAppCatalogue() {
        assertEquals("Alpine Dawn", packPhotoName("01_Alpine_Dawn.webp"))
        assertEquals("Future Sunset", packPhotoName("11_Future-Sunset.webp"))
        assertTrue(packFileName("11_Future-Sunset.webp"))
    }
}
