package com.passprotect.vault

import org.linguafranca.pwdb.kdbx.KdbxHeader

/**
 * KeePassJava2 開檔／存檔時，同一組憑證會呼叫 [getTransformedKeyDigest] 三次（驗證檔頭 HMAC、
 * HMAC 區塊串流、解密／加密串流），而它每次都重跑一次完整的 Argon2——解鎖跟存檔因此都是
 * KDF 時間的三倍。這裡把第一次的結果記住，同一把金鑰再要就直接給一份拷貝。
 *
 * 記住的是「轉換後的金鑰」，拿到它就能解開這個檔案，所以只在一次開檔或存檔的期間記住：
 * 呼叫端在結束時（成功或失敗都一樣）呼叫 [wipe]。
 */
internal class CachingKdbxHeader(version: Int) : KdbxHeader(version) {

    private var cachedKey: ByteArray? = null
    private var cachedDigest: ByteArray? = null

    /** 實際跑了幾次 KDF（測試用）。 */
    var derivations = 0
        private set

    override fun getTransformedKeyDigest(key: ByteArray): ByteArray {
        val digest = cachedDigest
        if (digest != null && key.contentEquals(cachedKey)) return digest.copyOf()
        wipe()
        derivations++
        return super.getTransformedKeyDigest(key).also {
            cachedKey = key.copyOf()
            cachedDigest = it.copyOf()
        }
    }

    fun wipe() {
        cachedKey?.fill(0)
        cachedDigest?.fill(0)
        cachedKey = null
        cachedDigest = null
    }
}
