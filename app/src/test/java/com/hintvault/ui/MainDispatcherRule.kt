package com.hintvault.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `viewModelScope` 內部用 `Dispatchers.Main`，JVM 單元測試環境沒有 Android
 * 的 Main looper，需要 `kotlinx-coroutines-test` 頂替一個。`UnconfinedTestDispatcher`
 * 讓 `viewModelScope.launch { ... }` 內的協程立即、同步執行到第一個掛起點，
 * 測試才能在呼叫完就直接斷言 `uiState.value`，不必手動 advance。
 *
 * 供 Task 6/7/8 的 `VaultViewModelTest` 共用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
