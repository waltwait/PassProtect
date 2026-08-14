package com.hintvault.storage

import java.security.MessageDigest

/**
 * 資料庫檔在某一時刻的身分。
 *
 * 載入時記錄，存檔前重新比對。不一致就代表外部（同步工具、桌面版 KeePassXC）
 * 動過這個檔，此時絕不覆寫。
 */
data class FileStamp(val lastModified: Long, val sha256: String) {

    /**
     * 內容或時間任一不同即視為已變動。
     *
     * 時間不同但內容相同也算變動：同步工具重寫檔案時可能產生這種情況，
     * 而在「絕不覆蓋他人改動」這個目標下，多問一次的代價遠低於漏判。
     */
    fun differsFrom(other: FileStamp): Boolean = this != other

    companion object {
        fun of(bytes: ByteArray, lastModified: Long): FileStamp =
            FileStamp(
                lastModified = lastModified,
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            )
    }
}
