package com.hintvault.storage

import java.io.File

/** 覆寫前的前一版密文。SAF 沒有可靠的 temp-then-rename，只能自己留一份。 */
interface BackupStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

/** 存於 app 私有目錄。內容是已加密的密文，因此不需額外保護。 */
class FileBackupStore(private val directory: File) : BackupStore {
    private val backupFile get() = File(directory, "previous.kdbx.bak")

    override fun put(bytes: ByteArray) {
        directory.mkdirs()
        backupFile.writeBytes(bytes)
    }

    override fun get(): ByteArray? =
        backupFile.takeIf { it.exists() }?.readBytes()
}
