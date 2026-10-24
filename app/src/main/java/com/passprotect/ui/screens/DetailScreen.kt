package com.passprotect.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.components.HintSlip
import com.passprotect.ui.components.IconTopBar
import com.passprotect.ui.components.LargeTitleHeader
import com.passprotect.ui.components.TonalButton
import com.passprotect.ui.format.historyCountLabel
import com.passprotect.ui.format.longDate
import com.passprotect.ui.theme.PassProtectTheme
import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import java.time.ZoneId
import java.util.UUID

/**
 * 編輯／刪除已經在 ListScreen 的左滑動作鈕做了（見 `SwipeActionsRow`），
 * 這裡不重複放，只留「查看歷史」——這是 ListScreen 滑動手勢沒有涵蓋的功能。
 */
@Composable
fun DetailScreen(entryId: UUID, entries: List<Entry>, navController: NavController) {
    val entry = entries.firstOrNull { it.id == entryId }
    val context = LocalContext.current

    Scaffold(topBar = { IconTopBar(onBack = { navController.popBackStack() }) }) { padding ->
        // 記錄可能在這個畫面開著時被刪掉（左滑刪除後立刻點進來、或同步檔被另一台
        // 裝置改掉）。仍然留著頂列的返回鍵，使用者才有路回去。
        if (entry == null) {
            Text(
                "找不到這筆記錄，可能已被刪除",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(20.dp)
            )
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            LargeTitleHeader(entry.title)

            // 分類／網址是這筆記錄的中繼資料，用小膠囊跟下面真正的內容（帳號卡片）
            // 分開，掃過去就知道「這兩個只是標籤，不是密語本身」。
            if (entry.category.isNotBlank() || entry.url.isNotBlank()) {
                FlowRow(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (entry.category.isNotBlank()) CategoryPill(entry.category)
                    if (entry.url.isNotBlank()) {
                        UrlPill(entry.url) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
                            }
                        }
                    }
                }
            }

            // 一個標題/網址底下可能有不只一組帳號（見 EditScreen）——每一組獨立
            // 一張卡片，提示本身用 HintSlip 大字等寬顯示，是這個畫面真正的重點。
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                entry.accounts.forEachIndexed { index, account ->
                    AccountCard(
                        account = account,
                        number = if (entry.accounts.size > 1) index + 1 else null,
                        onHistory = { navController.navigate("history/${entry.id}/${account.id}") }
                    )
                }
            }

            Text(
                "最後修改：${longDate(entry.modifiedAt, ZoneId.systemDefault())}",
                style = MaterialTheme.typography.labelMedium,
                color = PassProtectTheme.extraColors.label,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
            )
        }
    }
}

@Composable
private fun CategoryPill(category: String) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = PassProtectTheme.extraColors.label, modifier = Modifier.size(14.dp))
        Text(category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UrlPill(url: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccountCard(account: Account, number: Int?, onHistory: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (number != null || account.username.isNotBlank()) {
            // 「帳號 N」不參與分配寬度、永遠完整顯示；帳號名稱拿剩下的空間，太長就省略。
            // 反過來的話，一個長 email 會把「帳號 N」擠成一字一行的直條。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (number != null) {
                    Text(
                        "帳號 $number",
                        style = MaterialTheme.typography.labelSmall,
                        color = PassProtectTheme.extraColors.label
                    )
                }
                if (account.username.isNotBlank()) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            6.dp,
                            if (number != null) Alignment.End else Alignment.Start
                        )
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            account.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
        HintSlip(account.hint)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TonalButton("查看歷史", onClick = onHistory, icon = Icons.Rounded.History)
            Text(
                historyCountLabel(account.history.size),
                style = MaterialTheme.typography.labelMedium,
                color = PassProtectTheme.extraColors.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                textAlign = TextAlign.End
            )
        }
    }
}
