package com.hintvault.session

/** 規格 §6 的上鎖觸發條件。 */
data class AutoLockPolicy(
    val lockOnBackground: Boolean = true,
    /** 進入背景後的寬限期。0 代表立即上鎖。 */
    val backgroundGraceMillis: Long = 0,
    val idleTimeoutMillis: Long = 5 * 60 * 1000
)

/**
 * 判斷何時該上鎖。
 *
 * 時鐘以參數注入，讓時間相關的行為可用假時鐘窮舉測試，
 * 不必在測試裡真的等五分鐘。
 */
class AutoLock(
    private val policy: AutoLockPolicy,
    private val now: () -> Long,
    private val onLock: () -> Unit
) {

    private var lastInteractionAt: Long = now()
    private var backgroundedAt: Long? = null
    private var alreadyLocked = false

    fun onUserInteraction() {
        lastInteractionAt = now()
        alreadyLocked = false
    }

    fun onEnterBackground() {
        if (!policy.lockOnBackground) return
        if (policy.backgroundGraceMillis <= 0) {
            fire()
        } else {
            backgroundedAt = now()
        }
    }

    fun onReturnToForeground() {
        backgroundedAt = null
        onUserInteraction()
    }

    /** 螢幕關閉一律立即上鎖，不套用寬限期。 */
    fun onScreenOff() = fire()

    /** 由前景的計時器定期呼叫，用於檢查逾時。 */
    fun tick() {
        val current = now()
        backgroundedAt?.let {
            if (current - it > policy.backgroundGraceMillis) { fire(); return }
        }
        if (current - lastInteractionAt > policy.idleTimeoutMillis) fire()
    }

    private fun fire() {
        if (alreadyLocked) return
        alreadyLocked = true
        onLock()
    }
}
