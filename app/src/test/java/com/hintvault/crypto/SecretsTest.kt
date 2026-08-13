package com.hintvault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretsTest {

    @Test
    fun wipeZeroesByteArray() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        bytes.wipe()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun wipeZeroesCharArray() {
        val chars = charArrayOf('a', 'b', 'c')
        chars.wipe()
        assertArrayEquals(charArrayOf(' ', ' ', ' '), chars)
    }

    @Test
    fun useThenWipeReturnsValueAndWipes() {
        val bytes = byteArrayOf(9, 9)
        val size = bytes.useThenWipe { it.size }
        assertEquals(2, size)
        assertArrayEquals(byteArrayOf(0, 0), bytes)
    }

    @Test
    fun useThenWipeWipesEvenOnThrow() {
        val bytes = byteArrayOf(7, 7)
        try {
            bytes.useThenWipe<Unit> { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(byteArrayOf(0, 0), bytes)
    }

    @Test
    fun toUtf8BytesEncodesMultibyteCorrectly() {
        val chars = "提示".toCharArray()
        assertArrayEquals("提示".toByteArray(Charsets.UTF_8), chars.toUtf8Bytes())
    }
}
