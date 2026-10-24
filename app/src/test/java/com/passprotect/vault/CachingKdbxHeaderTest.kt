package com.passprotect.vault

import com.passprotect.crypto.KdfParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.linguafranca.pwdb.security.Argon2

/** 開檔／存檔途中同一把金鑰只跑一次 Argon2，結束時記住的結果要清掉。 */
class CachingKdbxHeaderTest {

    private fun header() = CachingKdbxHeader(4).apply {
        keyDerivationFunction = Argon2.getInstance()
        kdfParameters.putULong("M", KdfParams.FLOOR.memoryBytes)
        kdfParameters.putULong("I", KdfParams.FLOOR.iterations)
        kdfParameters.putUInt("P", KdfParams.FLOOR.parallelism)
    }

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun repeatedCallsDeriveOnce() {
        val header = header()
        val first = header.getTransformedKeyDigest(key)
        val second = header.getTransformedKeyDigest(key)
        assertArrayEquals(first, second)
        assertEquals(1, header.derivations)
    }

    @Test
    fun anotherKeyDerivesAgain() {
        val header = header()
        header.getTransformedKeyDigest(key)
        header.getTransformedKeyDigest(ByteArray(32) { 7 })
        assertEquals(2, header.derivations)
    }

    @Test
    fun wipeForgetsTheResult() {
        val header = header()
        val first = header.getTransformedKeyDigest(key)
        header.wipe()
        val again = header.getTransformedKeyDigest(key)
        assertArrayEquals(first, again)
        assertEquals(2, header.derivations)
    }

    @Test
    fun callersCannotCorruptTheCachedResult() {
        val header = header()
        header.getTransformedKeyDigest(key).fill(0)
        assertFalse(header.getTransformedKeyDigest(key).all { it == 0.toByte() })
    }
}
