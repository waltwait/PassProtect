package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hintvault.ui.SaveMessage
import com.hintvault.ui.VaultViewModel
import com.hintvault.vault.Entry
import com.hintvault.vault.HistoricalHint
import java.time.Instant
import java.util.UUID

/**
 * 組出要存檔的 [Entry]，並在這裡（也只有這裡）決定要不要往 [Entry.history] 添一筆。
 *
 * 規則：只有「修改既有記錄」且「提示真的變了」才把**舊的**提示存成
 * [HistoricalHint]——那正是 `HistoryScreen` 要救援的東西（改了提示，但某台裝置
 * 還在用舊密碼）。只改標題、什麼都沒改、或新增記錄都不寫歷史，否則歷史清單會被
 * 一堆「跟現在一模一樣」的雜訊塞滿，反而看不出真正變過幾次。
 * `HistoryScreen` 顯示時自己會依 `at` 由新到舊排序，所以這裡前插後插都可以，
 * 選前插只是讓底層順序也跟顯示順序一致。
 *
 * 從 composable 的 onClick 抽成獨立函式，是為了讓這條規則能在 JVM 單元測試裡
 * 直接驗證——Compose 的畫面本身只有真機 instrumented 測試碰得到。
 *
 * [now] 可注入，測試才能斷言時間戳；[Entry.modifiedAt] 則怎麼傳都會被底層函式庫
 * 在寫入時蓋掉（見 `Entry` 的 KDoc），這裡照舊傳現在時間即可。
 */
internal fun entryToSave(
    existing: Entry?,
    title: String,
    hint: String,
    now: Instant = Instant.now()
): Entry {
    val history = when {
        existing == null -> emptyList()
        existing.hint != hint -> listOf(HistoricalHint(at = now, hint = existing.hint)) + existing.history
        else -> existing.history
    }
    return Entry(
        id = existing?.id ?: UUID.randomUUID(),
        title = title,
        hint = hint,
        modifiedAt = now,
        history = history
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(entryId: UUID?, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val existing = entryId?.let { id -> entries.firstOrNull { it.id == id } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var hint by remember { mutableStateOf(existing?.hint.orEmpty()) }
    val uiState by viewModel.uiState.collectAsState()
    var didAttemptSave by remember { mutableStateOf(false) }

    // 只在成功存檔時離開這個畫面；SaveMessage.Conflict 時必須留在原地，
    // 讓使用者看到規格 §4.4 要求的衝突訊息，不可靜默導覽走。
    LaunchedEffect(uiState.saveMessage) {
        if (didAttemptSave && uiState.saveMessage is SaveMessage.Saved) {
            navController.popBackStack()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "新增" else "編輯") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            if (didAttemptSave) {
                (uiState.saveMessage as? SaveMessage.Conflict)?.let {
                    Text("修改已存到另一個檔案（${it.conflictFileName}），同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併")
                }
                // 與 Conflict 同樣的處理方式：留在原地並說明，不可靜默 pop back stack。
                if (uiState.saveMessage == SaveMessage.Error) {
                    Text("存檔失敗，請再試一次")
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("標題") })
            OutlinedTextField(value = hint, onValueChange = { hint = it }, label = { Text("提示") })
            Button(
                onClick = {
                    didAttemptSave = true
                    viewModel.saveEntry(entryToSave(existing, title, hint))
                },
                enabled = title.isNotBlank()
            ) { Text("儲存") }
        }
    }
}
