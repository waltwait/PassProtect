package com.passprotect.storage

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
     *
     * 若檔案在載入後被外部刪除，`file.read()` 會直接擲出例外——這種情況下
     * 不會走到衝突分支、不會寫出 `*.conflict-*.kdbx`，本地版本目前沒有
     * 被保留。呼叫端遇到例外時應自行把 [bytes] 存到別處，不能假設這個
     * 函式在任何失敗路徑下都會保留本地版本。
     */
    fun save(bytes: ByteArray, loadedStamp: FileStamp): SaveResult {
        val current = FileStamp.of(file.read(), file.lastModified())
        if (current.differsFrom(loadedStamp)) {
            val name = file.writeSibling(conflictSuffix(), bytes)
            return SaveResult.Conflict(name)
        }

        // 備份必須在寫入之前。寫到一半失敗時，這是唯一的救援路徑。
        //
        // 這裡刻意「重新讀一次」而不是複用上面 current 比對時已經讀過的
        // 位元組：如果外部改動剛好卡在比對之後、寫入之前這個極窄的窗口，
        // 這次重讀會把外部的新內容存進備份——救得回來；若改成複用舊的
        // current，備份存的會是已經不存在的舊內容，外部改動就真的丟了。
        // 這個重讀是刻意的緩解手段，不是能刪掉的重複程式碼。
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
