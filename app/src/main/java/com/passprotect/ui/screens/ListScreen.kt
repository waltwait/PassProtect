package com.passprotect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.passprotect.ui.VaultViewModel
import com.passprotect.vault.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        entries
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedBy { it.title }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示保險庫") },
                actions = {
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = "鎖定")
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("edit_new") }) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 規格 §4.2 的慢速裝置提醒：建立資料庫成功後畫面已經切到這裡，
            // SetupScreen 早就不在畫面上了，所以這句話得在這裡講。
            uiState.setupError?.let { message ->
                Text(message)
                TextButton(onClick = { viewModel.clearSetupError() }) { Text("知道了") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜尋") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn {
                items(filtered, key = { it.id.toString() }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.modifiedAt.toString()) },
                        modifier = Modifier.clickable { navController.navigate("detail/${entry.id}") }
                    )
                }
            }
        }
    }
}
