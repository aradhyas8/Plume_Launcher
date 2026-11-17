package dev.plume

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbTest {
    @Test fun authorizesThenSendsOnlyThePlumeGrant() {
        val challenge = ByteArray(20) { (it + 1).toByte() }
        val peer = Peer(
            packet("AUTH", 1, 0, challenge) + packet("AUTH", 1, 0, challenge) +
                packet("CNXN", 0x01000000, 4096) + packet("OKAY", 8, 1) +
                packet("WRTE", 8, 1, "output".toByteArray()) + packet("CLSE", 8, 1)
        )

        LocalAdb.grantOnConnectedSocket(peer)

        val sent = ByteBuffer.wrap(peer.sent.toByteArray()).order(LITTLE_ENDIAN)
        val (connect, _, banner) = read(sent)
        val (sign, signType, signature) = read(sent)
        val (offer, offerType, publicKey) = read(sent)
        val (open, _, command) = read(sent)
        assertEquals("CNXN", connect)
        assertEquals("host::\u0000", banner.decodeToString())
        assertEquals("AUTH", sign)
        assertEquals(2, signType)
        assertEquals(256, signature.size)
        assertEquals("AUTH", offer)
        assertEquals(3, offerType)
        assertEquals(524, Base64.getDecoder().decode(publicKey.decodeToString().substringBefore(' ')).size)
        assertEquals("OPEN", open)
        assertEquals("shell:pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS\u0000", command.decodeToString())
        assertEquals("OKAY", read(sent).first)
        assertEquals("CLSE", read(sent).first)
        assertTrue(!sent.hasRemaining())
    }

    @Test fun rejectsAnInvalidChallengeBeforeOpeningAShell() {
        val peer = Peer(packet("AUTH", 1, 0, ByteArray(19)))
        assertTrue(runCatching { LocalAdb.grantOnConnectedSocket(peer) }.exceptionOrNull() is IOException)
        val sent = ByteBuffer.wrap(peer.sent.toByteArray()).order(LITTLE_ENDIAN)
        assertEquals("CNXN", read(sent).first)
        assertTrue(!sent.hasRemaining())
    }

    private class Peer(replies: ByteArray) : Socket() {
        private val input = ByteArrayInputStream(replies)
        val sent = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = sent
        override fun setSoTimeout(timeout: Int) = Unit
    }

    private fun packet(name: String, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val command = ByteBuffer.wrap(name.toByteArray()).order(LITTLE_ENDIAN).int
        return ByteBuffer.allocate(24 + data.size).order(LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv()).put(data).array()
    }

    private fun read(buffer: ByteBuffer): Triple<String, Int, ByteArray> {
        val command = ByteArray(4).also { buffer.get(it) }.decodeToString()
        val arg0 = buffer.int
        buffer.int
        val size = buffer.int
        buffer.int
        buffer.int
        return Triple(command, arg0, ByteArray(size).also { buffer.get(it) })
    }
}
