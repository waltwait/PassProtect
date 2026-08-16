package com.passprotect.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.vault.Entry
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(entryId: UUID, accountId: UUID, entries: List<Entry>, navController: NavController) {
    val entry = entries.firstOrNull { it.id == entryId }
    val account = entry?.accounts?.firstOrNull { it.id == accountId }
    val history = account?.history?.sortedByDescending { it.at }.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: "歷史紀錄") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Text("沒有歷史紀錄", modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(history) { h ->
                    ListItem(
                        headlineContent = { Text(h.hint) },
                        supportingContent = { Text(h.at.toString()) },
                        leadingContent = { Icon(Icons.Filled.History, contentDescription = null) }
                    )
                }
            }
        }
    }
}
