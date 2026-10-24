package com.passprotect.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.passprotect.crypto.BiometricUnlock
import com.passprotect.crypto.KdfParams
import com.passprotect.crypto.KeyPermanentlyInvalidException
import com.passprotect.crypto.KeystoreKeys
import com.passprotect.crypto.WrappedKeyStore
import com.passprotect.crypto.toUtf8Bytes
import com.passprotect.crypto.toUtf8CharArray
import com.passprotect.crypto.wipe
import com.passprotect.session.AutoLockPolicy
import com.passprotect.session.LockState
import com.passprotect.session.VaultSession
import com.passprotect.storage.BackupStore
import com.passprotect.storage.FileBackupStore
import com.passprotect.storage.FileStamp
import com.passprotect.storage.SafVaultFile
import com.passprotect.storage.SaveResult
import com.passprotect.storage.VaultFile
import com.passprotect.storage.VaultHealthCheck
import com.passprotect.storage.VaultRepository
import com.passprotect.storage.VaultUriPermissions
import com.passprotect.vault.Entry
import com.passprotect.vault.UnsupportedVaultFormatException
import com.passprotect.vault.Vault
import com.passprotect.vault.WrongPasswordException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次設定與解鎖流程的狀態機（Task 7 會加存檔/刪除，Task 8 會加設定）。
 *
 * **不持有 Context-dependent 物件**：所有需要 [Context] 的操作（挑資料夾、建立
 * [SafVaultFile]）都把 `Context` 當參數傳入對應函式，而非建構子欄位或
 * `private val` 快取。這讓本類別可以用無參數建構子在 JVM 測試裡直接
 * `VaultViewModel()`，不需要 Robolectric 或任何 Android 框架。
 *
 * 更嚴格的是：真正被快取進 [file]/[directory] 欄位的 [SafVaultFile]/
 * [DocumentFile] 物件，內部都持有它們建構時拿到的 `Context`。實際呼叫端
 * （Task 16 的 `PassProtectApp`，透過 Compose 的 `LocalContext.current`）給的
 * 是 Activity Context，不是 Application——若直接拿它去建構 `SafVaultFile`/
 * `DocumentFile.fromTreeUri()`，快取下來的欄位就等於讓本 ViewModel 間接
 * 持有一個 Activity，在設定變更（旋轉螢幕）後這個 Activity 已經被銷毀，
 * ViewModel 卻還活著、還握著它——典型的 ViewModel-holds-Activity 洩漏。
 * 因此每個收 [Context] 參數的函式，第一件事就是呼叫
 * `context.applicationContext` 換成 Application Context，讓任何後續由它
 * 建構、可能被快取的物件永遠只持有活得比 Activity 久的那個 Context，不論
 * 呼叫端實際傳進來的是什麼。
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

    /**
     * 真正被授予 `takePersistableUriPermission` 的那個 URI（`ACTION_OPEN_DOCUMENT_TREE`
     * 回傳的原始 tree URI）。**不可**用 `directory.uri` 代替——`DocumentFile.fromTreeUri()`
     * 對樹狀根目錄回傳的 `.uri` 是 `tree/X/document/X` 這種組合形式（androidx.documentfile
     * 的 `TreeDocumentFile` 內部行為），與當初 `takePersistableUriPermission()`/
     * `persistedUriPermissions` 記錄的裸 tree URI（`tree/X`）是兩個不同的字串。把前者存進
     * [VaultLocationPrefs] 會讓下次啟動的 [VaultUriPermissions.isStillGranted] 永遠比對
     * 不到、永遠回傳 false——結果是使用者每次重開 app 都被迫重選一次資料夾，即使系統授權
     * 其實完好無損（真機實測重現，見 2026-08-16 的除錯記錄）。
     */
    private var directoryTreeUri: Uri? = null
    private var file: VaultFile? = null
    private var backups: BackupStore? = null
    private var repository: VaultRepository? = null
    private var loadedStamp: FileStamp? = null
    private var masterPasswordCache: CharArray? = null
    private val persistMutex = Mutex()

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
        val appContext = context.applicationContext
        val biometricEnabled = com.passprotect.crypto.WrappedKeyStore(keyDir(appContext)).exists()
        _uiState.update { it.copy(biometricEnabled = biometricEnabled) }

        val location = VaultLocationPrefs(appContext).get()
        if (location == null || !VaultUriPermissions.isStillGranted(appContext, location.treeUri)) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        val dir = DocumentFile.fromTreeUri(appContext, location.treeUri)
        if (dir == null) {
            _uiState.update { it.copy(setup = SetupState.NeedsFolderPicker) }
            return
        }
        // 這裡刻意**不**檢查「檔案還在不在」。檔案不見正是規格 §4.8 的備份還原
        // 流程存在的理由：`SafVaultFile.document` 是 lazy getter，替一個還不存在的
        // 檔名建構 SafVaultFile 是安全的，`VaultHealthCheck.check()` 讀不到檔案時
        // 會轉成 RecoverableFromBackup/Unrecoverable，由 [selectFile] 導到
        // NeedsBackupDecision。若在這裡先攔下來退回 NeedsFolderPicker，使用者只會
        // 看到「建立一個新的空資料庫」，好端端的備份就被靜默孤立了。
        directory = dir
        directoryTreeUri = location.treeUri
        selectFile(appContext, location.treeUri, dir, location.fileName)
    }

    /**
     * 也是設定畫面「重選資料夾」的入口，因此第一件事必須是回到乾淨的上鎖狀態。
     *
     * 這個函式原本只服務首次設定（當下必然是上鎖的），只換掉
     * [directory]/[file]/[backups]/[repository]。設定畫面接上同一個入口後，
     * 使用者可能在**已解鎖**時重選資料夾：那時舊保險庫仍解密在 [session] 裡、
     * 舊主密碼仍在 [masterPasswordCache]、[loadedStamp] 還是舊檔案的指紋，卻
     * 全部被配到新檔案上——下一次存檔會把舊資料庫的內容寫進剛選的新資料庫，
     * 或產生莫名其妙的衝突檔。所以先整組重設，等使用者對新檔案重新解鎖。
     *
     * [loadedStamp] 是 [lock] 本身不會清的（同一個檔案 lock/unlock 來回時它
     * 本來就該留著），但換資料夾會讓它失效，因此這裡額外清掉。
     *
     * 不需要自己加導覽：`PassProtectApp` 的 `LaunchedEffect(setup, lockState)`
     * 會因為 lockState 變回 Locked 而自動把使用者帶離設定畫面。
     */
    fun onFolderChosen(context: Context, treeUri: Uri) {
        resetForNewLocation()

        val appContext = context.applicationContext
        VaultUriPermissions.persist(appContext, treeUri)
        val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return
        directory = dir
        directoryTreeUri = treeUri

        val candidates = dir.listFiles().mapNotNull { it.name }.filter { it.endsWith(".kdbx") }
        when {
            candidates.isEmpty() ->
                _uiState.update { it.copy(setup = SetupState.NeedsNewVaultPassword) }
            candidates.size == 1 ->
                selectFile(appContext, treeUri, dir, candidates.single())
            else ->
                _uiState.update { it.copy(setup = SetupState.NeedsFileChoice(candidates)) }
        }
    }

    fun onFileChosen(context: Context, fileName: String) {
        val appContext = context.applicationContext
        val dir = directory ?: return
        val treeUri = directoryTreeUri ?: return
        selectFile(appContext, treeUri, dir, fileName)
    }

    /**
     * 建立新資料庫。與 [attemptUnlock]/[persist]/[changeMasterPassword] 同一套形狀：
     * Argon2 與 SAF I/O 都在 [unlockDispatcher] 上跑（規格量測 2.2-5.9 秒，留在
     * 主執行緒等於畫面凍住那麼久），整段包在
     * try/catch(Cancellation, rethrow)/catch(Exception)/finally(wipe) 裡——
     * 舊版只在 `dir == null` 這一條路徑清密碼，`newFile.create()` 擲例外時
     * 明文密碼會留在記憶體，而且例外會從 Compose 的 onClick 直接炸掉整個 app。
     *
     * 同時這裡是規格 §4.2 的 KDF 校準點：`Vault.create(kdf)` 本身不跑 KDF，
     * KDF 只發生在 `serialize()`，因此校準必須實際計時
     * `Vault.create(候選參數).serialize(主密碼)`。被選中的那一級所產生的位元組
     * 直接拿去寫檔，不再多跑一次同樣參數的 KDF。
     */
    fun onNewVaultPasswordChosen(context: Context, masterPassword: CharArray) {
        val appContext = context.applicationContext
        val dir = directory
        val treeUri = directoryTreeUri
        if (dir == null || treeUri == null) {
            masterPassword.wipe()
            _uiState.update { it.copy(setupError = "尚未選擇資料夾，請先選擇一個同步資料夾") }
            return
        }
        val fileName = "vault.kdbx"
        createNewVault(
            newFile = SafVaultFile(appContext, dir, fileName),
            backupStore = FileBackupStore(File(appContext.filesDir, "backups")),
            masterPassword = masterPassword,
            rememberLocation = { VaultLocationPrefs(appContext).put(treeUri, fileName) }
        )
    }

    /**
     * [onNewVaultPasswordChosen] 扣掉 SAF/Context 之後的本體。
     *
     * 拆出來的理由跟本檔案其他 `*ForTest` 入口一樣：`SafVaultFile`/
     * `VaultLocationPrefs`/`DocumentFile` 在純 JVM 測試環境裡建不出來，
     * 但校準、非同步、密碼清除、錯誤處理這些真正會出錯的邏輯全都在這裡，
     * 必須測得到。因此把不可測的三個 Android 相依物件都變成參數/lambda。
     */
    internal fun createNewVault(
        newFile: VaultFile,
        backupStore: BackupStore,
        masterPassword: CharArray,
        rememberLocation: () -> Unit
    ) {
        _uiState.update { it.copy(isCreatingVault = true, setupError = null) }
        viewModelScope.launch {
            try {
                val created = withContext(unlockDispatcher) {
                    val outcome = calibrateAndSerialize(masterPassword)
                    newFile.create(outcome.bytes)
                    CreatedVault(outcome, FileStamp.of(outcome.bytes, newFile.lastModified()))
                }

                file = newFile
                backups = backupStore
                repository = VaultRepository(newFile, backupStore)
                loadedStamp = created.stamp
                rememberLocation()

                cacheMasterPassword(masterPassword)
                val vault = created.outcome.vault
                val entries = withContext(unlockDispatcher) { vault.entries() }
                session.unlock(vault)
                _uiState.update {
                    it.copy(
                        setup = SetupState.Ready,
                        lockState = LockState.Unlocked,
                        entries = entries,
                        isCreatingVault = false,
                        setupError = if (KdfParams.exceedsBudget(created.outcome.millis)) SLOW_KDF_NOTICE else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCreatingVault = false, setupError = "建立資料庫失敗，請確認資料夾權限或改選其他資料夾後再試一次")
                }
            } finally {
                masterPassword.wipe()
            }
        }
    }

    /** [calibrateAndSerialize] 的結果：選中的那一級參數、它產生的位元組與實測耗時。 */
    private class CalibrationOutcome(
        val vault: Vault,
        val bytes: ByteArray,
        val params: KdfParams,
        val millis: Long
    )

    private class CreatedVault(val outcome: CalibrationOutcome, val stamp: FileStamp)

    /**
     * 跑 [KdfParams.calibrate]，每一級都真的建一個 vault 並以 [masterPassword]
     * 序列化計時。重複用同一份主密碼沒有問題——被選中之前什麼都還沒寫進檔案。
     */
    private fun calibrateAndSerialize(masterPassword: CharArray): CalibrationOutcome {
        var last: CalibrationOutcome? = null
        val chosen = KdfParams.calibrate { params ->
            val vault = Vault.create(params)
            val start = System.nanoTime()
            val bytes = vault.serialize(masterPassword)
            val millis = (System.nanoTime() - start) / 1_000_000
            last = CalibrationOutcome(vault, bytes, params, millis)
            millis
        }
        // calibrate() 的合約保證回傳值就是最後量測的那一級（第一個在預算內的，
        // 或全部超標時的 FLOOR，也就是階梯最後一級），因此正常情況下可以直接
        // 重用它產生的位元組。這個 fallback 只是不把正確性押在那個合約上。
        last?.takeIf { it.params == chosen }?.let { return it }
        val vault = Vault.create(chosen)
        val start = System.nanoTime()
        val bytes = vault.serialize(masterPassword)
        return CalibrationOutcome(vault, bytes, chosen, (System.nanoTime() - start) / 1_000_000)
    }

    /** 使用者按「知道了」後清掉 [VaultUiState.setupError]（慢速裝置提醒用）。 */
    fun clearSetupError() {
        _uiState.update { it.copy(setupError = null) }
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

    /**
     * [context] 也在此再轉一次 [Context.getApplicationContext]，即使目前所有呼叫端
     * 都已經轉過——這裡是最後一道防線：只要任何一個直接建立 [SafVaultFile]/
     * [DocumentFile] 的地方漏轉，Activity Context 就會經由這兩個物件內部持有的
     * 參照，被存進 [file]/[directory] 欄位，活得比 Activity 本身還久。轉換動作
     * 冪等（對已經是 Application Context 的值再轉一次仍是它自己），因此在這裡
     * 重複轉換沒有成本。
     */
    private fun selectFile(context: Context, treeUri: Uri, dir: DocumentFile, fileName: String) {
        val appContext = context.applicationContext
        val safFile = SafVaultFile(appContext, dir, fileName)
        val backupStore = FileBackupStore(File(appContext.filesDir, "backups"))
        file = safFile
        backups = backupStore
        repository = VaultRepository(safFile, backupStore)
        VaultLocationPrefs(appContext).put(treeUri, fileName)

        when (val result = VaultHealthCheck(safFile, backupStore).check()) {
            VaultHealthCheck.Result.Healthy ->
                _uiState.update { it.copy(setup = SetupState.Ready) }
            is VaultHealthCheck.Result.RecoverableFromBackup ->
                _uiState.update { it.copy(setup = SetupState.NeedsBackupDecision(result.backupBytes)) }
            VaultHealthCheck.Result.Unrecoverable ->
                _uiState.update { it.copy(setup = SetupState.Ready, unlockError = UnlockError.FILE_MISSING) }
        }
    }

    private fun keyDir(context: Context) = File(context.applicationContext.filesDir, "keys")

    // ---- 解鎖 ----

    fun unlockWithPassword(masterPassword: CharArray) {
        viewModelScope.launch { attemptUnlock(masterPassword) }
    }

    fun unlockWithBiometric(biometric: BiometricUnlock) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, unlockError = null) }
            val passwordBytes = try {
                biometric.unlock()
            } catch (e: KeyPermanentlyInvalidException) {
                _uiState.update { it.copy(biometricEnabled = false, unlockError = UnlockError.BIOMETRIC_INVALIDATED) }
                null
            }
            if (passwordBytes == null) {
                _uiState.update { it.copy(isUnlocking = false) }
                return@launch
            }
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
        _uiState.update { it.copy(isUnlocking = true) }
        try {
            val loaded = withContext(unlockDispatcher) { repo.load() }
            val vault = withContext(unlockDispatcher) { Vault.open(loaded.bytes, masterPassword) }
            // 把每一筆記錄從 KDBX 的 XML 解出來，要對 DOM 跑一連串 XPath 查詢，記錄一多就是
            // 好幾百毫秒。之前放在下面 _uiState.update 裡、在主執行緒做，Argon2 跑完那一刻
            // 轉圈會整個卡住、換到清單也頓一下。跟解密一樣在背景做完，主執行緒只負責交棒。
            val (entries, hintSnippets) = withContext(unlockDispatcher) { vault.entries() to vault.hintSnippets() }
            loadedStamp = loaded.stamp
            cacheMasterPassword(masterPassword)
            session.unlock(vault)
            _uiState.update {
                it.copy(
                    setup = SetupState.Ready,
                    lockState = LockState.Unlocked,
                    entries = entries,
                    hintSnippets = hintSnippets,
                    unlockError = null,
                    isUnlocking = false
                )
            }
        } catch (e: WrongPasswordException) {
            _uiState.update { it.copy(unlockError = UnlockError.WRONG_PASSWORD, isUnlocking = false) }
        } catch (e: UnsupportedVaultFormatException) {
            _uiState.update { it.copy(unlockError = UnlockError.UNSUPPORTED_FORMAT, isUnlocking = false) }
        } catch (e: CancellationException) {
            // CancellationException 是 Exception 的子類別，若被下面那個 catch (e: Exception)
            // 吃掉，viewModelScope 在 onCleared() 時取消協程就不會真的中止這個函式，
            // 違反結構化並行的合作式取消合約。必須排在通用 catch 之前先攔截、重新丟出，
            // 讓取消訊號正常往外傳播；finally 仍會執行，密碼還是會被清空。
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(unlockError = UnlockError.FILE_MISSING, isUnlocking = false) }
        } finally {
            masterPassword.wipe()
        }
    }

    // ---- 清單 / CRUD ----

    fun saveEntry(entry: Entry) {
        val vault = session.withVault { it }
        vault.upsert(entry)
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch { persist(vault) }
    }

    fun deleteEntry(id: UUID) {
        val vault = session.withVault { it }
        vault.delete(id)
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch { persist(vault) }
    }

    /**
     * 存一句常用提示詞，供之後新增/編輯記錄時直接選用。空白字串、已經存過的
     * 字串都直接忽略——不需要回報錯誤，這不是使用者會在意結果的操作。
     *
     * 跟 [saveEntry] 一樣「先同步改記憶體裡的 Vault、uiState 立刻反映，再非同步
     * 寫檔」：常用提示詞清單本身不參與 [SaveMessage]/衝突偵測（不是一筆
     * entry，沒有對應的畫面在等 saveMessage），因此不呼叫
     * `_uiState.update { it.copy(saveMessage = null) }`。
     */
    fun addHintSnippet(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val vault = session.withVault { it }
        val current = vault.hintSnippets()
        if (trimmed in current) return
        vault.setHintSnippets(current + trimmed)
        _uiState.update { it.copy(hintSnippets = vault.hintSnippets()) }
        viewModelScope.launch { persist(vault) }
    }

    fun removeHintSnippet(text: String) {
        val vault = session.withVault { it }
        vault.setHintSnippets(vault.hintSnippets() - text)
        _uiState.update { it.copy(hintSnippets = vault.hintSnippets()) }
        viewModelScope.launch { persist(vault) }
    }

    /**
     * **主密碼取的是拷貝，不是 [masterPasswordCache] 那個陣列本身。**
     * `vault.serialize()` 在 [unlockDispatcher] 上跑約 2.2 秒的 Argon2，這段期間
     * [lock] 可能在主執行緒被呼叫（`AutoLock` 的螢幕關閉/切到背景是零寬限期
     * 觸發，按下「儲存」後隨手關螢幕就會發生），而 [lock] 會就地把
     * [masterPasswordCache] 的每個字元就地覆寫成 NUL。若這裡拿的是同一個陣列，
     * KDF 就會在中途改用一把被清成半空的密碼繼續推導，寫出一個誰也打不開的
     * KDBX——永久資料遺失。改成一進來就拷貝一份自己擁有的，[lock] 清的是它自己
     * 那份，這邊的運算完整跑完，最後在 finally 裡清掉自己的拷貝。
     *
     * 例外處理與三條前置條件不成立的路徑都必須回報 [SaveMessage.Error]：
     * `saveEntry`/`deleteEntry` 現在會先把 saveMessage 清成 null，靜默 return
     * 會讓 `EditScreen` 永遠等不到任何訊息，看起來就是卡住。
     */
    private suspend fun persist(vault: Vault) {
        val password = masterPasswordCache?.copyOf()
        if (password == null) {
            _uiState.update { it.copy(saveMessage = SaveMessage.Error) }
            return
        }
        try {
            val repo = repository
            if (repo == null) {
                _uiState.update { it.copy(saveMessage = SaveMessage.Error) }
                return
            }
            persistMutex.withLock {
                val stamp = loadedStamp
                if (stamp == null) {
                    _uiState.update { it.copy(saveMessage = SaveMessage.Error) }
                    return@withLock
                }
                val bytes = withContext(unlockDispatcher) { vault.serialize(password) }
                when (val result = withContext(unlockDispatcher) { repo.save(bytes, stamp) }) {
                    is SaveResult.Saved -> {
                        loadedStamp = result.newStamp
                        // 同 attemptUnlock：解出所有記錄放在背景，「已儲存」的打勾動畫才不會卡一下。
                        val entries = withContext(unlockDispatcher) { vault.entries() }
                        _uiState.update { it.copy(saveMessage = SaveMessage.Saved, entries = entries) }
                    }
                    is SaveResult.Conflict -> {
                        val entries = withContext(unlockDispatcher) { vault.entries() }
                        _uiState.update {
                            it.copy(saveMessage = SaveMessage.Conflict(result.conflictFileName), entries = entries)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 同 attemptUnlock：取消訊號必須先攔下來重丟，不能被下面的通用 catch 吃掉。
            throw e
        } catch (e: Exception) {
            // VaultRepository.save() 自己的 KDoc 就寫明：檔案在 session 中途被外部
            // 刪掉時它會直接擲例外。沒有這個 catch 的話例外會從 viewModelScope
            // 的協程逃出去，直接讓 app 崩潰。
            _uiState.update { it.copy(saveMessage = SaveMessage.Error) }
        } finally {
            password.wipe()
        }
    }

    // ---- 設定 ----

    /**
     * 前提：`masterPasswordCache` 非 null，代表本次 session 已經用主密碼
     * 或生體辨識成功解鎖過一次——規格 §4.6「必須先以主密碼手動解鎖一次，
     * 才能啟用生體辨識」在這裡是自動滿足的，因為兩條解鎖路徑（Task 6）
     * 都會先寫入這個欄位。
     *
     * `BiometricUnlock` 本身無法在 JVM 環境下實際觸發 `BiometricPrompt`，
     * 因此本函式的真實行為只在真機/模擬器上驗證（見 docs/manual-verification.md）。
     */
    fun enrollBiometric(biometric: BiometricUnlock) {
        // 與 persist() 同一個理由：拷貝一份自己擁有的，別讓中途的 lock() 把
        // 這條協程正在轉成 UTF-8 位元組的那個陣列清空（會把一把半空的密碼
        // 包進生體辨識金鑰，之後怎麼刷都解不開）。
        val password = masterPasswordCache?.copyOf() ?: return
        viewModelScope.launch {
            try {
                val bytes = password.toUtf8Bytes()
                val success = try {
                    biometric.enroll(bytes)
                } finally {
                    bytes.wipe()
                }
                if (success) _uiState.update { it.copy(biometricEnabled = true) }
            } finally {
                password.wipe()
            }
        }
    }

    fun disableBiometric(context: Context) {
        KeystoreKeys().deleteKey()
        WrappedKeyStore(keyDir(context)).clear()
        _uiState.update { it.copy(biometricEnabled = false) }
    }

    fun setAutoLockTimeout(millis: Long) {
        _uiState.update { it.copy(autoLockPolicy = it.autoLockPolicy.copy(idleTimeoutMillis = millis)) }
    }

    fun setBackgroundGrace(millis: Long) {
        _uiState.update { it.copy(autoLockPolicy = it.autoLockPolicy.copy(backgroundGraceMillis = millis)) }
    }

    /**
     * 若已啟用生體辨識，[biometric] 不可為 null——重新呼叫 `enroll()` 會用
     * 新密碼蓋掉包裝檔（`BiometricUnlock.enroll()` 內部一開始就 `store.clear()`），
     * 這是規格 §4.6 點名「忘了同步更新會導致生體辨識解鎖用舊密碼失敗」
     * 這個風險最高步驟的處理方式：不是額外清除，而是靠重新 enroll 自然取代。
     */
    fun changeMasterPassword(newPassword: CharArray, biometric: BiometricUnlock?) {
        // 同 Task 7 的 persist()：vault.serialize() 是同一顆 Argon2 KDF，必須離開呼叫端
        // 執行緒，走同一個 unlockDispatcher seam，不能同步跑在呼叫端（之後 Task 15 的
        // 設定畫面會直接呼叫這個函式）。用同一個 persistMutex：若這個呼叫與
        // saveEntry/deleteEntry 幾乎同時發生，兩者都在動同一份 loadedStamp 與同一個檔案，
        // 必須互斥，理由與 Task 7 的 persist() 完全相同——stamp 的讀取也同樣搬到鎖裡面。
        val vault = try {
            session.withVault { it }
        } catch (e: Exception) {
            newPassword.wipe()
            throw e
        }
        _uiState.update { it.copy(saveMessage = null) }
        viewModelScope.launch {
            try {
                val repo = repository ?: return@launch
                persistMutex.withLock {
                    val stamp = loadedStamp ?: return@withLock
                    val bytes = withContext(unlockDispatcher) { vault.serialize(newPassword) }
                    when (val result = withContext(unlockDispatcher) { repo.save(bytes, stamp) }) {
                        is SaveResult.Saved -> {
                            loadedStamp = result.newStamp
                            cacheMasterPassword(newPassword)
                            if (uiState.value.biometricEnabled && biometric != null) {
                                enrollBiometric(biometric)
                            }
                            _uiState.update { it.copy(saveMessage = SaveMessage.Saved) }
                        }
                        is SaveResult.Conflict -> {
                            _uiState.update { it.copy(saveMessage = SaveMessage.Conflict(result.conflictFileName)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                newPassword.wipe()
            }
        }
    }

    fun lock() {
        resetToLockedState()
    }

    /**
     * 「即將換到另一個檔案/資料夾」的重設：整組回到上鎖狀態，外加清掉
     * [loadedStamp]。見 [onFolderChosen] 的 KDoc。
     *
     * `internal` 而非 `private`，是因為 [onFolderChosen] 本體被 SAF 綁死、
     * 純 JVM 測試碰不到，但這段重設正是修的那個 bug 的本體，必須測得到。
     */
    internal fun resetForNewLocation() {
        resetToLockedState()
        loadedStamp = null
    }

    /**
     * [lock] 的實作，另外供 [resetForNewLocation] 重用。刻意不清 [loadedStamp]：
     * 一般的 lock/unlock 來回面對的還是同一個檔案，那份指紋仍然有效；
     * 只有換資料夾/換檔案才會讓它失效，由 [resetForNewLocation] 額外清掉。
     */
    private fun resetToLockedState() {
        session.lock()
        masterPasswordCache?.wipe()
        masterPasswordCache = null
        _uiState.update { it.copy(lockState = LockState.Locked, entries = emptyList(), isUnlocking = false) }
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

    internal companion object {
        /** 規格 §4.2：連 [KdfParams.FLOOR] 都跑超過預算時要告知使用者。 */
        internal const val SLOW_KDF_NOTICE =
            "這台裝置跑加密運算較慢，之後每次解鎖大約需要數秒，屬正常現象"
    }
}
