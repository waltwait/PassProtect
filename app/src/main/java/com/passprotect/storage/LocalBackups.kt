package com.passprotect.storage

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 一份認得出來的備份。每日備份的 [time] 是 null（它代表那天最後的樣子）。 */
data class BackupEntry(
    val name: String,
    val date: LocalDate,
    val time: LocalTime?,
    val beforeRestore: Boolean,
    val lastModified: Long
)

/**
 * 本地備份的規則（規格 2026-10-24-local-backup §3）：每天一份
 * `PassProtect-yyyy-MM-dd.kdbx`、還原前多存一份
 * `PassProtect-yyyy-MM-dd-HHmmss-before-restore.kdbx`，只保留最新 [KEEP] 份。
 * 資料夾裡不符合這兩種檔名的檔案一律不碰——資料夾可能就是同步資料夾，主檔也在裡面。
 *
 * `vaultFileName` 是目前資料庫檔的名字：換手機復原時可能誤選了一份備份當資料庫，
 * 它的名字就長得跟備份一樣。這個名字永遠不列進備份、也永遠不清掉。
 */
object LocalBackups {
    const val KEEP = 30

    private val Name = Regex("""PassProtect-(\d{4}-\d{2}-\d{2})(?:-(\d{6})-before-restore)?\.kdbx""")
    private val TimeFormat = DateTimeFormatter.ofPattern("HHmmss")

    fun dailyName(date: LocalDate): String = "PassProtect-$date.kdbx"

    fun beforeRestoreName(at: LocalDateTime): String =
        "PassProtect-${at.toLocalDate()}-${at.format(TimeFormat)}-before-restore.kdbx"

    fun parse(file: StoredFile): BackupEntry? {
        val match = Name.matchEntire(file.name) ?: return null
        return try {
            val date = LocalDate.parse(match.groupValues[1])
            val time = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it, TimeFormat) }
            BackupEntry(file.name, date, time, beforeRestore = time != null, lastModified = file.lastModified)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** 認得出來的備份，最新在前；同一天的每日備份排在那天的還原前備份前面。 */
    fun list(folder: BackupFolder, vaultFileName: String? = null): List<BackupEntry> =
        folder.list()
            .filter { it.name != vaultFileName }
            .mapNotNull(::parse)
            .sortedWith(compareByDescending<BackupEntry> { it.date }.thenByDescending { it.time ?: LocalTime.MAX })

    /**
     * 今天那份：[overwrite] 為 false 時已經有就不動（解鎖用），true 時覆蓋（存檔、立即備份用）。
     * 回傳這次有沒有真的寫入——只有寫入成功才算「備份恢復正常」。
     */
    fun backupDaily(
        folder: BackupFolder,
        bytes: ByteArray,
        today: LocalDate,
        overwrite: Boolean,
        vaultFileName: String? = null
    ): Boolean {
        val name = dailyName(today)
        if (!overwrite && folder.list().any { it.name == name }) return false
        folder.write(name, bytes)
        prune(folder, vaultFileName = vaultFileName)
        return true
    }

    fun backupBeforeRestore(folder: BackupFolder, bytes: ByteArray, at: LocalDateTime, vaultFileName: String? = null) {
        folder.write(beforeRestoreName(at), bytes)
        prune(folder, vaultFileName = vaultFileName)
    }

    fun prune(folder: BackupFolder, keep: Int = KEEP, vaultFileName: String? = null) {
        list(folder, vaultFileName).drop(keep).forEach { folder.delete(it.name) }
    }
}
