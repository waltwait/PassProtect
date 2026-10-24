package com.passprotect.storage

import android.content.Context
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.IOException

/** 備份資料夾裡的一個檔案。[lastModified] 給設定頁顯示「最後一次備份時間」。 */
data class StoredFile(val name: String, val lastModified: Long)

/**
 * 使用者選的本地備份資料夾（規格 2026-10-24-local-backup §3）。只管位元組進出；
 * 檔名、保留幾份由 [LocalBackups] 決定。抽成介面是為了在 JVM 上用假實作測完整規則。
 */
interface BackupFolder {
    val displayName: String
    fun list(): List<StoredFile>
    fun read(name: String): ByteArray
    /** 已有同名檔就覆蓋，沒有才建立。 */
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/**
 * 透過 SAF 存取備份資料夾。
 *
 * [write] 一定先找既有檔再覆蓋：部分 provider 撞名時不覆蓋、而是自動改名成
 * `… (1).kdbx`，那樣同一天每存一次檔就多一個檔，也不會被 [LocalBackups] 認得。
 */
class SafBackupFolder(
    private val context: Context,
    private val directory: DocumentFile
) : BackupFolder {

    override val displayName: String
        get() = directory.name.orEmpty()

    /**
     * 一次查詢列出整個資料夾。不用 `DocumentFile.listFiles()`：它查詢失敗（授權被撤銷、
     * 資料夾被刪、SD 卡拔掉）時會吞掉例外、回傳空陣列，讀不到的資料夾就變成「沒有備份」，
     * 「備份失敗」也就顯示不出來；而且它每個檔案還要再各查兩次名稱、時間。
     */
    override fun list(): List<StoredFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory.uri,
            DocumentsContract.getDocumentId(directory.uri)
        )
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED)
        val cursor = context.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("讀不到備份資料夾")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    add(StoredFile(name, if (c.isNull(1)) 0L else c.getLong(1)))
                }
            }
        }
    }

    override fun read(name: String): ByteArray {
        val file = directory.findFile(name) ?: throw FileNotFoundException("找不到 $name")
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("無法讀取 $name")
    }

    override fun write(name: String, bytes: ByteArray) {
        val file = directory.findFile(name)
            ?: directory.createFile(MIME_TYPE, name)
            ?: throw FileNotFoundException("無法建立 $name")
        // "wt"：純 "w" 在部分 provider 不截斷，新內容較短時會留下舊資料的尾巴（同 SafVaultFile）。
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $name")
    }

    override fun delete(name: String) {
        directory.findFile(name)?.delete()
    }

    private companion object {
        /** 同 `SafVaultFile`：`application/octet-stream` 在部分裝置會被加上 `.bin`。 */
        const val MIME_TYPE = "application/vnd.passprotect.kdbx"
    }
}
