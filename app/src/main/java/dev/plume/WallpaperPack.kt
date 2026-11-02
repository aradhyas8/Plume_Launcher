package dev.plume

import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal fun packFileName(name: String) = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.webp", RegexOption.IGNORE_CASE))

internal fun packPhotoName(file: String) = file.substringBeforeLast('.').replace(Regex("^\\d+[_-]"), "")
    .replace('_', ' ').replace('-', ' ')

internal data class PackPhoto(val file: String, val size: Long, val sha: String) {
    init {
        require(packFileName(file) && size in 12..50_000_000 && sha.matches(Regex("[a-f0-9]{40}")))
    }

    // GitHub identifies a file by the SHA-1 of its Git blob, including this header.
    private fun digest() = MessageDigest.getInstance("SHA-1").apply { update("blob $size\u0000".toByteArray(Charsets.UTF_8)) }

    fun current(dir: File) = File(dir, file).let { it.isFile && matches(it) }

    /** Replace only this file after verification. A failed download keeps the installed image. */
    fun download(dir: File, open: () -> InputStream): Boolean {
        if (current(dir)) return false
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val part = File(dir, ".$file.part")
        try {
            open().use { input -> part.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > size) throw IOException("Oversized wallpaper: $file")
                    out.write(buffer, 0, count)
                }
            } }
            val bytes = ByteArray(12)
            DataInputStream(part.inputStream()).use { it.readFully(bytes) }
            val header = bytes.toString(Charsets.US_ASCII)
            if (!header.startsWith("RIFF") || !header.endsWith("WEBP") || !matches(part)) throw IOException("Invalid wallpaper: $file")
            Files.move(part.toPath(), File(dir, file).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return true
        } finally {
            part.delete()
        }
    }

    private fun matches(file: File): Boolean {
        if (file.length() != size) return false
        val hash = digest()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == sha
    }
}
