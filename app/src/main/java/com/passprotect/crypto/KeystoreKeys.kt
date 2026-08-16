package com.passprotect.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore 金鑰已失效（通常是裝置新增了生體特徵），必須回退主密碼。 */
class KeyPermanentlyInvalidException(cause: Throwable) :
    Exception("Keystore 金鑰已失效，需以主密碼重新解鎖", cause)

data class KeyCreationResult(val strongBoxBacked: Boolean)

/**
 * 保護 vault key 的 Keystore 金鑰。
 *
 * 這把金鑰本身不是 vault key——它只用來包裝 vault key（§4.1）。
 * 主密碼永遠不進 Keystore，任何形式皆然。
 */
class KeystoreKeys(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "passprotect_wrapping_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = keyStore.containsAlias(alias)

    fun deleteKey() {
        keyStore.takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
    }

    /**
     * 建立生體辨識綁定的金鑰。優先使用 StrongBox，該硬體不支援時退回 TEE。
     */
    fun createKey(): KeyCreationResult {
        deleteKey()
        return try {
            generate(strongBox = true)
            KeyCreationResult(strongBoxBacked = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
            KeyCreationResult(strongBoxBacked = false)
        }
    }

    /**
     * `KeyPermanentlyInvalidatedException` 的實際拋出點是 `Cipher.init()`，
     * 不是 `KeyStore.getKey()`——後者宣告的受檢例外裡沒有 `InvalidKeyException`
     * 這一支，Keystore 只在真正要用這把金鑰做加解密運算時才會檢查它是否已
     * 因生體特徵變更而失效。catch 若包在 `secretKey()` 外面永遠不會觸發，
     * 平台的原始例外會直接往外傳、讓 app 崩潰，而不是優雅退回主密碼。
     */
    fun encryptCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            try {
                init(Cipher.ENCRYPT_MODE, secretKey())
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyPermanentlyInvalidException(e)
            }
        }

    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            try {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyPermanentlyInvalidException(e)
            }
        }

    private fun secretKey(): SecretKey = keyStore.getKey(alias, null) as SecretKey

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 有人在這台裝置新增指紋 → 這把金鑰立刻作廢。
            // 少了這一行，別人註冊自己的生體特徵就能解鎖這個保險庫。
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
