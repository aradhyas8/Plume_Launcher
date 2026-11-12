package dev.plume

import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/** One user-initiated connection to the TV's own legacy ADB daemon. */
internal object LocalAdb {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val WRTE = 0x45545257
    private const val CLSE = 0x45534c43
    private const val MAX_DATA = 4096
    private val sha1DigestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    fun grantHomePermission(socket: Socket) {
        // shortcut: only legacy port 5555, add TLS pairing when the pairing code can stay off the LAN.
        socket.connect(InetSocketAddress("127.0.0.1", 5555), 2_000)
        grantOnConnectedSocket(socket)
    }

    internal fun grantOnConnectedSocket(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()) {
            val packet = ByteBuffer.allocate(24 + data.size).order(LITTLE_ENDIAN)
            packet.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            packet.putInt(checksum(data)).putInt(command.inv()).put(data)
            output.write(packet.array())
        }
        fun read(): Packet {
            val header = ByteArray(24).also(input::readFully)
            val bytes = ByteBuffer.wrap(header).order(LITTLE_ENDIAN)
            val command = bytes.int
            val arg0 = bytes.int
            val arg1 = bytes.int
            val size = bytes.int
            val sum = bytes.int
            if (size !in 0..MAX_DATA || bytes.int != command.inv()) throw IOException("Invalid ADB header")
            val data = ByteArray(size).also(input::readFully)
            if (checksum(data) != sum) throw IOException("Invalid ADB checksum")
            return Packet(command, arg0, arg1, data)
        }

        socket.soTimeout = 5_000
        send(CNXN, 0x01000000, MAX_DATA, "host::\u0000".toByteArray())
        var reply = read()
        if (reply.command == AUTH) {
            val token = authToken(reply)
            // The permission survives disconnect. An ephemeral key avoids leaving a reusable private key on the TV.
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val signature = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key.private) }
            send(AUTH, 2, 0, signature.doFinal(sha1DigestInfo + token))
            reply = read()
            if (reply.command == AUTH) {
                authToken(reply)
                val publicKey = Base64.getEncoder().encodeToString(adbPublicKey(key.public as RSAPublicKey))
                send(AUTH, 3, 0, "$publicKey Plume@TV\u0000".toByteArray())
                socket.soTimeout = 60_000
                reply = read()
            }
        }
        if (reply.command != CNXN) throw IOException("Local ADB authorization failed")

        socket.soTimeout = 5_000
        send(OPEN, 1, 0, "shell:pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS\u0000".toByteArray())
        val opened = read()
        if (opened.command != OKAY || opened.arg1 != 1 || opened.arg0 == 0) throw IOException("ADB shell refused")
        repeat(16) {
            val packet = read()
            if (packet.arg0 != opened.arg0 || packet.arg1 != 1) throw IOException("Invalid ADB stream")
            when (packet.command) {
                WRTE -> send(OKAY, 1, opened.arg0)
                CLSE -> {
                    send(CLSE, 1, opened.arg0)
                    return // The caller checks Android's actual permission state.
                }
                else -> throw IOException("Invalid ADB stream command")
            }
        }
        throw IOException("ADB shell output exceeded limit")
    }

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun checksum(data: ByteArray) = data.sumOf { it.toInt() and 0xff }

    private fun authToken(packet: Packet): ByteArray {
        if (packet.arg0 != 1 || packet.data.size != 20) throw IOException("Invalid ADB challenge")
        return packet.data
    }

    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val modulus = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val inverse = modulus.mod(word).modInverse(word).negate().toInt()
        val square = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        val bytes = ByteBuffer.allocate(524).order(LITTLE_ENDIAN).putInt(64).putInt(inverse)
        for (value in listOf(modulus, square)) {
            val bigEndian = value.toByteArray()
            repeat(256) { bytes.put(bigEndian.getOrElse(bigEndian.lastIndex - it) { 0 }) }
        }
        return bytes.putInt(key.publicExponent.toInt()).array()
    }
}
