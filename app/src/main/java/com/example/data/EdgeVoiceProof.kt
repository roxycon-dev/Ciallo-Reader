// SPDX-License-Identifier: LGPL-3.0-only
// Edge Read Aloud proof adapted from rany2/edge-tts (Copyright 2025– rany)
// and the workspace's Dart adaptation. See assets/licenses/edge-tts.txt.
package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Client proof for the Edge Read Aloud v2 wire format. */
internal object EdgeVoiceProof {
    const val CLIENT = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val VERSION = "2-143.0.3650.75"
    private val initial = intArrayOf(
        0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
        0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt(),
    )
    private val working = intArrayOf(
        0xfd9fbea2.toInt(), 0x91243ce7.toInt(), 0xbe499094.toInt(), 0xcd2e1e0a.toInt(),
        0x6c006f49.toInt(), 0x04bd35ab.toInt(), 0xfeb0a7e4.toInt(), 0x03effbd2.toInt(),
    )
    private val schedule = intArrayOf(
        0xeb24bf9e.toInt(), 0x1558691f.toInt(), 0xa0c09ee2.toInt(), 0x0cfa32ba.toInt(),
        0xa622ee8c.toInt(), 0xd1dd9491.toInt(), 0xc0dd25a0.toInt(), 0xf9e70ab6.toInt(),
        0xf0c31573.toInt(), 0x7058ce4e.toInt(), 0x7188e606.toInt(), 0x00e5ab33.toInt(),
        0xd258b060.toInt(), 0x72766564.toInt(),
    )
    private val roundConstants = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    private val coefficients = intArrayOf(
        0xe82e91b1.toInt(), 0xb32700d0.toInt(), 0x44de24c6.toInt(), 0x88dff51d.toInt(),
        0x3beb65bc.toInt(), 0x29514038.toInt(), 0xc89c5933.toInt(), 0xb328cbdf.toInt(),
        0x8d1ec211.toInt(), 0xd405b4d5.toInt(), 0x314ef5d5.toInt(), 0x26e55f3c.toInt(),
        0xe92491b1.toInt(), 0x607801c6.toInt(), 0xf0a5b9d5.toInt(), 0x82b2c1fb.toInt(),
        0x06064cf3.toInt(), 0xfa51a3da.toInt(), 0xda79fa60.toInt(), 0x207cc464.toInt(),
        0xbed20560.toInt(), 0x3c3c74f3.toInt(), 0x89f9c9f8.toInt(), 0xd2d14b61.toInt(),
        0x35fa38c6.toInt(), 0xbb04a2c1.toInt(), 0xcb49e3c6.toInt(), 0xc5626167.toInt(),
    )

    fun value(now: Long): String {
        val seconds = now / 1000 + 11644473600L
        val window = seconds - seconds % 300
        val input = ((window * 10_000_000L).toString() + CLIENT).toByteArray(Charsets.US_ASCII)
        val digest = digest(input)
        val token = checksum(input, digest)
        return hex(token) + "-" + hex(digest)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") {
        ((it.toInt() and 255) + 256).toString(16).substring(1)
    }.uppercase(java.util.Locale.ROOT)

    private fun rotate(value: Int, bits: Int) = Integer.rotateRight(value, bits)
    private fun rotate16(value: Int) = Integer.rotateLeft(value, 16)
    private fun sigma0(value: Int) = rotate(value, 7) xor rotate(value, 18) xor (value ushr 3)
    private fun sigma1(value: Int) = rotate(value, 17) xor rotate(value, 19) xor (value ushr 10)

    private fun expand(words: IntArray, start: Int) {
        for (i in start until words.size) {
            words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
        }
    }

    private fun compress(state: IntArray, words: IntArray, start: Int): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in start..63) {
            val constant = if (start == 14 && i == 14) 0xc9dc06a7.toInt() else roundConstants[i]
            val first = h + (rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)) +
                ((e and f) xor (e.inv() and g)) + constant + words[i]
            val second = (rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + second
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(input: ByteArray): ByteArray {
        val words = IntArray(64)
        val prefix = ByteBuffer.wrap(input)
        words[14] = prefix.int ushr 8
        words[15] = prefix.getInt(3)
        for (i in 16..29) {
            val upper = sigma1(words[i - 2]) xor if (i == 16) 0x000b92c0 else 0
            val lower = sigma0(words[i - 15]) xor if (i == 29) 0x059c0b80 else 0
            words[i] = schedule[i - 16] + upper + words[i - 7] + lower
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            0xc9dc06a7.toInt() - roundConstants[14]
        expand(words, 31)
        val head = compress(working, words, 14)
        val state = IntArray(8) { initial[it] + head[it] }
        val messageLength = 57 + input.size
        val length = input.size - 7
        val tail = ByteBuffer.allocate(length + 1 + Math.floorMod(55 - messageLength, 64) + 8)
        tail.put(input, 7, length).put(0x80.toByte()).putLong(tail.capacity() - 8, messageLength * 8L)
        tail.rewind()
        while (tail.hasRemaining()) {
            repeat(16) { words[it] = tail.int }
            expand(words, 16)
            val result = compress(state, words, 0)
            for (i in state.indices) state[i] += result[i]
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun checksum(input: ByteArray, digest: ByteArray): ByteArray {
        val digestWords = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keys = intArrayOf(digestWords.int or 1, digestWords.int or 1)
        val factors = IntArray(2) { keys[it] * 0x82893933.toInt() }
        val terms = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()
        for (i in 0 until coefficients.size / 2) {
            terms.add(coefficients[i * 2] * factors[i % 2])
            offsets.add(coefficients[i * 2 + 1])
        }
        val tail = ByteBuffer.allocate(((input.size + 8) / 8) * 8).order(ByteOrder.LITTLE_ENDIAN)
        tail.put(0.toByte()).put(input).rewind()
        var index = 0
        while (tail.hasRemaining()) {
            val word = tail.int
            val even = index % 2 == 0
            var term = word * keys[index % 2]
            var offset = rotate16(word) * if (even) 0xef0569fb.toInt() else 0xc31713db.toInt()
            if (index == 0) {
                term += 0xf2f90b1a.toInt() * factors[0]
                offset += 0x0b1a0000
            }
            terms.add(term)
            offsets.add(offset)
            index++
        }
        var swappedTotal = 0
        var swappedLast = 0
        var state = 0
        var total = 0
        for (i in terms.indices step 2) {
            val x = terms[i]
            val y = terms[i + 1]
            val left = rotate16(offsets[i] + x) * 0x76f50f34
            val right = rotate16(offsets[i + 1] + y) * 0x1881ce3c
            swappedLast = rotate16(right) * 0x35bd1ec9 + right
            swappedTotal += rotate16(left) * 0xc3efea97.toInt() + left + swappedLast
            state = state * keys[0] + x
            state = rotate16(state) * 0xcf98b111.toInt()
            state = rotate16(state) * 0x87085b9f.toInt()
            state = rotate16(state) * 0x12ceb96d
            state = rotate16(state) * 0x257e1d83
            total += state
            state = state * keys[1] + y
            state = rotate16(state) * 0xa27416f5.toInt()
            state = rotate16(state) * 0xd38396ff.toInt()
            state = rotate16(state) * 0x7c932b89
            state = rotate16(state) * 0xbfa49f69.toInt()
            total += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swappedLast xor state).putInt(swappedTotal xor total).array()
    }
}
