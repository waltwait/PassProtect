package com.passprotect.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.format.toDisplayString
import com.passprotect.vault.Entry
import java.util.UUID

/**
 * 編輯／刪除已經在 ListScreen 的左滑動作鈕做了（見 `SwipeActionsRow`），
 * 這裡不重複放，只留「查看歷史」——這是 ListScreen 滑動手勢沒有涵蓋的功能。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(entryId: UUID, entries: List<Entry>, navController: NavController) {
    val entry = entries.firstOrNull { it.id == entryId }
    val context = LocalContext.current

    if (entry == null) {
        Text("找不到這筆記錄，可能已被刪除")
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (entry.category.isNotBlank()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(entry.category)
                }
            }
            if (entry.url.isNotBlank()) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(entry.url, textDecoration = TextDecoration.Underline)
                }
            }
            HorizontalDivider()

            // 一個標題/網址底下可能有不只一組帳號（見 EditScreen），各自的
            // 帳號名稱、提示、歷史紀錄分開顯示。
            entry.accounts.forEachIndexed { index, account ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.accounts.size > 1) {
                        Text(
                            "帳號 ${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (account.username.isNotBlank()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(account.username)
                        }
                    }
                    Text(account.hint)
                    FilledTonalButton(onClick = { navController.navigate("history/${entry.id}/${account.id}") }) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("查看歷史")
                    }
                }
            }
            Text("最後修改：${entry.modifiedAt.toDisplayString()}")
        }
    }
}
