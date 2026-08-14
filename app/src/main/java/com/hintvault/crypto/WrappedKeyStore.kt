package com.hintvault.crypto

import java.io.File

/** 被 Keystore 金鑰包起來的 vault key。[iv] 是解包時必須的 GCM IV。 */
class WrappedKey(val ciphertext: ByteArray, val iv: ByteArray)

/**
 * 存放被包裝的 vault key。
 *
 * 檔案格式：1 byte IV 長度 ‖ IV ‖ ciphertext
 *
 * 內容已被 Keystore 金鑰加密，且該金鑰綁定生體辨識，因此即使檔案外流，
 * 沒有通過裝置上的生體驗證也解不開。
 */
class WrappedKeyStore(private val directory: File) {

    private val file get() = File(directory, FILE_NAME)

    fun exists(): Boolean = file.exists()

    fun put(key: WrappedKey) {
        directory.mkdirs()
        file.writeBytes(
            byteArrayOf(key.iv.size.toByte()) + key.iv + key.ciphertext
        )
    }

    /** 檔案不存在或損毀時回傳 null，呼叫端應回退主密碼。 */
    fun get(): WrappedKey? {
        if (!file.exists()) return null
        val raw = file.readBytes()
        if (raw.isEmpty()) return null

        val ivSize = raw[0].toInt()
        if (ivSize <= 0 || raw.size <= 1 + ivSize) return null

        return WrappedKey(
            iv = raw.copyOfRange(1, 1 + ivSize),
            ciphertext = raw.copyOfRange(1 + ivSize, raw.size)
        )
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "wrapped.key"
    }
}
