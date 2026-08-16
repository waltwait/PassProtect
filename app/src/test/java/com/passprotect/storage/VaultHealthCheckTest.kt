package com.passprotect.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class VaultHealthCheckTest {

    private val kdbxHeader = byteArrayOf(
        0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
        0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
    )

    private class FakeFile(
        private var exists: Boolean,
        private var content: ByteArray = ByteArray(0)
    ) : VaultFile {
        override val displayName = "vault.kdbx"
        var written: ByteArray? = null
        var created: ByteArray? = null

        override fun read(): ByteArray {
            if (!exists) throw FileNotFoundException("missing")
            return content
        }
        override fun write(bytes: ByteArray) {
            if (!exists) throw FileNotFoundException("missing")
            written = bytes
            content = bytes
        }
        override fun lastModified() = 1000L
        override fun writeSibling(suffix: String, bytes: ByteArray) = displayName + suffix
        override fun create(bytes: ByteArray) {
            created = bytes
            content = bytes
            exists = true
        }
    }

    private class FakeBackupStore(private val backup: ByteArray?) : BackupStore {
        override fun put(bytes: ByteArray) {}
        override fun get() = backup
    }

    @Test
    fun healthyFileReturnsHealthy() {
        val file = FakeFile(exists = true, content = kdbxHeader + "rest".toByteArray())
        val result = VaultHealthCheck(file, FakeBackupStore(null)).check()
        assertTrue(result is VaultHealthCheck.Result.Healthy)
    }

    @Test
    fun missingFileWithNoBackupReturnsUnrecoverable() {
        val file = FakeFile(exists = false)
        val result = VaultHealthCheck(file, FakeBackupStore(null)).check()
        assertTrue(result is VaultHealthCheck.Result.Unrecoverable)
    }

    @Test
    fun missingFileWithValidBackupReturnsRecoverableFromBackup() {
        val backup = kdbxHeader + "backup-content".toByteArray()
        val file = FakeFile(exists = false)
        val result = VaultHealthCheck(file, FakeBackupStore(backup)).check()

        assertTrue(result is VaultHealthCheck.Result.RecoverableFromBackup)
        assertArrayEquals(backup, (result as VaultHealthCheck.Result.RecoverableFromBackup).backupBytes)
    }

    @Test
    fun corruptHeaderWithValidBackupReturnsRecoverableFromBackup() {
        val backup = kdbxHeader + "backup-content".toByteArray()
        val file = FakeFile(exists = true, content = "not-a-kdbx-file".toByteArray())
        val result = VaultHealthCheck(file, FakeBackupStore(backup)).check()

        assertTrue(result is VaultHealthCheck.Result.RecoverableFromBackup)
    }

    @Test
    fun corruptHeaderWithNoUsableBackupReturnsUnrecoverable() {
        val file = FakeFile(exists = true, content = "not-a-kdbx-file".toByteArray())
        // 備份本身也壞掉的情況：不能假裝可以還原
        val result = VaultHealthCheck(file, FakeBackupStore("also-corrupt".toByteArray())).check()

        assertTrue(result is VaultHealthCheck.Result.Unrecoverable)
    }

    @Test
    fun restoreFromBackupWritesToExistingFile() {
        val file = FakeFile(exists = true, content = "corrupt".toByteArray())
        val backup = kdbxHeader + "restored".toByteArray()

        VaultHealthCheck(file, FakeBackupStore(backup)).restoreFromBackup(backup)

        assertArrayEquals(backup, file.written)
    }

    @Test
    fun restoreFromBackupCreatesFileWhenMissing() {
        val file = FakeFile(exists = false)
        val backup = kdbxHeader + "restored".toByteArray()

        VaultHealthCheck(file, FakeBackupStore(backup)).restoreFromBackup(backup)

        assertArrayEquals(backup, file.created)
    }
}
