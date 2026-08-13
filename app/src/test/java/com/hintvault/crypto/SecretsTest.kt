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
        assertArrayEquals(charArrayOf('\u0000', '\u0000', '\u0000'), chars)
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

    @Test
    fun toUtf8BytesHandlesSurrogatePairs() {
        // 代理對：2 chars → 4 bytes。size*3 的配置必須足夠。
        val chars = "🔐".toCharArray()
        assertArrayEquals("🔐".toByteArray(Charsets.UTF_8), chars.toUtf8Bytes())
    }

    @Test
    fun toUtf8BytesHandlesLongCjkInputWithoutTruncation() {
        // 舊實作在此長度會反覆擴充緩衝區；新實作單次完成。
        val text = "提示".repeat(64)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), text.toCharArray().toUtf8Bytes())
    }
}
