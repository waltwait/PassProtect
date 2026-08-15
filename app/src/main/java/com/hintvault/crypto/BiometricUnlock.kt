package com.hintvault.crypto

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.crypto.AEADBadTagException
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

    /**
     * 首次以主密碼解鎖成功後呼叫，把 vault key 包起來存下。
     *
     * 先清掉舊的包裝檔，再呼叫 [KeystoreKeys.createKey]——後者內部會刪除舊的
     * Keystore 金鑰。若不先清store，一旦使用者在驗證那一步取消，磁碟上會留下
     * 一個用「已經不存在的金鑰」加密出來的檔案：格式完好、`store.get()` 讀
     * 得到，下一次 [unlock] 卻會在使用者按完指紋後才炸出 `AEADBadTagException`。
     * 先清空是安全的方向——最壞情況只是使用者被迫多輸入一次主密碼。
     *
     * [vaultKey] 的所有權留在呼叫端——本函式讀取它加密後就不再持有，
     * 不會清空呼叫端的陣列。呼叫端用完後必須自己 wipe。
     */
    suspend fun enroll(vaultKey: ByteArray): Boolean {
        store.clear()
        keys.createKey()
        val cipher = keys.encryptCipher()
        val authenticated = authenticate(cipher, "啟用生體辨識解鎖") ?: return false
        store.put(WrappedKey(ciphertext = authenticated.doFinal(vaultKey), iv = authenticated.iv))
        return true
    }

    /**
     * @return 解包後的 vault key；使用者取消、驗證失敗、或密文本身已損毀時為 null。
     * @throws KeyPermanentlyInvalidException 金鑰已因生體特徵變更而作廢，須回退主密碼。
     *
     * 回傳的陣列是新配置的（來自 `Cipher.doFinal`），呼叫端自此擁有它的
     * 生命週期，必須負責清空——通常是直接交給 `VaultSession.unlock()`，
     * 它會在下一次解鎖或上鎖時清空。
     */
    suspend fun unlock(): ByteArray? {
        val wrapped = store.get() ?: return null
        val cipher = keys.decryptCipher(wrapped.iv)
        val authenticated = authenticate(cipher, "解鎖保險庫") ?: return null
        return try {
            authenticated.doFinal(wrapped.ciphertext)
        } catch (_: AEADBadTagException) {
            // 密文本身壞了（不是檔案格式壞了，那個 WrappedKeyStore.get() 已經擋掉）。
            // 清掉這個註定失敗的檔案，下次直接走主密碼，而不是每次都在使用者
            // 按完指紋、以為要成功了之後才失敗。
            store.clear()
            null
        }
    }

    /**
     * 生體辨識的 UI（BiometricPrompt）只能在主執行緒操作，強制切過去，
     * 呼叫端不需要自己記得。
     */
    private suspend fun authenticate(cipher: Cipher, title: String): Cipher? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                        }
                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
                // coroutine 被取消（例如 Activity 正在結束）時，畫面上的生體辨識
                // 提示不會自己消失——沒有這行，prompt 會留在螢幕上但沒有人接得住
                // 它的結果。
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
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
}
