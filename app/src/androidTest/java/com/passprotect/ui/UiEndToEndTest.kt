package com.passprotect.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var syncDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        syncDir = File(context.cacheDir, "ui-e2e-${System.nanoTime()}").apply { mkdirs() }
    }

    /**
     * 這個測試不透過真正的 SAF 資料夾選擇器（那需要系統 UI 互動，Compose
     * 測試框架碰不到）。
     *
     * 原本設想是直接呼叫 `viewModel.onFolderChosen()`、用 `DocumentFile.fromFile()`
     * 包一個普通目錄餵給它，比照 `SafVaultFileTest`／核心引擎 `EndToEndTest` 的既有
     * 模式。實機跑過才發現這條路徑本身不通：`onFolderChosen()` 內部第一步呼叫
     * `ContentResolver.takePersistableUriPermission()`，這支 API 只認得「透過
     * `ACTION_OPEN_DOCUMENT_TREE` 系統選擇器實際跑過一輪、被授過權」的 `content://`
     * tree URI；餵一個自己用 `DocumentFile.fromFile()` 包出來的 `file://` URI 進去，
     * 系統會直接丟 `SecurityException`（"No persistable permission grants found"），
     * 把整個 process 弄掛掉。這不是本 app 的邏輯錯誤——真正的使用者流程一定是先
     * 走系統選擇器拿到已授權的 URI，`onFolderChosen()` 的前提永遠成立；只有測試
     * 想繞過系統 UI、直接偽造一個 URI 時才會踩到這個 SAF 專屬的限制。跟 BiometricPrompt
     * 一樣，這一小段（「使用者真的點過系統資料夾選擇器」本身）沒辦法在 Compose UI
     * 測試裡自動化，留給 docs/manual-verification.md。
     *
     * 因此改用反射直接灌 `directory` 私有欄位（效果等同於「已經选过资料夹」），
     * 再用既有的 `forceSetupStateForTest()` 把狀態機撥到 `NeedsNewVaultPassword`，
     * 兩者合起來跳過的只有「系統選擇器 + takePersistableUriPermission」這一小段。
     * 從這裡開始（建立新資料庫 → 新增/搜尋 → 鎖定/解鎖）走的全部是真正的
     * `VaultViewModel` 邏輯與真正透過 `ContentResolver` 對 `syncDir` 的讀寫
     * ——與 `SafVaultFileTest` 已經在實機驗證過的路徑相同，不是假的檔案系統。
     *
     * 另一個實機才會踩到的坑：`saveEntry()`／`unlockWithPassword()` 內部都會
     * 真的跑一次 Argon2d KDF（規格量測約 2.2 秒），發生在 `viewModelScope.launch`
     * 切到 `Dispatchers.Default` 的背景執行緒上——`composeRule.waitForIdle()`
     * 只保證 Compose 重組佇列與 Espresso 追蹤的 Main-thread 待辦事項都清空，
     * 不會等這種背景執行緒運算真的做完。原本每次存檔/解鎖後只呼叫一次
     * `waitForIdle()` 就接著斷言，在實機上量到的實際後果：斷言當下可能還沒真的
     * 存完、還沒真的導覽回清單頁——`onNodeWithText("github.com").assertExists()`
     * 甚至可能是「假陽性」通過（比對到的其實是 EditScreen 上還沒被清掉的
     * 「標題」輸入框內容，不是清單頁上的項目），下一步找 FAB 時才真正暴露出來
     * （`新增`／清單頁才有的節點根本還沒出現）。修法是存檔/解鎖後改用
     * `waitUntilExactlyOneExists()` 主動輪詢清單頁才有的節點（FAB 的
     * `新增` contentDescription），比 `waitForIdle()` 更貼近「真的等到背景
     * 運算與導覽都完成」。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun firstLaunchCreateEntryLockUnlockShowsEntry() {
        composeRule.waitForIdle()

        composeRule.activity.runOnUiThread {
            val vm = androidx.lifecycle.ViewModelProvider(composeRule.activity)[VaultViewModel::class.java]

            val directoryField = VaultViewModel::class.java.getDeclaredField("directory")
            directoryField.isAccessible = true
            directoryField.set(vm, DocumentFile.fromFile(syncDir))

            vm.forceSetupStateForTest(SetupState.NeedsNewVaultPassword)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("確認主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("建立").performClick()

        // 「建立」的存檔是同步呼叫（不是 viewModelScope.launch），Espresso 的
        // performClick() 本來就會等主執行緒忙完才回傳，所以這裡 waitForIdle()
        // 已經足夠，不需要額外的 waitUntilExactlyOneExists()。
        composeRule.waitForIdle()
        composeRule.waitUntilExactlyOneExists(hasContentDescription("新增"), timeoutMillis = 15_000)
        // 「新增」是 FAB 的 contentDescription，不是可見文字，要用
        // onNodeWithContentDescription 找；「標題」「提示」「儲存」在
        // EditScreen 上確實是可見文字（欄位標籤/按鈕文字），用 onNodeWithText。
        composeRule.onNodeWithContentDescription("新增").performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("標題").performTextInput("github.com")
        composeRule.onNodeWithText("提示").performTextInput("提示：貓的名字 + 生日")
        composeRule.onNodeWithText("儲存").performClick()

        // saveEntry() 真的跑一次 Argon2d KDF（背景執行緒，~2.2 秒）才會存檔完成、
        // 導覽回清單頁；見上方 KDoc。等清單頁才有的 FAB 出現，比 waitForIdle()
        // 更能保證真的等到存檔+導覽都完成，而不是撞見 EditScreen 上還沒清掉的
        // 輸入框文字造成的假陽性。
        composeRule.waitUntilExactlyOneExists(hasContentDescription("新增"), timeoutMillis = 15_000)
        composeRule.onNodeWithText("github.com").assertExists()

        // 規格 §5 要求搜尋過濾要有 Compose UI 測試覆蓋：新增第二筆，
        // 用搜尋框過濾，確認不相關的那筆被濾掉、相關的那筆還在。
        composeRule.onNodeWithContentDescription("新增").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("標題").performTextInput("example.com")
        composeRule.onNodeWithText("提示").performTextInput("提示：無關的另一筆")
        composeRule.onNodeWithText("儲存").performClick()
        composeRule.waitUntilExactlyOneExists(hasContentDescription("新增"), timeoutMillis = 15_000)

        composeRule.onNodeWithText("搜尋").performTextInput("github")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("github.com").assertExists()
        composeRule.onNodeWithText("example.com").assertDoesNotExist()

        // 「鎖定」同樣是 TopAppBar 上圖示按鈕的 contentDescription。鎖定本身
        // 是同步操作（VaultSession.lock() 沒有 KDF），waitForIdle() 已足夠。
        composeRule.onNodeWithContentDescription("鎖定").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("主密碼").performTextInput("correct horse battery staple")
        composeRule.onNodeWithText("解鎖").performClick()

        // unlockWithPassword() 同樣真的跑一次 Argon2d KDF 才會回到清單頁，
        // 理由同上，等清單頁的 FAB 出現再斷言。
        composeRule.waitUntilExactlyOneExists(hasContentDescription("新增"), timeoutMillis = 15_000)
        composeRule.onNodeWithText("github.com").assertExists()
    }
}
