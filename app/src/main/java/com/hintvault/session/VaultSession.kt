package com.hintvault.session

import com.hintvault.crypto.wipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * 記憶體中 vault key 的生命週期。
 *
 * [unlock] 會**接管**傳入的陣列——呼叫端不應再持有或重複使用它。
 * 上鎖時陣列會被歸零（規格 §4.5）。
 *
 * **僅限單一執行緒（主執行緒）呼叫。** 內部沒有任何同步機制——[vaultKey]
 * 是普通的 `var`，[lock] 會直接把陣列內容歸零。若另一執行緒當下正在
 * [withKey] 的區塊內使用同一個陣列，[lock] 會在它背後把內容清成 0，
 * 呼叫端不會收到任何訊號，會安靜地開始對著一把已清空的金鑰操作。
 * 之後任何要接上背景計時器（例如 `AutoLock.tick()`）觸發 [lock] 的地方，
 * 都必須先切回主執行緒再呼叫，不能直接從背景執行緒呼叫。
 */
class VaultSession {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var vaultKey: ByteArray? = null

    fun unlock(vaultKey: ByteArray) {
        this.vaultKey?.wipe()     // 舊金鑰絕不留在堆積上
        this.vaultKey = vaultKey
        _state.value = LockState.Unlocked
    }

    /**
     * 在已解鎖狀態下取用金鑰。刻意不提供 getter，
     * 避免呼叫端把金鑰複製出去後我們失去對它生命週期的掌握。
     */
    fun <R> withKey(block: (ByteArray) -> R): R {
        val key = vaultKey ?: throw IllegalStateException("保險庫已上鎖")
        return block(key)
    }

    fun lock() {
        vaultKey?.wipe()
        vaultKey = null
        _state.value = LockState.Locked
    }
}
