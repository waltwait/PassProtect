package com.passprotect.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WrappedKeyStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun subject() = WrappedKeyStore(folder.root)

    @Test
    fun startsEmpty() {
        assertFalse(subject().exists())
        assertNull(subject().get())
    }

    @Test
    fun roundTripsCiphertextAndIv() {
        val key = WrappedKey(ciphertext = byteArrayOf(1, 2, 3, 4), iv = ByteArray(12) { it.toByte() })
        subject().put(key)

        val got = subject().get()!!
        assertArrayEquals(key.ciphertext, got.ciphertext)
        assertArrayEquals(key.iv, got.iv)
    }

    @Test
    fun existsBecomesTrueAfterPut() {
        subject().put(WrappedKey(byteArrayOf(9), ByteArray(12)))
        assertTrue(subject().exists())
    }

    @Test
    fun clearRemovesStoredKey() {
        val store = subject()
        store.put(WrappedKey(byteArrayOf(9), ByteArray(12)))
        store.clear()

        assertFalse(store.exists())
        assertNull(store.get())
    }

    @Test
    fun clearIsIdempotent() {
        subject().clear()
        subject().clear()
        assertFalse(subject().exists())
    }

    @Test
    fun putOverwritesPreviousKey() {
        val store = subject()
        store.put(WrappedKey(byteArrayOf(1), ByteArray(12) { 1 }))
        store.put(WrappedKey(byteArrayOf(2), ByteArray(12) { 2 }))

        assertArrayEquals(byteArrayOf(2), store.get()!!.ciphertext)
    }

    @Test
    fun getReturnsNullOnTruncatedFile() {
        // 檔案損毀時必須回傳 null 而非擲例外——呼叫端會回退主密碼
        folder.newFile("wrapped.key").writeBytes(byteArrayOf(1, 2))
        assertNull(WrappedKeyStore(folder.root).get())
    }
}
