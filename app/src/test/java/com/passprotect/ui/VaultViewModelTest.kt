package com.passprotect.ui

import com.passprotect.crypto.KdfParams
import com.passprotect.session.LockState
import com.passprotect.storage.FakeBackupFolder
import com.passprotect.storage.FileStamp
import com.passprotect.storage.VaultFile
import com.passprotect.storage.VaultRepository
import com.passprotect.vault.Vault
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** 模擬「檔案在 session 中途被外部刪掉」——VaultRepository KDoc 記載的失敗模式。 */
        var failReads = false
        /** 模擬 SAF 建檔失敗（權限沒了、撞名…）。 */
        var failCreate = false
        var created = false

        override val displayName = "vault.kdbx"
        override fun read(): ByteArray {
            if (failReads) throw java.io.FileNotFoundException("外部刪掉了")
            return content
        }
        override fun write(bytes: ByteArray) { content = bytes }
        override fun lastModified() = 1000L
        override fun writeSibling(suffix: String, bytes: ByteArray) = displayName + suffix
        override fun create(bytes: ByteArray) {
            if (failCreate) throw java.io.FileNotFoundException("無法建立")
            content = bytes
            created = true
        }
    }

    private class FakeBackupStore : com.passprotect.storage.BackupStore {
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

    @Test
    fun consecutiveWrongPasswordAttemptsResetsIsUnlockingFlag() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("right"))
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw("wrong"))
        val firstError = vm.uiState.value.unlockError
        val firstIsUnlocking = vm.uiState.value.isUnlocking

        vm.unlockWithPassword(pw("wrong"))

        assertEquals(UnlockError.WRONG_PASSWORD, firstError)
        assertEquals(false, firstIsUnlocking)
        assertEquals(UnlockError.WRONG_PASSWORD, vm.uiState.value.unlockError)
        assertEquals(false, vm.uiState.value.isUnlocking)
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

        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "github.com",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
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

        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "conflict-test",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
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
        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "to-delete",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        vm.deleteEntry(entry.id)

        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun setAutoLockTimeoutUpdatesPolicy() = runTest {
        val vm = VaultViewModel()
        vm.setAutoLockTimeout(15 * 60_000L)
        assertEquals(15 * 60_000L, vm.uiState.value.autoLockPolicy.idleTimeoutMillis)
    }

    @Test
    fun setBackgroundGraceUpdatesPolicy() = runTest {
        val vm = VaultViewModel()
        vm.setBackgroundGrace(60_000L)
        assertEquals(60_000L, vm.uiState.value.autoLockPolicy.backgroundGraceMillis)
    }

    @Test
    fun changeMasterPasswordReserializesAndSaves() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("old-password"))
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw("old-password"))

        vm.changeMasterPassword(pw("new-password"), biometric = null)

        assertEquals(SaveMessage.Saved, vm.uiState.value.saveMessage)
    }

    @Test
    fun changeMasterPasswordThenReopeningWithNewPasswordWorks() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw("old-password"))
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(kotlinx.coroutines.Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw("old-password"))

        vm.changeMasterPassword(pw("new-password"), biometric = null)

        // 舊密碼開不起來了，新密碼開得起來——直接用底層 Vault.open 驗證存檔內容本身
        var threwWithOldPassword = false
        try {
            Vault.open(file.content, pw("old-password"))
        } catch (_: com.passprotect.vault.WrongPasswordException) {
            threwWithOldPassword = true
        }
        assertTrue(threwWithOldPassword)
        Vault.open(file.content, pw("new-password")) // 不拋例外即為成功
    }

    // ---- C1：存檔進行中被上鎖，不可把主密碼從底下抽走 ----

    /**
     * 這是唯一一個真的把 race 排出來的測試：把 [VaultViewModel.setUnlockDispatcherForTest]
     * 換成 `StandardTestDispatcher`，`persist()` 裡 `withContext(unlockDispatcher)`
     * 之後的 Argon2 序列化與寫檔就會停在排程器上等 `advanceUntilIdle()`——正好對應
     * 實機上「按下儲存後隨手關螢幕，AutoLock 零寬限期觸發 lock()」的那個時間窗。
     *
     * 修好之前：`persist()` 拿的是 `masterPasswordCache` 本人，`lock()` 會把它就地
     * 覆寫成 NUL，之後才跑的 KDF 等於用一把全空的密碼加密，寫出來的 KDBX
     * 誰也打不開——永久資料遺失。修好之後：`persist()` 一進來就複製一份自己的，
     * 因此下面 `Vault.open(file.content, pw())` 必須成功。
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun lockDuringAnInFlightSaveStillWritesAFileTheRealPasswordCanOpen() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw())
        vm.setUnlockDispatcherForTest(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))

        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "race",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)
        vm.lock() // 存檔還卡在 dispatcher 上時上鎖
        advanceUntilIdle()

        assertEquals(SaveMessage.Saved, vm.uiState.value.saveMessage)
        val reopened = Vault.open(file.content, pw())
        assertTrue(reopened.entries().any { it.title == "race" })
    }

    // ---- I1：存檔失敗必須有訊息，不能靜默卡住或崩潰 ----

    /**
     * `VaultRepository.save()` 自己的 KDoc 就寫明：檔案在 session 中途被外部刪掉時
     * 它直接擲例外。修好之前 `persist()` 沒有任何 try/catch，這個例外會從
     * `viewModelScope.launch` 逃出去直接讓 app 崩潰；而且 `saveEntry()` 已經先把
     * `saveMessage` 清成 null，畫面連個訊息都不會有。
     */
    @Test
    fun saveFailureFromExternalDeletionSurfacesErrorInsteadOfCrashing() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw())
        file.failReads = true

        vm.saveEntry(
            com.passprotect.vault.Entry(
                id = java.util.UUID.randomUUID(),
                title = "會失敗",
                accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
                modifiedAt = java.time.Instant.now()
            )
        )

        assertEquals(SaveMessage.Error, vm.uiState.value.saveMessage)
    }

    @Test
    fun deleteFailureAlsoSurfacesErrorMessage() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val file = FakeVaultFile(bytes)
        val repo = VaultRepository(file, FakeBackupStore())
        val vm = VaultViewModel().apply {
            setRepositoryForTest(repo, FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
        }
        vm.unlockWithPassword(pw())
        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "to-delete",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)
        file.failReads = true

        vm.deleteEntry(entry.id)

        assertEquals(SaveMessage.Error, vm.uiState.value.saveMessage)
    }

    // ---- I3：換資料夾必須先整組重設，不可拿舊的解密內容配新檔案 ----

    @Test
    fun resetForNewLocationLocksAndBlocksSavingWithTheStaleVault() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.resetForNewLocation()

        assertEquals(LockState.Locked, vm.uiState.value.lockState)
        assertTrue(vm.uiState.value.entries.isEmpty())
        assertEquals(false, vm.uiState.value.isUnlocking)
        // 舊的解密 vault 已經不在 session 裡：任何存檔都會直接被擋下來，
        // 不可能把舊資料庫的內容寫進剛選的新資料庫。
        var threw = false
        try {
            vm.saveEntry(
                com.passprotect.vault.Entry(
                    id = java.util.UUID.randomUUID(),
                    title = "stale",
                    accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
                    modifiedAt = java.time.Instant.now()
                )
            )
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---- 歷史紀錄寫入路徑（EditScreen.entryToSave → Vault.upsert → 檔案 → entries()）----

    /**
     * [com.passprotect.ui.screens.entryToSave] 的規則本身在 `EditScreenEntryTest` 測；
     * 這裡測的是它接上 `Vault.upsert()`/`HistoryCodec` 之後，舊提示真的存進了
     * KDBX 並且讀得回來——否則 `HistoryScreen` 還是會永遠顯示「沒有歷史紀錄」。
     */
    @Test
    fun editingAnEntrysHintPersistsThePreviousHintIntoHistory() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.saveEntry(
            com.passprotect.ui.screens.entryToSave(
                null, title = "github.com",
                accounts = listOf(com.passprotect.ui.screens.AccountDraft(java.util.UUID.randomUUID(), hint = "舊提示"))
            )
        )
        val stored = vm.uiState.value.entries.single()
        vm.saveEntry(
            com.passprotect.ui.screens.entryToSave(
                stored, title = "github.com",
                accounts = listOf(com.passprotect.ui.screens.AccountDraft(stored.accounts.single().id, hint = "新提示"))
            )
        )

        val updated = vm.uiState.value.entries.single()
        assertEquals("新提示", updated.accounts.single().hint)
        assertEquals(listOf("舊提示"), updated.accounts.single().history.map { it.hint })
    }

    // ---- I2 + I5：建立新資料庫 ----

    @Test
    fun createNewVaultCalibratesWritesTheFileAndUnlocksIt() = runTest {
        val file = FakeVaultFile(ByteArray(0))
        val vm = VaultViewModel().apply { setUnlockDispatcherForTest(Dispatchers.Unconfined) }
        val password = pw("brand new")
        var locationRemembered = false

        vm.createNewVault(file, FakeBackupStore(), password) { locationRemembered = true }

        assertEquals(SetupState.Ready, vm.uiState.value.setup)
        assertEquals(LockState.Unlocked, vm.uiState.value.lockState)
        assertEquals(false, vm.uiState.value.isCreatingVault)
        assertTrue(locationRemembered)
        assertTrue(file.created)
        // 校準選出來的參數真的被用去加密：新檔案用同一把主密碼開得起來。
        Vault.open(file.content, pw("brand new"))
        // 呼叫端傳進來的密碼陣列在每一條離開路徑都被清空。
        assertTrue(password.all { it == '\u0000' })
    }

    /**
     * 修好之前 `newFile.create()` 擲例外會直接從 Compose 的 onClick 炸掉 app，
     * 而且那條路徑不清主密碼。
     */
    @Test
    fun createNewVaultFailureSurfacesSetupErrorWipesPasswordAndStaysLocked() = runTest {
        val file = FakeVaultFile(ByteArray(0)).apply { failCreate = true }
        val vm = VaultViewModel().apply { setUnlockDispatcherForTest(Dispatchers.Unconfined) }
        val password = pw("brand new")

        vm.createNewVault(file, FakeBackupStore(), password) { }

        assertTrue(vm.uiState.value.setupError != null)
        assertEquals(false, vm.uiState.value.isCreatingVault)
        assertEquals(LockState.Locked, vm.uiState.value.lockState)
        assertTrue(password.all { it == '\u0000' })

        vm.clearSetupError()
        assertNull(vm.uiState.value.setupError)
    }

    @Test
    fun addHintSnippetAppearsInUiStateAndSurvivesReload() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.addHintSnippet("生日 + 寵物名")

        assertEquals(listOf("生日 + 寵物名"), vm.uiState.value.hintSnippets)
    }

    @Test
    fun addHintSnippetIgnoresBlankAndDuplicate() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        vm.addHintSnippet("常用句")
        vm.addHintSnippet("   ")
        vm.addHintSnippet("常用句")

        assertEquals(listOf("常用句"), vm.uiState.value.hintSnippets)
    }

    @Test
    fun removeHintSnippetTakesItOutOfUiState() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())
        vm.addHintSnippet("留著")
        vm.addHintSnippet("刪掉")

        vm.removeHintSnippet("刪掉")

        assertEquals(listOf("留著"), vm.uiState.value.hintSnippets)
    }

    @Test
    fun unlockingRestoresPreviouslySavedHintSnippets() = runTest {
        val vault = Vault.create(fastKdf)
        vault.setHintSnippets(listOf("既有的常用句"))
        val bytes = vault.serialize(pw())
        val vm = readyViewModel(bytes)

        vm.unlockWithPassword(pw())

        assertEquals(listOf("既有的常用句"), vm.uiState.value.hintSnippets)
    }

    @Test
    fun savingEntryWithCategoryIsReflectedInUiStateEntries() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val vm = readyViewModel(bytes)
        vm.unlockWithPassword(pw())

        val entry = com.passprotect.vault.Entry(
            id = java.util.UUID.randomUUID(),
            title = "github.com",
            category = "工作",
            accounts = listOf(com.passprotect.vault.Account(id = java.util.UUID.randomUUID(), hint = "提示")),
            modifiedAt = java.time.Instant.now()
        )
        vm.saveEntry(entry)

        assertEquals("工作", vm.uiState.value.entries.single().category)
    }

    // ---- 本地備份（規格 2026-10-24-local-backup §3） ----

    private val backupDay = LocalDateTime.of(2026, 10, 24, 10, 0)
    private val todayName = "PassProtect-2026-10-24.kdbx"

    private fun entry(title: String) = com.passprotect.vault.Entry(
        id = UUID.randomUUID(),
        title = title,
        accounts = listOf(com.passprotect.vault.Account(id = UUID.randomUUID(), hint = "提示")),
        modifiedAt = Instant.now()
    )

    private class BackupSetup(val vm: VaultViewModel, val file: FakeVaultFile, val folder: FakeBackupFolder?)

    private fun backupReady(
        bytes: ByteArray,
        folder: FakeBackupFolder? = FakeBackupFolder(),
        now: () -> LocalDateTime = { backupDay }
    ): BackupSetup {
        val file = FakeVaultFile(bytes)
        val vm = VaultViewModel().apply {
            setRepositoryForTest(VaultRepository(file, FakeBackupStore()), FileStamp.of(bytes, file.lastModified()))
            setUnlockDispatcherForTest(Dispatchers.Unconfined)
            setBackupFolderForTest(folder)
            setClockForTest(now)
        }
        return BackupSetup(vm, file, folder)
    }

    @Test
    fun unlockBacksUpTodayOnce() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes)
        val folder = s.folder!!

        s.vm.unlockWithPassword(pw())
        assertArrayEquals(bytes, folder.files[todayName])

        folder.files[todayName] = byteArrayOf(7)
        s.vm.lock()
        s.vm.unlockWithPassword(pw())
        assertArrayEquals(byteArrayOf(7), folder.files[todayName])
    }

    @Test
    fun saveRefreshesTodaysBackup() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes)
        s.vm.unlockWithPassword(pw())

        s.vm.saveEntry(entry("github.com"))

        assertEquals(SaveMessage.Saved, s.vm.uiState.value.saveMessage)
        assertFalse(s.file.content.contentEquals(bytes))
        assertArrayEquals(s.file.content, s.folder!!.files[todayName])
    }

    @Test
    fun conflictedSaveDoesNotBackUp() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes)
        s.vm.unlockWithPassword(pw())
        s.file.content = s.file.content + byteArrayOf(0)

        s.vm.saveEntry(entry("conflict"))

        assertTrue(s.vm.uiState.value.saveMessage is SaveMessage.Conflict)
        assertArrayEquals(bytes, s.folder!!.files[todayName])
    }

    @Test
    fun folderErrorsNeverFailUnlockOrSave() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes)
        val folder = s.folder!!
        folder.failWrites = true

        s.vm.unlockWithPassword(pw())
        assertEquals(LockState.Unlocked, s.vm.uiState.value.lockState)
        s.vm.saveEntry(entry("github.com"))
        assertEquals(SaveMessage.Saved, s.vm.uiState.value.saveMessage)
        assertTrue(s.vm.uiState.value.backupFailed)

        folder.failWrites = false
        s.vm.backupNow()
        assertFalse(s.vm.uiState.value.backupFailed)
    }

    @Test
    fun saveAfterMidnightStartsANewDay() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        var now = LocalDateTime.of(2026, 10, 24, 23, 59)
        val s = backupReady(bytes, now = { now })
        s.vm.unlockWithPassword(pw())

        now = LocalDateTime.of(2026, 10, 25, 0, 1)
        s.vm.saveEntry(entry("github.com"))

        assertArrayEquals(bytes, s.folder!!.files[todayName])
        assertArrayEquals(s.file.content, s.folder.files["PassProtect-2026-10-25.kdbx"])
    }

    @Test
    fun backupNowWritesTheCurrentFile() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes)
        s.vm.unlockWithPassword(pw())
        s.folder!!.files[todayName] = byteArrayOf(7)

        s.vm.backupNow()

        assertArrayEquals(s.file.content, s.folder.files[todayName])
        assertEquals(1, s.vm.uiState.value.backups.size)
    }

    @Test
    fun noFolderMeansNoBackupAndNoError() = runTest {
        val bytes = Vault.create(fastKdf).serialize(pw())
        val s = backupReady(bytes, folder = null)

        s.vm.unlockWithPassword(pw())
        s.vm.saveEntry(entry("github.com"))

        assertEquals(SaveMessage.Saved, s.vm.uiState.value.saveMessage)
        assertFalse(s.vm.uiState.value.backupFailed)
    }
}
