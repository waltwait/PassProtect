package com.hintvault.storage

import java.time.Instant

data class LoadedVault(val bytes: ByteArray, val stamp: FileStamp) {
    // ByteArray 在 data class 中是參考相等，需自行實作
    override fun equals(other: Any?): Boolean =
        other is LoadedVault && bytes.contentEquals(other.bytes) && stamp == other.stamp
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + stamp.hashCode()
}

sealed interface SaveResult {
    /** 已寫入。[newStamp] 供下一次存檔比對使用。 */
    data class Saved(val newStamp: FileStamp) : SaveResult
    /** 未寫入，因為檔案被外部改過。本地版本已另存為 [conflictFileName]。 */
    data class Conflict(val conflictFileName: String) : SaveResult
}

/**
 * 存取資料庫檔，並保證一件事：**絕不覆蓋外部的改動**。
 *
 * 不做自動合併。合併錯誤會靜默丟資料，那是此類資料最糟的失敗模式；
 * 交給桌面版 KeePassXC 在大螢幕上處理遠比在手機上自動決定安全。
 */
class VaultRepository(
    private val file: VaultFile,
    private val backups: BackupStore
) {

    fun load(): LoadedVault {
        val bytes = file.read()
        return LoadedVault(bytes, FileStamp.of(bytes, file.lastModified()))
    }

    /**
     * @param loadedStamp 上次 [load]（或上次成功 [save]）時的指紋。
     */
    fun save(bytes: ByteArray, loadedStamp: FileStamp): SaveResult {
        val current = FileStamp.of(file.read(), file.lastModified())
        if (current.differsFrom(loadedStamp)) {
            val name = file.writeSibling(conflictSuffix(), bytes)
            return SaveResult.Conflict(name)
        }

        // 備份必須在寫入之前。寫到一半失敗時，這是唯一的救援路徑。
        backups.put(file.read())
        file.write(bytes)
        return SaveResult.Saved(FileStamp.of(bytes, file.lastModified()))
    }

    private fun conflictSuffix(): String {
        val timestamp = Instant.now().toString()
            .replace(':', '-')
            .substringBefore('.')
        return ".conflict-$timestamp.kdbx"
    }
}
