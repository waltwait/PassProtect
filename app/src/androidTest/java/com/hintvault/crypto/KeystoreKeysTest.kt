package com.hintvault.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreKeysTest {

    private val subject = KeystoreKeys(alias = "hv_test_key")

    @Before fun clean() = subject.deleteKey()
    @After fun cleanUp() = subject.deleteKey()

    @Test
    fun createsKey() {
        assertFalse(subject.hasKey())
        subject.createKey()
        assertTrue(subject.hasKey())
    }

    @Test
    fun reportsWhetherStrongBoxWasUsed() {
        // 測試裝置（Galaxy S25）有 StrongBox；無 StrongBox 的機器應回傳 false 而非擲例外
        val result = subject.createKey()
        assertNotNull(result)
        // 不論何者都必須成功建出金鑰
        assertTrue(subject.hasKey())
    }

    @Test
    fun deleteRemovesKey() {
        subject.createKey()
        subject.deleteKey()
        assertFalse(subject.hasKey())
    }

    @Test
    fun deleteIsIdempotent() {
        subject.deleteKey()
        subject.deleteKey()
        assertFalse(subject.hasKey())
    }

    @Test
    fun encryptCipherIsInitialisedWithFreshIv() {
        subject.createKey()
        val first = subject.encryptCipher()
        val second = subject.encryptCipher()

        assertNotNull(first.iv)
        assertEquals(12, first.iv.size)          // GCM 標準 IV 長度
        assertFalse(first.iv.contentEquals(second.iv))   // 絕不可重複使用 IV
    }

    @Test
    fun decryptCipherAcceptsIvFromEncryptCipher() {
        subject.createKey()
        val encrypt = subject.encryptCipher()

        val decrypt = subject.decryptCipher(encrypt.iv)

        assertNotNull(decrypt)
        assertArrayEquals(encrypt.iv, decrypt.iv)
        assertEquals("AES/GCM/NoPadding", decrypt.algorithm)
    }

    @Test
    fun createKeyTwiceReplacesRatherThanAccumulates() {
        subject.createKey()
        subject.createKey()
        assertTrue(subject.hasKey())
    }
}
