package dev.plume

import kotlin.math.abs

/**
 * Minimal QR Code encoder, enough for the wallpaper-upload address without a library: byte mode,
 * error correction level M, versions 1–6 (up to 106 bytes), one alignment pattern at most.
 * Returns the modules (true = dark) without the quiet zone.
 */
internal object Qr {
    // Per version (index 0 = version 1), level M: data codewords, EC codewords per block, block count.
    private val DATA = intArrayOf(16, 28, 44, 64, 86, 108)
    private val EC = intArrayOf(10, 16, 26, 18, 24, 16)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4)

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Reed–Solomon error correction codewords for one block. */
    internal fun rs(data: IntArray, n: Int): IntArray {
        var gen = intArrayOf(1) // highest power first
        for (i in 0 until n) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor mul(gen[j], EXP[i])
            }
            gen = next
        }
        val rem = IntArray(n)
        for (d in data) {
            val f = d xor rem[0]
            System.arraycopy(rem, 1, rem, 0, n - 1)
            rem[n - 1] = 0
            for (j in 0 until n) rem[j] = rem[j] xor mul(gen[j + 1], f)
        }
        return rem
    }

    data class Symbol(val modules: Array<BooleanArray>, val fixed: Array<BooleanArray>)

    fun encode(text: String): Array<BooleanArray> = symbol(text).modules

    fun symbol(text: String): Symbol {
        val bytes = text.toByteArray()
        val v = DATA.indices.firstOrNull { bytes.size + 2 <= DATA[it] } ?: throw IllegalArgumentException("text too long for a QR code here")

        // Bit stream: byte mode, 8-bit length, data, terminator, then pad bytes.
        val bits = ArrayList<Boolean>()
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value shr i) and 1 == 1 }
        put(0b0100, 4)
        put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, DATA[v] * 8 - bits.size))
        while (bits.size % 8 != 0) bits += false
        val data = IntArray(DATA[v]) { i ->
            if (i < bits.size / 8) (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 }
            else if ((i - bits.size / 8) % 2 == 0) 0xEC else 0x11
        }

        // Split into blocks, add error correction, interleave.
        val per = DATA[v] / BLOCKS[v]
        val blocks = (0 until BLOCKS[v]).map { data.copyOfRange(it * per, (it + 1) * per) }
        val ecs = blocks.map { rs(it, EC[v]) }
        val stream = ArrayList<Int>()
        for (i in 0 until per) blocks.forEach { stream += it[i] }
        for (i in 0 until EC[v]) ecs.forEach { stream += it[i] }

        val size = 21 + 4 * v
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) } // function modules: data skips these
        fun set(r: Int, c: Int, dark: Boolean) { m[r][c] = dark; fn[r][c] = true }

        for ((r0, c0) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) { // finders with their separators
            for (r in -1..7) for (c in -1..7) {
                if (r0 + r !in 0 until size || c0 + c !in 0 until size) continue
                val ring = maxOf(abs(r - 3), abs(c - 3))
                set(r0 + r, c0 + c, ring != 2 && ring != 4)
            }
        }
        for (i in 8 until size - 8) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        if (v > 0) {
            val a = size - 7
            for (r in -2..2) for (c in -2..2) set(a + r, a + c, maxOf(abs(r), abs(c)) != 1)
        }

        // Format information (level M = 00, mask 0), both copies; this also reserves those modules.
        val fmtData = 0
        var rem = fmtData
        repeat(10) { rem = (rem shl 1) xor ((rem shr 9) * 0x537) }
        val fmt = ((fmtData shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (fmt shr i) and 1 == 1
        for (i in 0..5) set(i, 8, bit(i))
        set(7, 8, bit(6))
        set(8, 8, bit(7))
        set(8, 7, bit(8))
        for (i in 9..14) set(8, 14 - i, bit(i))
        for (i in 0..7) set(8, size - 1 - i, bit(i))
        for (i in 8..14) set(size - 15 + i, 8, bit(i))
        set(size - 8, 8, true) // the always-dark module

        // Data in two-column zigzags from the bottom right, skipping the timing column.
        // ponytail: fixed mask 0 instead of scoring all 8; scanners read any mask, it's only less robust in rare cases.
        var k = 0
        var col = size - 1
        var up = true
        while (col > 0) {
            if (col == 6) col--
            for (i in 0 until size) {
                val r = if (up) size - 1 - i else i
                for (c in intArrayOf(col, col - 1)) {
                    if (fn[r][c]) continue
                    val dark = k < stream.size * 8 && (stream[k / 8] shr (7 - k % 8)) and 1 == 1
                    k++
                    m[r][c] = dark xor ((r + c) % 2 == 0)
                }
            }
            up = !up
            col -= 2
        }
        return Symbol(m, fn)
    }
}
