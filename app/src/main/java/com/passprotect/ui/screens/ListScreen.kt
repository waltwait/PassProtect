package com.passprotect.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VpnKeyOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.VaultViewModel
import com.passprotect.vault.Entry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val categories = remember(entries) {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val filtered = remember(entries, query, selectedCategory) {
        entries
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .filter { selectedCategory == null || it.category == selectedCategory }
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
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (categories.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("全部") }
                    )
                    categories.forEach { name ->
                        FilterChip(
                            selected = selectedCategory == name,
                            onClick = { selectedCategory = if (selectedCategory == name) null else name },
                            label = { Text(name) }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                EmptyEntriesMessage(hasFilter = query.isNotBlank() || selectedCategory != null)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered, key = { it.id.toString() }) { entry ->
                        SwipeToDeleteRow(
                            onDelete = { viewModel.deleteEntry(entry.id) }
                        ) {
                            ListItem(
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(entry.modifiedAt.toString()) },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.VpnKey,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.clickable { navController.navigate("detail/${entry.id}") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyEntriesMessage(hasFilter: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.VpnKeyOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            if (hasFilter) "沒有符合條件的提示" else "還沒有任何提示，點右下角新增第一筆",
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 滑到底（EndToStart，由右往左）直接刪除——跟 DetailScreen 的兩步驟確認刪除
 * 是兩條並存的路徑，不衝突：手勢是給熟悉清單操作的使用者的捷徑，真的誤觸也
 * 還有 KDBX 備份／同步衝突檔案能救回來，不是唯一防線。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            true
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.animateContentSize(),
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        content = { content() }
    )
}
