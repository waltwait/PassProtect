package com.hintvault.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.KeyPermanentlyInvalidException
import com.hintvault.crypto.toUtf8CharArray
import com.hintvault.crypto.wipe
import com.hintvault.session.LockState
import com.hintvault.session.VaultSession
import com.hintvault.storage.BackupStore
import com.hintvault.storage.FileBackupStore
import com.hintvault.storage.FileStamp
import com.hintvault.storage.SafVaultFile
import com.hintvault.storage.VaultFile
import com.hintvault.storage.VaultHealthCheck
import com.hintvault.storage.VaultRepository
import com.hintvault.storage.VaultUriPermissions
import com.hintvault.vault.UnsupportedVaultFormatException
import com.hintvault.vault.Vault
import com.hintvault.vault.WrongPasswordException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次設定與解鎖流程的狀態機（Task 7 會加存檔/刪除，Task 8 會加設定）。
 *
 * **不持有 [Context]**：所有需要它的操作（挑資料夾、建立 [SafVaultFile]）
 * 都把 `Context` 當參數傳入對應函式，而非建構子欄位或 `private val` 快取。
 * 這讓本類別可以用無參數建構子在 JVM 測試裡直接 `VaultViewModel()`，
 * 不需要 Robolectric 或任何 Android 框架。
 *
 * **主密碼快取**：`Vault.serialize()` 每次存檔都需要主密碼——KeePassJava2
 * 沒有「已經解鎖過、之後存檔不用再給密碼」這種介面。若不快取，使用者每編輯
 * 一筆就要重新輸入主密碼，不符合規格 §4.4「解鎖 → 清單 → 編輯 → 存檔」的
 * 隱含流程。因此在解鎖成功時（不論是手動輸入還是生體辨識解出的）把密碼複製
 * 一份存進 [masterPasswordCache]，[lock] 與 [onCleared] 時清空。這與規格 §2
 * 已經接受的風險同一等級——密碼在解鎖期間本來就會以明文存在記憶體中，這裡
 * 只是把「多久存在」從單次操作延長到整個 unlocked session，沒有引入新的
 * 風險類別。
 */
class VaultViewModel : ViewModel() {

    private val session = VaultSession()
    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var directory: DocumentFile? = null
    private var file: VaultFile? = null
    private var backups: BackupStore? = null
    private var repository: VaultRepository? = null
    private var loadedStamp: FileStamp? = null
    private var masterPasswordCache: CharArray? = null

    /**
     * 執行 KDF 等 CPU 密集運算的背景 dispatcher。
     *
     * 實機用 [Dispatchers.Default]，才不會在 Argon2 derivation（規格量測約 2 秒）
     * 期間卡住主執行緒。以欄位注入而非寫死在 [attemptUnlock] 裡，理由與
     * `AutoLock` 注入 `now: () -> Long` 相同：`MainDispatcherRule` 用
     * `UnconfinedTestDispatcher` 頂替的只有 `Dispatchers.Main`，真正切到
     * `Dispatchers.Default` 的協程是排到另一條真實執行緒、非同步完成，
     * JVM 測試在呼叫完 `unlockWithPassword()` 後立刻讀 `uiState.value` 會
     * 抓到協程還沒跑完的舊狀態。測試改用 [setUnlockDispatcherForTest] 把它
     * 換成不切執行緒的 dispatcher，讓整條協程在單一測試執行緒同步跑完。
     */
    private var unlockDispatcher: CoroutineDispatcher = Dispatchers.Default

    // ---- 首次設定 / 資料夾偵測 ----

    fun checkExistingSetup(context: Context) {
        val biometricEnabled = com.hintvault.crypto.WrappedKeyStore(keyDir(context)).exists()
        _uiState.update { it.copy(biometricEnabled = biometricEnabled) }

        val location = VaultLocationPrefs(context).get()
        if (location == null || !VaultUriPermissions.isStillGranted(context, location.treeUri)) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        val dir = DocumentFile.fromTreeUri(context, location.treeUri)
        if (dir == null || dir.findFile(location.fileName) == null) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        directory = dir
        selectFile(context, dir, location.fileName)
    }

    fun onFolderChosen(context: Context, treeUri: Uri) {
        VaultUriPermissions.persist(context, treeUri)
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
        directory = dir

        val candidates = dir.listFiles().mapNotNull { it.name }.filter { it.endsWith(".kdbx") }
        when {
            candidates.isEmpty() ->
                _uiState.update { it.copy(setup = SetupState.NeedsNewVaultPassword) }
            candidates.size == 1 ->
                selectFile(context, dir, candidates.single())
            else ->
                _uiState.update { it.copy(setup = SetupState.NeedsFileChoice(candidates)) }
        }
    }

    fun onFileChosen(context: Context, fileName: String) {
        val dir = directory ?: return
        selectFile(context, dir, fileName)
    }

    fun onNewVaultPasswordChosen(context: Context, masterPassword: CharArray) {
        val dir = directory ?: run { masterPassword.wipe(); return }
        val fileName = "vault.kdbx"
        val newFile = SafVaultFile(context, dir, fileName)
        val vault = Vault.create()
        val bytes = vault.serialize(masterPassword)
        newFile.create(bytes)

        val backupStore = FileBackupStore(File(context.filesDir, "backups"))
        file = newFile
        backups = backupStore
        repository = VaultRepository(newFile, backupStore)
        loadedStamp = FileStamp.of(bytes, newFile.lastModified())
        VaultLocationPrefs(context).put(dir.uri, fileName)

        cacheMasterPassword(masterPassword)
        masterPassword.wipe()
        session.unlock(vault)
        _uiState.update {
            it.copy(setup = SetupState.Ready, lockState = LockState.Unlocked, entries = vault.entries())
        }
    }

