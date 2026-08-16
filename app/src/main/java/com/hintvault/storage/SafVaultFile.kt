package com.hintvault.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException

/**
 * 透過 SAF 存取資料庫檔，以「已授權的資料夾」為單位操作。
 *
 * 用 SAF 而非 app 私有目錄，是為了讓 Syncthing / Nextcloud 這類同步工具
 * 能碰到同一個檔。不申請 MANAGE_EXTERNAL_STORAGE——權限過大。
 *
 * **為何吃 [directory] 而非自己解析 URI**：`ACTION_OPEN_DOCUMENT`（單一檔案）
 * 建構出的 `DocumentFile.parentFile` 恆為 `null`（`androidx.documentfile:documentfile:1.0.1`
 * 之 `RawDocumentFile` 與 `SingleDocumentFile` 皆然，已用 `javap` 反編譯與真機
 * 雙重證實），因此完全無法建立同目錄的兄弟檔案——[writeSibling] 賴以運作的能力。
 * 呼叫端須改用 `ACTION_OPEN_DOCUMENT_TREE` 取得整個資料夾的授權，用
 * `DocumentFile.fromTreeUri()` 解析成目錄後傳入。此設計也讓測試能用
 * `DocumentFile.fromFile()`（一個普通目錄）走完全相同的程式碼路徑，
 * 不需真的 SAF 授權。
 */
class SafVaultFile(
    private val context: Context,
    private val directory: DocumentFile,
    private val fileName: String
) : VaultFile {

    private val document: DocumentFile
        get() = directory.findFile(fileName)
            ?: throw FileNotFoundException("在資料夾中找不到 $fileName")

    override val displayName: String
        get() = fileName

    override fun read(): ByteArray =
        context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("無法讀取 $fileName")

    /**
     * 以 "wt" 開啟。純 "w" 在部分 provider 上不會截斷檔案，
     * 新內容較短時會留下舊資料的尾巴，導致 KDBX 檔損毀。
     */
    override fun write(bytes: ByteArray) {
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $fileName")
    }

    override fun lastModified(): Long = document.lastModified()

    /**
     * 撞名時的行為不能信任底層 provider——實測 `RawDocumentFile.createFile()`
     * 完全沒有去重邏輯，撞名會直接靜默覆蓋既有內容（`File.createNewFile()` 的
     * 回傳值被忽略）。因此自己在建立前用 [uniqueNameFor] 保證拿到一個沒被
     * 用過的名字，不依賴 provider 的慣例行為。這正是 Task 7 review 釘住的
     * 合約——絕不覆蓋、回傳實際使用的檔名——所以放在這裡自己保證，而非信任
     * 上游。
     */
    override fun writeSibling(suffix: String, bytes: ByteArray): String {
        val name = uniqueNameFor(fileName + suffix)
        val created = directory.createFile(SIBLING_MIME_TYPE, name)
            ?: throw FileNotFoundException("無法在資料夾中建立 $name")
        context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $name")
        return created.name ?: name
    }

    /**
     * 直接重用 [writeSibling] 已驗證過的「絕不覆蓋既有檔案」邏輯所在的同一個
     * MIME type 常數，但語意不同：這裡撞名是**錯誤**而非該自動避開的情況，
     * 因此不呼叫 [uniqueNameFor]，而是直接檢查、直接拋例外。
     *
     * 「先檢查存不存在、再建立」不是原子操作，與 [writeSibling] 走的 [uniqueNameFor]
     * 是同一個既有的 TOCTOU 保留條款：SAF 沒有 create-if-absent 這種原子語意，
     * 外部同步工具若剛好卡在檢查與建立之間建出同名檔案，這裡擋不住。
     */
    override fun create(bytes: ByteArray) {
        if (directory.findFile(fileName) != null) {
            throw FileAlreadyExistsException(File(fileName))
        }
        val created = directory.createFile(SIBLING_MIME_TYPE, fileName)
            ?: throw FileNotFoundException("無法建立 $fileName")
        context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("無法寫入 $fileName")
    }

    /** 在目錄裡找一個還沒被用過的檔名，必要時在副檔名前插入遞增的數字，
     *  這樣撞名時第二個檔案仍以 .kdbx 結尾，KeePassXC 才認得出來。 */
    private fun uniqueNameFor(base: String): String {
        if (directory.findFile(base) == null) return base

        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""

        var i = 1
        while (directory.findFile("$stem.$i$ext") != null) i++
        return "$stem.$i$ext"
    }

    private companion object {
        /**
         * 用一個 `MimeTypeMap` 查不到對應副檔名的字串。實測
         * `"application/octet-stream"` 在部分裝置上會被自動加上 `.bin`，
         * 讓衝突檔案的檔名不再以 `.kdbx` 結尾，KeePassXC 認不出這是它的格式。
         */
        const val SIBLING_MIME_TYPE = "application/vnd.hintvault.kdbx"
    }
}

/** 持久化 SAF 授權，讓 app 重啟後仍能存取同一個資料夾。 */
object VaultUriPermissions {

    private const val FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun persist(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
    }

    /** 使用者可能在系統設定裡撤銷授權，或把資料夾刪掉。 */
    fun isStillGranted(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
}
