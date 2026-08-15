package com.hintvault.ui

import com.hintvault.crypto.KdfParams
import com.hintvault.session.LockState
import com.hintvault.storage.FileStamp
import com.hintvault.storage.VaultFile
import com.hintvault.storage.VaultRepository
import com.hintvault.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VaultViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fastKdf = KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)
    private fun pw(text: String = "correct horse") = text.toCharArray()

    /** JVM 上可用的最小假實作，繞過真正的 SAF。 */
    private class FakeVaultFile(var content: ByteArray) : VaultFile {
        override val displayName = "vault.kdbx"
        override fun read() = content
        override fun write(bytes: ByteArray) { content = bytes }
        override fun lastModified() = 1000L
        override fun writeSibling(suffix: String, bytes: ByteArray) = displayName + suffix
        override fun create(bytes: ByteArray) { content = bytes }
    }

    private class FakeBackupStore : com.hintvault.storage.BackupStore {
        var stored: ByteArray? = null
        override fun put(bytes: ByteArray) { stored = bytes }
        override fun get() = stored
    }

    private fun readyViewModel(bytes: ByteArray): VaultViewModel {
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val stamp = FileStamp.of(bytes, file.lastModified())
        return VaultViewModel().apply {
            setRepositoryForTest(repo, stamp)
            // Dispatchers.Default 是真實執行緒池，不受 MainDispatcherRule 的
            // UnconfinedTestDispatcher 控制；換成 Dispatchers.Unconfined 讓
            // attemptUnlock() 全程留在呼叫端執行緒，測試才能在 unlockWithPassword()
            // 呼叫完後立刻讀到最終狀態，不必自己 advanceUntilIdle()/等待背景執行緒。
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
        }
    }

    @Test
    fun unlockWithCorrectPasswordMovesToReadyAndUnlocked() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw())

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertEquals(LockState.Unlocked, vm.uiState.value.lockState)
        assertNull(vm.uiState.value.unlockError)
    }

    @Test
    fun unlockWithWrongPasswordSetsWrongPasswordError() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("right"))
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw("wrong"))

        assertEquals(UnlockError.WRONG_PASSWORD, vm.uiState.value.unlockError)
        assertEquals(LockState.Locked, vm.uiState.value.lockState)
    }

    /**
     * `Vault.kt` 的 `WrongPasswordException` KDoc 講得很明白：「密碼錯誤，或檔案
     * 損毀 / 非 KDBX」——`Vault.open()` 的 catch-all（除了「Unknown Cipher UUID」
     * 這個特例）一律包成 `WrongPasswordException`，因此對完全不是 KDBX 的輸入，
     * 這裡斷言 `WRONG_PASSWORD` 才是符合核心引擎規格的行為，不是穿透到函式庫
     * 內部細節去猜例外型別。
     */
    @Test
    fun unlockWithGarbageBytesSetsWrongPasswordError() = runTest {
        val vm = readyViewModel("not a kdbx file at all".toByteArray())

        vm.unlockWithPassword(pw())

        assertEquals(UnlockError.WRONG_PASSWORD, vm.uiState.value.unlockError)
    }

    @Test
    fun unlockWipesThePasswordArrayPassedIn() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        val password = pw()

        vm.unlockWithPassword(password)

        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun lockClearsEntriesAndReturnsToLocked() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.lock()

        assertEquals(LockState.Locked, vm.uiState.value.lockState)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun resolveBackupDecisionAcceptingRestoresAndMovesToReady() = runTest {
        val goodBytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(ByteArray(0))
        val backups = FakeBackupStore().apply { stored = goodBytes }
        val repo = VaultRepository(file, backups)
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(ByteArray(0), 0L))
            setFileAndBackupsForTest(file, backups)
            forceSetupStateForTest(SetupState.NeedsBackupDecision(goodBytes))
        }

        vm.resolveBackupDecision(accept = true)

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertEquals(goodBytes.toList(), file.content.toList())
    }

    @Test
    fun resolveBackupDecisionDecliningStillMovesToReadyWithoutRestoring() = runTest {
        val file = FakeVaultFile(ByteArray(0))
        val backups = FakeBackupStore().apply { stored = "backup".toByteArray() }
        val repo = VaultRepository(file, backups)
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(ByteArray(0), 0L))
            setFileAndBackupsForTest(file, backups)
            forceSetupStateForTest(SetupState.NeedsBackupDecision("backup".toByteArray()))
        }

        vm.resolveBackupDecision(accept = false)

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertTrue(file.content.isEmpty())
    }

    @Test
    fun saveEntryPersistsAndUpdatesEntriesInState() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "github.com",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("github.com", vm.uiState.value.entries.single().title)
        assertEquals(SaveMessage.Saved, vm.uiState.value.saveMessage)
    }

    @Test
    fun saveEntryDetectsExternalConflictAndDoesNotLoseTheChange() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw())

        // 外部（模擬同步工具）在解鎖之後改了檔案
        file.content = file.content + byteArrayOf(0)

        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "conflict-test",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        assertTrue(vm.uiState.value.saveMessage is SaveMessage.Conflict)
        assertTrue(vm.uiState.value.entries.any { it.title == "conflict-test" })
    }

    @Test
    fun deleteEntryRemovesItFromStateAndPersists() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())
        val entry = com.hintvault.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "to-delete",
            hint = "提示",
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        vm.deleteEntry(entry.id)

        assertTrue(vm.uiState.value.entries.isEmpty())
    }
}
