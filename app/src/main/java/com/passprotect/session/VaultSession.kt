package com.passprotect.session

import com.passprotect.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * 記憶體中已解密 [Vault] 的生命週期。
 *
 * 原本持有一把抽象的 vault key（`ByteArray`），規格 §2 的安全政策變更後
 * 不再對應任何實際存在的東西——真正敏感、真正有用的是解密好的 [Vault] 物件
 * 本身。[Vault] 不是 `ByteArray`，沒有 `wipe()` 可呼叫；[lock] 因此改為
 * **捨棄參照讓 GC 回收**，而不是像舊版那樣位元覆寫。這是刻意的行為差異，
 * 不是遺漏。
 *
 * **僅限單一執行緒（主執行緒）呼叫。** 內部沒有任何同步機制——[vault]
 * 是普通的 `var`。若另一執行緒當下正在 [withVault] 的區塊內使用，[lock]
 * 會在它背後把參照清成 null，呼叫端不會收到任何訊號。之後任何要接上背景
 * 計時器（例如 `AutoLock.tick()`）觸發 [lock] 的地方，都必須先切回主執行緒
 * 再呼叫，不能直接從背景執行緒呼叫。
 */
class VaultSession {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var vault: Vault? = null

    fun unlock(vault: Vault) {
        this.vault = vault
        _state.value = LockState.Unlocked
    }

    /**
     * 在已解鎖狀態下取用 [Vault]。刻意不提供 getter，理由與舊版相同：
     * 避免呼叫端把參照複製出去後我們失去對它生命週期的掌握。
     */
    fun <R> withVault(block: (Vault) -> R): R {
        val v = vault ?: throw IllegalStateException("保險庫已上鎖")
        return block(v)
    }

    fun lock() {
        vault = null
        _state.value = LockState.Locked
    }
}
