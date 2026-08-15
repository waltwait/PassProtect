package com.hintvault.ui

import com.hintvault.session.AutoLockPolicy
import com.hintvault.session.LockState
import com.hintvault.vault.Entry

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
    val saveMessage: SaveMessage? = null
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
}
