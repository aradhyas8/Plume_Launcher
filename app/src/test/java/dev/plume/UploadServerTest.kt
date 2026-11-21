package dev.plume

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadServerTest {
    @Test fun tokenProtectsPictureUploadAndFitsQr() {
        val temp = File.createTempFile("upload-test-", ".tmp")
        val picture = File.createTempFile("picture-test-", ".png")
        val before = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(4, 5, 6)
        picture.writeBytes(before)
        val server = UploadServer(temp, imagesOnly = true) { file, _ ->
            file.copyTo(picture, overwrite = true)
            "Test picture"
        }
        try {
            assertTrue(server.code.matches(Regex("[A-Za-z0-9_-]{32}")))
            assertTrue(Base64.getUrlDecoder().decode(server.code).size == 24)
            assertTrue(Qr.encode("http://255.255.255.255:65535/${server.code}").isNotEmpty())
            fun request(code: String, data: ByteArray? = null, readBody: Boolean = false): Pair<Int, String> {
                val connection = URL("http://127.0.0.1:${server.port}/$code").openConnection() as HttpURLConnection
                return try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    if (data != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "image/png")
                        connection.setFixedLengthStreamingMode(data.size)
                        connection.outputStream.use { it.write(data) }
                    }
                    val status = connection.responseCode
                    val body = if (readBody) connection.inputStream.bufferedReader().use { it.readText() } else ""
                    status to body
                } finally {
                    connection.disconnect()
                }
            }
            assertEquals(404, request("wrong").first)
            assertEquals(404, request("wrong", replacement).first)
            assertArrayEquals(before, picture.readBytes())
            val page = request(server.code, readBody = true)
            assertEquals(200, page.first)
            assertTrue(page.second.contains("fetch(location.pathname"))
            assertEquals(200, request(server.code, replacement).first)
            assertArrayEquals(replacement, picture.readBytes())
            val next = UploadServer(temp, imagesOnly = true) { _, _ -> null }
            try {
                assertNotEquals(server.code, next.code)
            } finally {
                next.stop()
            }
        } finally {
            server.stop()
            picture.delete()
            temp.delete()
        }
    }
}
