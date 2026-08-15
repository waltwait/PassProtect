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
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(entryId: UUID?, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val existing = entryId?.let { id -> entries.firstOrNull { it.id == id } }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var hint by remember { mutableStateOf(existing?.hint.orEmpty()) }
    val uiState by viewModel.uiState.collectAsState()

    // 只在成功存檔時離開這個畫面；SaveMessage.Conflict 時必須留在原地，
    // 讓使用者看到規格 §4.4 要求的衝突訊息，不可靜默導覽走。
    LaunchedEffect(uiState.saveMessage) {
        if (uiState.saveMessage is SaveMessage.Saved) {
            navController.popBackStack()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "新增" else "編輯") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            (uiState.saveMessage as? SaveMessage.Conflict)?.let {
                Text("修改已存到另一個檔案（${it.conflictFileName}），同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併")
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("標題") })
            OutlinedTextField(value = hint, onValueChange = { hint = it }, label = { Text("提示") })
            Button(
                onClick = {
                    val entry = Entry(
                        id = existing?.id ?: UUID.randomUUID(),
                        title = title,
                        hint = hint,
                        modifiedAt = Instant.now(),
                        history = existing?.history ?: emptyList()
                    )
                    viewModel.saveEntry(entry)
                },
                enabled = title.isNotBlank()
            ) { Text("儲存") }
        }
    }
}
