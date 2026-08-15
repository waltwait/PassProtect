package com.hintvault.storage

/**
 * 開 app 時，在要求密碼之前先確認資料庫檔案本身是否健全。
 *
 * 只檢查 KDBX 檔頭簽章（前 8 bytes），不做完整格式解析——完整解析需要密碼
 * 才能驗證後續內容，那是 `Vault.open()` 的責任（見 `UnsupportedVaultFormatException`）。
 * 這裡只回答一個更基本的問題：這個檔案存不存在、開得起來、前 8 bytes 像不像 KDBX。
 */
class VaultHealthCheck(
    private val file: VaultFile,
    private val backups: BackupStore
) {

    sealed interface Result {
        data object Healthy : Result
        data class RecoverableFromBackup(val backupBytes: ByteArray) : Result {
            // ByteArray 在 data class 中是參考相等，需自行實作
            override fun equals(other: Any?): Boolean =
                other is RecoverableFromBackup && backupBytes.contentEquals(other.backupBytes)
            override fun hashCode(): Int = backupBytes.contentHashCode()
        }
        data object Unrecoverable : Result
    }

    fun check(): Result {
        val bytes = try {
            file.read()
        } catch (_: Exception) {
            return recoverableOrUnrecoverable()
        }
        return if (bytes.hasKdbxSignature()) Result.Healthy else recoverableOrUnrecoverable()
    }

    /**
     * 使用者確認還原後呼叫。先試著蓋掉既有檔案；若檔案根本不存在（`write()`
     * 會擲例外），退回 `create()`——重用 Task 3 剛加的「絕不覆蓋既有檔案」
     * 建檔邏輯，這裡走到這一步時確實不存在既有檔案，語意上不衝突。
     */
    fun restoreFromBackup(backupBytes: ByteArray) {
        try {
            file.write(backupBytes)
        } catch (_: Exception) {
            file.create(backupBytes)
        }
    }

    private fun recoverableOrUnrecoverable(): Result {
        val backup = backups.get()
        return if (backup != null && backup.hasKdbxSignature()) {
            Result.RecoverableFromBackup(backup)
        } else {
            Result.Unrecoverable
        }
    }

    private fun ByteArray.hasKdbxSignature(): Boolean =
        size >= KDBX_SIGNATURE.size && KDBX_SIGNATURE.indices.all { this[it] == KDBX_SIGNATURE[it] }

    private companion object {
        val KDBX_SIGNATURE = byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
        )
    }
}