    fun resolveBackupDecision(accept: Boolean) {
        val state = uiState.value.setup as? SetupState.NeedsBackupDecision ?: return
        if (accept) {
            val f = file
            val b = backups
            if (f != null && b != null) VaultHealthCheck(f, b).restoreFromBackup(state.backupBytes)
        }
        _uiState.update { it.copy(setup = SetupState.Ready) }
    }

    private fun selectFile(context: Context, dir: DocumentFile, fileName: String) {
        val safFile = SafVaultFile(context, dir, fileName)
        val backupStore = FileBackupStore(File(context.filesDir, "backups"))
        file = safFile
        backups = backupStore
        repository = VaultRepository(safFile, backupStore)
        VaultLocationPrefs(context).put(dir.uri, fileName)

        when (val result = VaultHealthCheck(safFile, backupStore).check()) {
            VaultHealthCheck.Result.Healthy ->
                _uiState.update { it.copy(setup = SetupState.Ready) }
            is VaultHealthCheck.Result.RecoverableFromBackup ->
                _uiState.update { it.copy(setup = SetupState.NeedsBackupDecision(result.backupBytes)) }
            VaultHealthCheck.Result.Unrecoverable ->
                _uiState.update { it.copy(setup = SetupState.Ready, unlockError = UnlockError.FILE_MISSING) }
        }
    }

    private fun keyDir(context: Context) = File(context.filesDir, "keys")

    // ---- 解鎖 ----

    fun unlockWithPassword(masterPassword: CharArray) {
        viewModelScope.launch { attemptUnlock(masterPassword) }
    }

    fun unlockWithBiometric(biometric: BiometricUnlock) {
        viewModelScope.launch {
            val passwordBytes = try {
                biometric.unlock()
            } catch (e: KeyPermanentlyInvalidException) {
                _uiState.update { it.copy(biometricEnabled = false, unlockError = UnlockError.BIOMETRIC_INVALIDATED) }
                null
            }
            if (passwordBytes == null) return@launch
            val password = passwordBytes.toUtf8CharArray()
            passwordBytes.wipe()
            attemptUnlock(password)
        }
    }

    private suspend fun attemptUnlock(masterPassword: CharArray) {
        val repo = repository
        if (repo == null) {
            masterPassword.wipe()
            return
        }
        try {
            val loaded = withContext(unlockDispatcher) { repo.load() }
            val vault = withContext(unlockDispatcher) { Vault.open(loaded.bytes, masterPassword) }
            loadedStamp = loaded.stamp
            cacheMasterPassword(masterPassword)
            session.unlock(vault)
            _uiState.update {
                it.copy(
                    setup = SetupState.Ready,
                    lockState = LockState.Unlocked,
                    entries = vault.entries(),
                    unlockError = null
                )
            }
        } catch (e: WrongPasswordException) {
            _uiState.update { it.copy(unlockError = UnlockError.WRONG_PASSWORD) }
        } catch (e: UnsupportedVaultFormatException) {
            _uiState.update { it.copy(unlockError = UnlockError.UNSUPPORTED_FORMAT) }
        } catch (e: Exception) {
            _uiState.update { it.copy(unlockError = UnlockError.FILE_MISSING) }
        } finally {
            masterPassword.wipe()
        }
    }

    fun lock() {
        session.lock()
        masterPasswordCache?.wipe()
        masterPasswordCache = null
        _uiState.update { it.copy(lockState = LockState.Locked, entries = emptyList()) }
    }

    /** 解鎖成功後呼叫。先清舊快取再存新的一份——不留兩份明文密碼同時存在。 */
    private fun cacheMasterPassword(masterPassword: CharArray) {
        masterPasswordCache?.wipe()
        masterPasswordCache = masterPassword.copyOf()
    }

    override fun onCleared() {
        masterPasswordCache?.wipe()
        super.onCleared()
    }

    // ---- 測試用入口：繞過 SAF，直接注入已就緒的 repository ----
    //
    // checkExistingSetup/onFolderChosen 等協調函式依賴 DocumentFile.fromTreeUri()/
    // ContentResolver，純 JVM 測試環境用不了（本專案沒有 Robolectric）。這幾個
    // internal 入口讓 VaultViewModelTest 能繞過 SAF、直接把已就緒的
    // VaultRepository/VaultFile/BackupStore 灌進來，測到解鎖/存檔邏輯本身。
    // SAF 協調函式的真實行為留給 Task 18 的真機端到端測試。

    internal fun setRepositoryForTest(repository: VaultRepository, stamp: FileStamp) {
        this.repository = repository
        this.loadedStamp = stamp
    }

    internal fun setFileAndBackupsForTest(file: VaultFile, backups: BackupStore) {
        this.file = file
        this.backups = backups
    }

    internal fun forceSetupStateForTest(setup: SetupState) {
        _uiState.update { it.copy(setup = setup) }
    }

    /** 見 [unlockDispatcher] 的 KDoc：讓測試把背景運算換成不切執行緒的 dispatcher。 */
    internal fun setUnlockDispatcherForTest(dispatcher: CoroutineDispatcher) {
        unlockDispatcher = dispatcher
    }
}
