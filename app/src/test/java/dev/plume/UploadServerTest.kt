package dev.plume

import java.io.File
import java.net.Socket
import java.util.Base64
import org.junit.Assert.assertArrayEquals
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
            fun request(code: String, data: ByteArray? = null): String =
                Socket("127.0.0.1", server.port).use { socket ->
                    socket.soTimeout = 5_000
                    val headers = if (data == null) "GET /$code HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        else "POST /$code HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nContent-Length: ${data.size}\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray())
                        if (data != null) write(data)
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
            assertTrue(request("wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request("wrong", replacement).startsWith("HTTP/1.1 404"))
            assertArrayEquals(before, picture.readBytes())
            val page = request(server.code)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("fetch(location.pathname"))
            assertTrue(request(server.code, replacement).startsWith("HTTP/1.1 200"))
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
