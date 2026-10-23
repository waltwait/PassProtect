package com.passprotect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.passprotect.ui.components.HintSlip
import com.passprotect.ui.components.IconTopBar
import com.passprotect.ui.components.LargeTitleHeader
import com.passprotect.ui.components.SectionLabel
import com.passprotect.ui.format.replacedLabel
import com.passprotect.ui.theme.PassProtectTheme
import com.passprotect.vault.Entry
import com.passprotect.vault.HistoricalHint
import java.time.ZoneId
import java.util.UUID

/**
 * 一組帳號換過的舊提示。最上面先放「目前的提示」當對照——使用者來這裡通常是因為
 * 某台裝置還在用舊密碼，要一眼看出「現在的」跟「以前的」差在哪。
 */
@Composable
fun HistoryScreen(entryId: UUID, accountId: UUID, entries: List<Entry>, navController: NavController) {
    val entry = entries.firstOrNull { it.id == entryId }
    val account = entry?.accounts?.firstOrNull { it.id == accountId }
    val history = account?.history?.sortedByDescending { it.at }.orEmpty()
    val zone = ZoneId.systemDefault()

    Scaffold(topBar = { IconTopBar(onBack = { navController.popBackStack() }) }) { padding ->
        // 跟詳情頁同理：記錄或帳號可能已經不在了，留著返回鍵讓使用者回得去。
        if (entry == null || account == null) {
            Text(
                "找不到這筆記錄，可能已被刪除",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(padding)
                    .padding(20.dp)
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                LargeTitleHeader(
                    "舊提示",
                    subtitle = listOf(entry.title, account.username).filter { it.isNotBlank() }.joinToString("・")
                )
            }
            item(key = "current") {
                HintSlip(
                    account.hint,
                    label = "目前的提示",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)
                )
            }
            item(key = "earlier-label") { SectionLabel("更早之前") }
            if (history.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "這組帳號還沒有換過提示",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PassProtectTheme.extraColors.label,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
            itemsIndexed(history) { index, item ->
                TimelineItem(item, isLast = index == history.lastIndex, zone = zone)
            }
        }
    }
}

/** 時間軸的一點：左邊空心圓點＋往下接到下一點的細線，右邊是舊提示與換掉的日期。 */
@Composable
private fun TimelineItem(item: HistoricalHint, isLast: Boolean, zone: ZoneId) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            Modifier
                .width(12.dp)
                .fillMaxHeight()
                .padding(top = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .border(1.5.dp, PassProtectTheme.extraColors.label, CircleShape)
            )
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Column(
            Modifier.padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                item.hint,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                replacedLabel(item.at, zone),
                style = MaterialTheme.typography.labelMedium,
                color = PassProtectTheme.extraColors.label
            )
        }
    }
}
