package com.hintvault.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRepositoryTest {

    /** 記憶體中的假檔案，模擬外部同步工具的干擾。 */
    private class FakeVaultFile(
        override val displayName: String = "vault.kdbx",
        var content: ByteArray = ByteArray(0),
        var mtime: Long = 1000L,
        var exists: Boolean = true
    ) : VaultFile {
        val siblings = mutableMapOf<String, ByteArray>()
        var writeShouldFail = false

        override fun read() = content
        override fun lastModified() = mtime
        override fun write(bytes: ByteArray) {
            if (writeShouldFail) throw java.io.IOException("simulated write failure")
            content = bytes
            mtime += 1
        }
        override fun writeSibling(suffix: String, bytes: ByteArray): String {
            val name = "$displayName$suffix"
            siblings[name] = bytes
            return name
        }
        override fun create(bytes: ByteArray) {
            if (exists) throw FileAlreadyExistsException(java.io.File(displayName))
            content = bytes
            exists = true
        }

        /** 模擬同步工具在背後換掉了檔案。 */
        fun externallyReplacedWith(bytes: ByteArray) {
            content = bytes
            mtime += 500
        }
    }

    private class FakeBackupStore : BackupStore {
        private var stored: ByteArray? = null
        override fun put(bytes: ByteArray) { stored = bytes }
        override fun get() = stored
    }

    @Test
    fun loadReturnsBytesAndStamp() {
        val file = FakeVaultFile(content = "v1".toByteArray(), mtime = 1234L)
        val loaded = VaultRepository(file, FakeBackupStore()).load()

        assertArrayEquals("v1".toByteArray(), loaded.bytes)
        assertEquals(FileStamp.of("v1".toByteArray(), 1234L), loaded.stamp)
    }

    @Test
    fun saveWritesWhenFileUnchanged() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        val result = repo.save("v2".toByteArray(), loaded.stamp)

        assertTrue(result is SaveResult.Saved)
        assertArrayEquals("v2".toByteArray(), file.content)
    }

    @Test
    fun saveRefusesToOverwriteExternalChange() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed-by-desktop".toByteArray())
        val result = repo.save("v2".toByteArray(), loaded.stamp)

        assertTrue(result is SaveResult.Conflict)
        // 外部版本必須完好無損
        assertArrayEquals("changed-by-desktop".toByteArray(), file.content)
    }

    @Test
    fun conflictWritesLocalVersionToSiblingFile() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed".toByteArray())
        val result = repo.save("my-local-work".toByteArray(), loaded.stamp) as SaveResult.Conflict

        assertArrayEquals("my-local-work".toByteArray(), file.siblings[result.conflictFileName])
    }

    @Test
    fun conflictFileNameCarriesConflictMarkerAndKdbxExtension() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        file.externallyReplacedWith("changed".toByteArray())
        val result = repo.save("local".toByteArray(), loaded.stamp) as SaveResult.Conflict

        assertTrue(result.conflictFileName.contains(".conflict-"))
        assertTrue(result.conflictFileName.endsWith(".kdbx"))
    }

    @Test
    fun saveBacksUpPreviousContentBeforeOverwriting() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val backups = FakeBackupStore()
        val repo = VaultRepository(file, backups)
        val loaded = repo.load()

        repo.save("v2".toByteArray(), loaded.stamp)

        assertArrayEquals("v1".toByteArray(), backups.get())
    }

    @Test
    fun backupIsTakenBeforeWriteSoFailedWriteIsRecoverable() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val backups = FakeBackupStore()
        val repo = VaultRepository(file, backups)
        val loaded = repo.load()
        file.writeShouldFail = true

        try {
            repo.save("v2".toByteArray(), loaded.stamp)
        } catch (_: java.io.IOException) {
        }

        // 寫入炸掉了，但前一版還救得回來
        assertArrayEquals("v1".toByteArray(), backups.get())
    }

    @Test
    fun savedStampMatchesWhatWasActuallyWritten() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        val loaded = repo.load()

        val result = repo.save("v2".toByteArray(), loaded.stamp) as SaveResult.Saved

        // 回傳的 stamp 必須能直接用於下一次存檔的比對
        assertEquals(FileStamp.of(file.content, file.mtime), result.newStamp)
    }

    @Test
    fun consecutiveSavesSucceedUsingReturnedStamp() {
        val file = FakeVaultFile(content = "v1".toByteArray())
        val repo = VaultRepository(file, FakeBackupStore())
        var stamp = repo.load().stamp

        stamp = (repo.save("v2".toByteArray(), stamp) as SaveResult.Saved).newStamp
        val second = repo.save("v3".toByteArray(), stamp)

        assertTrue(second is SaveResult.Saved)
        assertArrayEquals("v3".toByteArray(), file.content)
    }
}
