package com.passprotect.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsSetupOrUnlockScreen() {
        // 全新安裝、沒有任何持久化的資料夾選擇 → 應落在首次設定畫面。
        composeRule.onNodeWithText("選擇資料夾").assertExists()
    }
}
