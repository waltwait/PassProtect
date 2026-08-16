package com.passprotect.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 驗證「包裝 → 存檔 → 讀回 → 解包」這條路徑本身是正確的。
 *
 * 這裡刻意使用**不綁定生體辨識**的 Keystore 金鑰，因為 instrumented 測試
 * 無法通過 BiometricPrompt。生體辨識綁定的完整流程於 Task 12 手動驗證。
 */
@RunWith(AndroidJUnit4::class)
class WrappedKeyRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "hv_test_unbound_key"

    private fun unboundKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    @Test
    fun vaultKeySurvivesWrapStoreReadUnwrap() {
        val vaultKey = ByteArray(32) { it.toByte() }
        val store = WrappedKeyStore(File(context.filesDir, "roundtrip-test"))
        store.clear()

        val key = unboundKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        store.put(WrappedKey(ciphertext = encrypt.doFinal(vaultKey), iv = encrypt.iv))

        val wrapped = store.get()!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv))
        }

        assertArrayEquals(vaultKey, decrypt.doFinal(wrapped.ciphertext))
        store.clear()
    }

    @Test
    fun tamperedCiphertextIsRejectedByGcmTag() {
        val store = WrappedKeyStore(File(context.filesDir, "tamper-test"))
        store.clear()

        val key = unboundKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = encrypt.doFinal(ByteArray(32))
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        store.put(WrappedKey(ciphertext, encrypt.iv))

        val wrapped = store.get()!!
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv))
        }

        var rejected = false
        try {
            decrypt.doFinal(wrapped.ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            rejected = true
        }
        assertTrue("GCM 必須拒絕被竄改的密文", rejected)
        store.clear()
    }
}
