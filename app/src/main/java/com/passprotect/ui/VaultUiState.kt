package com.passprotect.ui

import com.passprotect.storage.BackupEntry
import com.passprotect.session.AutoLockPolicy
import com.passprotect.session.LockState
import com.passprotect.vault.Entry

/**
 * `VaultViewModel` 對外唯一的狀態快照。畫面永遠只讀這一個物件，
 * 不直接碰 `VaultSession`/`VaultRepository`——這樣 Compose 端不需要知道
 * 保險庫是不是已經解鎖過、SAF 授權還在不在，只要照著 [setup]/[lockState]
 * 的狀態機畫對應的畫面即可。
 */
data class VaultUiState(
    val setup: SetupState = SetupState.CheckingFolder,
    val lockState: LockState = LockState.Locked,
    val unlockError: UnlockError? = null,
    val isUnlocking: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val biometricEnabled: Boolean = false,
    val autoLockPolicy: AutoLockPolicy = AutoLockPolicy(),
    val saveMessage: SaveMessage? = null,
    /**
     * 「正在建立新資料庫」。刻意不共用 [isUnlocking]——那個欄位語意上專屬解鎖流程
     * （`UnlockScreen` 用它畫進度圈），而建立新資料庫是設定流程裡的另一段
     * 同樣長達數秒的 Argon2 運算，畫面不同、時機不同，共用會讓兩個流程
     * 的進度指示互相干擾。
     */
    val isCreatingVault: Boolean = false,
    /**
     * 設定流程（目前只有「建立新資料庫」）要告訴使用者的一句話。
     *
     * 兩種來源：建立失敗的錯誤訊息（由 `SetupScreen` 顯示），以及規格 §4.2
     * 要求的「KDF 校準後仍超出預算」慢速裝置提醒（建立成功後畫面已切到清單，
     * 因此由 `ListScreen` 顯示，可由使用者按「知道了」清掉）。刻意不重用
     * [unlockError]：那是 enum、語意是解鎖失敗的原因，這裡兩種都不是解鎖失敗。
     */
    val setupError: String? = null,
    /**
     * 使用者存過的常用提示詞（`Vault.hintSnippets()`）——編輯畫面用來讓使用者
     * 不用每次都重打同一種提示句型。跟 [entries] 一樣，解鎖成功、或
     * `addHintSnippet`/`removeHintSnippet` 改動後就同步更新。
     */
    val hintSnippets: List<String> = emptyList(),
    /** 本地備份資料夾的名稱；null 代表還沒設定或授權已失效（規格 2026-10-24-local-backup）。 */
    val backupFolderName: String? = null,
    /** 資料夾裡認得出來的備份，最新在前。 */
    val backups: List<BackupEntry> = emptyList(),
    /** 最近一次備份（解鎖、存檔、立即備份）失敗。不影響解鎖、存檔本身，只在設定頁顯示。 */
    val backupFailed: Boolean = false
)

/**
 * 解鎖失敗的原因，供畫面挑對應的錯誤訊息與復原動作。
 *
 * 不細分「密碼錯誤」與「檔案損毀/非 KDBX」——`Vault.open()` 本身就不細分
 * （見 `WrongPasswordException` 的 KDoc），這裡的 [WRONG_PASSWORD] 因此
 * 同時涵蓋兩者，畫面上呈現同一句「密碼錯誤或檔案損毀」。
 */
enum class UnlockError { WRONG_PASSWORD, UNSUPPORTED_FORMAT, FILE_MISSING, BIOMETRIC_INVALIDATED }

/** 首次啟動 / 選檔流程的狀態機，對應規格 §4 的流程圖。 */
sealed interface SetupState {
    /** 尚在判斷「先前是否已選過資料夾、授權是否還有效」。 */
    data object CheckingFolder : SetupState
    data object NeedsFolderPicker : SetupState
    data class NeedsFileChoice(val candidateNames: List<String>) : SetupState
    data object NeedsNewVaultPassword : SetupState
    /** 主檔案讀不到但備份健全，等使用者決定要不要還原。 */
    data class NeedsBackupDecision(val backupBytes: ByteArray) : SetupState
    data object Ready : SetupState
}

/** 存檔結果的一次性訊息，畫面顯示過後應清掉，避免旋轉螢幕後重複跳出。 */
sealed interface SaveMessage {
    data object Saved : SaveMessage
    data class Conflict(val conflictFileName: String) : SaveMessage
    /** 存檔失敗（例外，或前置條件不成立）。畫面必須留在原地並顯示訊息，不可靜默無反應。 */
    data object Error : SaveMessage
}
