package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TmdbKeyTransferTest {
    @Test fun localFormAcceptsOneSameOriginKey() {
        val address = InetAddress.getByName("127.0.0.1")
        val received = LinkedBlockingQueue<String>()
        val transfer = TmdbKeyTransfer(address) { received.add(it) }
        try {
            Qr.encode(transfer.url)
            val url = URI(transfer.url)
            fun request(method: String, headers: String = "", body: String = ""): String =
                Socket(address, url.port).use { phone ->
                    phone.soTimeout = 2000
                    phone.getOutputStream().write(("$method ${url.path} HTTP/1.1\r\nHost: ${url.authority}\r\n" +
                        headers + "\r\n" + body).encodeToByteArray())
                    phone.getInputStream().bufferedReader().readText()
                }

            val form = request("GET")
            assertTrue(form.contains("""<form method="post">"""))
            assertTrue(form.contains("Referrer-Policy: same-origin\r\n"))
            val key = "0123456789abcdef0123456789abcdef"
            val formHeaders = "Content-Length: 36\r\nContent-Type: application/x-www-form-urlencoded\r\n"
            assertTrue(request("POST", formHeaders + "Origin: http://other.test\r\n", "key=$key").startsWith("HTTP/1.1 400"))
            assertNull(received.poll())
            val response = request("POST", formHeaders + "Origin: http://${url.authority}\r\n", "key=$key")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("<h1>Key sent</h1>"))
            assertTrue(key !in response)
            assertEquals(key, received.poll(2, TimeUnit.SECONDS))
            try {
                Socket(address, url.port).use { throw AssertionError("The receiver still accepts connections") }
            } catch (_: java.net.ConnectException) {
                // The receiver closed before delivering the key.
            }
        } finally {
            transfer.stop()
        }
    }
}
