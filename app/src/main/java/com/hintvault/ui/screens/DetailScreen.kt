package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.hintvault.ui.VaultViewModel
import com.hintvault.vault.Entry
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(entryId: UUID, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val entry = entries.firstOrNull { it.id == entryId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (entry == null) {
        Text("找不到這筆記錄，可能已被刪除")
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text(entry.title) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text(entry.hint)
            Text("最後修改：${entry.modifiedAt}")
            Row {
                Button(onClick = { navController.navigate("edit/${entry.id}") }) { Text("編輯") }
                Button(onClick = { navController.navigate("history/${entry.id}") }) { Text("查看歷史") }
                Button(onClick = { showDeleteConfirm = true }) { Text("刪除") }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("確定刪除「${entry.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(entry.id)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}
