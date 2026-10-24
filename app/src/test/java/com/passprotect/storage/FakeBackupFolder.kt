package com.passprotect.storage

/** JVM 測試用的備份資料夾：檔案放在記憶體裡，最後修改時間用寫入順序遞增。 */
class FakeBackupFolder : BackupFolder {
    val files = linkedMapOf<String, ByteArray>()
    private val modified = mutableMapOf<String, Long>()
    private var clock = 0L

    /** 模擬資料夾授權被撤銷、空間不足之類的寫入失敗。 */
    var failWrites = false
    /** 模擬讀不到資料夾內容。 */
    var failList = false
    /** 讀檔時順便做的事，例如模擬還原途中被上鎖。 */
    var onRead: () -> Unit = {}
    /** 寫完一個檔之後順便做的事（拿到檔名）。 */
    var onWrite: (String) -> Unit = {}

    override val displayName = "PassProtect 備份"

    override fun list(): List<StoredFile> {
        if (failList) throw java.io.IOException("讀不到資料夾")
        return files.keys.map { StoredFile(it, modified[it] ?: 0L) }
    }

    override fun read(name: String): ByteArray {
        onRead()
        return files[name] ?: throw java.io.FileNotFoundException(name)
    }

    override fun write(name: String, bytes: ByteArray) {
        if (failWrites) throw java.io.IOException("寫不進去")
        files[name] = bytes
        modified[name] = ++clock
        onWrite(name)
    }

    override fun delete(name: String) {
        files.remove(name)
        modified.remove(name)
    }
}
