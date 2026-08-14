package com.hintvault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStampTest {

    @Test
    fun identicalBytesAndTimeAreEqual() {
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("hello".toByteArray(), 1000L)
        assertEquals(a, b)
        assertFalse(a.differsFrom(b))
    }

    @Test
    fun differentContentDiffers() {
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("world".toByteArray(), 1000L)
        assertTrue(a.differsFrom(b))
    }

    @Test
    fun differentMtimeDiffersEvenWithSameContent() {
        // 同步工具可能重寫出位元組相同但時間不同的檔案。
        // 保守起見視為已變動——寧可多問一次，也不要覆蓋別人的改動。
        val a = FileStamp.of("hello".toByteArray(), 1000L)
        val b = FileStamp.of("hello".toByteArray(), 2000L)
        assertTrue(a.differsFrom(b))
    }

    @Test
    fun sha256IsLowercaseHexOf64Chars() {
        val stamp = FileStamp.of("hello".toByteArray(), 0L)
        assertEquals(64, stamp.sha256.length)
        assertTrue(stamp.sha256.all { it in "0123456789abcdef" })
    }

    @Test
    fun sha256MatchesKnownVector() {
        // echo -n "abc" | shasum -a 256
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileStamp.of("abc".toByteArray(), 0L).sha256
        )
    }

    @Test
    fun emptyContentIsStampable() {
        assertEquals(64, FileStamp.of(ByteArray(0), 0L).sha256.length)
    }
}
