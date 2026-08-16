package com.passprotect.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.passprotect.vault.Entry
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(entryId: UUID, entries: List<Entry>) {
    val entry = entries.firstOrNull { it.id == entryId }
    val history = entry?.history?.sortedByDescending { it.at }.orEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text(entry?.title ?: "歷史紀錄") }) }) { padding ->
        if (history.isEmpty()) {
            Text("沒有歷史紀錄", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(history) { h ->
                    ListItem(
                        headlineContent = { Text(h.hint) },
                        supportingContent = { Text(h.at.toString()) }
                    )
                }
            }
        }
    }
}
