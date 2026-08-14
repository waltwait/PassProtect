package com.hintvault.crypto

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * 生體辨識快速解鎖。
 *
 * Cipher 透過 CryptoObject 交給 BiometricPrompt，因此金鑰的「使用」與該次
 * 驗證綁定，而不是「驗證通過後就能自由取用金鑰」。這個差別很重要：
 * 後者在驗證與使用之間留下了可乘之機。
 */
class BiometricUnlock(
    private val activity: FragmentActivity,
    private val keys: KeystoreKeys = KeystoreKeys(),
    private val store: WrappedKeyStore
) {

    private val executor: Executor get() = androidx.core.content.ContextCompat.getMainExecutor(activity)

    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun isAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** 首次以主密碼解鎖成功後呼叫，把 vault key 包起來存下。 */
    suspend fun enroll(vaultKey: ByteArray): Boolean {
        keys.createKey()
        val cipher = keys.encryptCipher()
        val authenticated = authenticate(cipher, "啟用生體辨識解鎖") ?: return false
        store.put(WrappedKey(ciphertext = authenticated.doFinal(vaultKey), iv = authenticated.iv))
        return true
    }

    /**
     * @return 解包後的 vault key；使用者取消時為 null。
     * @throws KeyPermanentlyInvalidException 金鑰已因生體特徵變更而作廢，須回退主密碼。
     */
    suspend fun unlock(): ByteArray? {
        val wrapped = store.get() ?: return null
        val cipher = keys.decryptCipher(wrapped.iv)
        val authenticated = authenticate(cipher, "解鎖保險庫") ?: return null
        return authenticated.doFinal(wrapped.ciphertext)
    }

    private suspend fun authenticate(cipher: Cipher, title: String): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        continuation.resume(result.cryptoObject?.cipher)
                    }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        continuation.resume(null)
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText("改用主密碼")
                    .setAllowedAuthenticators(authenticators)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        }
}
