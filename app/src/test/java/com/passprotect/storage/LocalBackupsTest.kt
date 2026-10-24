package com.passprotect.storage

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 規格 §3：檔名、每天一份、保留最新 30 份、只動自己建的檔。 */
class LocalBackupsTest {

    @Test
    fun dailyNameUsesTheLocalDate() =
        assertEquals("PassProtect-2026-10-24.kdbx", LocalBackups.dailyName(LocalDate.of(2026, 10, 24)))

    @Test
    fun beforeRestoreNameCarriesTheTime() =
        assertEquals(
            "PassProtect-2026-10-24-153012-before-restore.kdbx",
            LocalBackups.beforeRestoreName(LocalDateTime.of(2026, 10, 24, 15, 30, 12))
        )

    @Test
    fun parsesOnlyItsOwnNames() {
        assertNotNull(LocalBackups.parse(StoredFile("PassProtect-2026-10-24.kdbx", 0)))
        assertEquals(
            true,
            LocalBackups.parse(StoredFile("PassProtect-2026-10-24-153012-before-restore.kdbx", 0))?.beforeRestore
        )
        listOf("PassProtect.kdbx", "PassProtect-2026-13-40.kdbx", "PassProtect-2026-10-24 (1).kdbx", "notes.txt")
            .forEach { assertNull(it, LocalBackups.parse(StoredFile(it, 0))) }
    }

    @Test
    fun dailyBackupIsWrittenOncePerDayUnlessOverwriting() {
        val folder = FakeBackupFolder()
        val day = LocalDate.of(2026, 10, 24)
        LocalBackups.backupDaily(folder, byteArrayOf(1), day, overwrite = false)
        LocalBackups.backupDaily(folder, byteArrayOf(2), day, overwrite = false)
        assertArrayEquals(byteArrayOf(1), folder.files["PassProtect-2026-10-24.kdbx"])
        LocalBackups.backupDaily(folder, byteArrayOf(3), day, overwrite = true)
        assertArrayEquals(byteArrayOf(3), folder.files["PassProtect-2026-10-24.kdbx"])
    }

    @Test
    fun keepsTheNewestThirty() {
        val folder = FakeBackupFolder()
        val start = LocalDate.of(2026, 9, 1)
        repeat(32) {
            LocalBackups.backupDaily(folder, byteArrayOf(it.toByte()), start.plusDays(it.toLong()), overwrite = true)
        }
        val names = LocalBackups.list(folder).map { it.name }
        assertEquals(30, names.size)
        assertFalse("PassProtect-2026-09-01.kdbx" in names)
        assertFalse("PassProtect-2026-09-02.kdbx" in names)
    }

    @Test
    fun foreignFilesAreNeverTouched() {
        val folder = FakeBackupFolder().apply {
            files["PassProtect.kdbx"] = byteArrayOf(9)
            files["keep.txt"] = byteArrayOf(8)
        }
        repeat(31) {
            LocalBackups.backupDaily(folder, byteArrayOf(1), LocalDate.of(2026, 9, 1).plusDays(it.toLong()), overwrite = true)
        }
        assertTrue("PassProtect.kdbx" in folder.files && "keep.txt" in folder.files)
    }

    @Test
    fun listsNewestFirstWithBeforeRestoreAfterThatDaysTime() {
        // 同一天：每日備份（time = null，視為當天最後）排在還原前備份之前
        val folder = FakeBackupFolder()
        LocalBackups.backupDaily(folder, byteArrayOf(1), LocalDate.of(2026, 10, 23), overwrite = true)
        LocalBackups.backupBeforeRestore(folder, byteArrayOf(2), LocalDateTime.of(2026, 10, 24, 9, 0, 0))
        LocalBackups.backupDaily(folder, byteArrayOf(3), LocalDate.of(2026, 10, 24), overwrite = true)
        assertEquals(
            listOf(
                "PassProtect-2026-10-24.kdbx",
                "PassProtect-2026-10-24-090000-before-restore.kdbx",
                "PassProtect-2026-10-23.kdbx"
            ),
            LocalBackups.list(folder).map { it.name }
        )
    }

    @Test
    fun theVaultFileIsNeitherListedNorPruned() {
        val folder = FakeBackupFolder()
        val vault = "PassProtect-2026-09-01.kdbx"
        folder.files[vault] = byteArrayOf(9)
        repeat(31) {
            LocalBackups.backupDaily(
                folder, byteArrayOf(1), LocalDate.of(2026, 9, 2).plusDays(it.toLong()), overwrite = true, vaultFileName = vault
            )
        }
        assertTrue(vault in folder.files)
        assertFalse(LocalBackups.list(folder, vaultFileName = vault).any { it.name == vault })
        assertEquals(30, LocalBackups.list(folder, vaultFileName = vault).size)
    }

    @Test
    fun dailyBackupReportsWhetherItWrote() {
        val folder = FakeBackupFolder()
        val day = LocalDate.of(2026, 10, 24)
        assertTrue(LocalBackups.backupDaily(folder, byteArrayOf(1), day, overwrite = false))
        assertFalse(LocalBackups.backupDaily(folder, byteArrayOf(2), day, overwrite = false))
    }
}
