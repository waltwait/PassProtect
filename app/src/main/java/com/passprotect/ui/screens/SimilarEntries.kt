package com.passprotect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.theme.PassProtectTheme
import com.passprotect.vault.Entry

private val SchemeAndWww = Regex("^(https?://)?(www\\.)?")
private val DomainSuffix = Regex("(\\.(com|net|org|edu|gov|io|app|dev|co|me|info))?(\\.[a-z]{2})?$")
private val Whitespace = Regex("\\s+")

/**
 * 比對用的標題：不分大小寫、去掉空白；像網址的（沒有空白、有點）再去掉 `https://`、
 * `www.`、路徑跟 `.com`／`.com.tw` 這類尾巴——「GitHub」「github.com」
 * 「https://www.github.com/login」都變成「github」。
 */
private fun similarityKey(title: String): String {
    var key = title.trim().lowercase()
    if (key.isNotEmpty() && !key.contains(Whitespace) && key.contains('.')) {
        key = key.replace(SchemeAndWww, "")
        val beforePath = key.substringBefore('/')
        if (beforePath.contains('.')) key = beforePath
        key = key.replace(DomainSuffix, "")
    }
    return key.replace(Whitespace, "")
}

/**
 * 新增時打的標題，跟哪幾筆既有記錄可能是同一個網站／服務：比對用的標題互相包含就算，
 * 完全一樣的排前面，最多 [limit] 筆。少於兩個字不比——一個字幾乎什麼都包含。
 */
internal fun similarEntries(title: String, entries: List<Entry>, limit: Int = 3): List<Entry> {
    val key = similarityKey(title)
    if (key.length < 2) return emptyList()
    return entries
        .mapNotNull { entry ->
            val other = similarityKey(entry.title)
            when {
                other.length < 2 -> null
                other == key -> entry to 0
                other.contains(key) || key.contains(other) -> entry to 1
                else -> null
            }
        }
        .sortedWith(compareBy({ it.second }, { it.first.title.lowercase() }))
        .take(limit)
        .map { it.first }
}

/** 新增頁標題下方「已經有類似的」：點一筆就改成在那筆加一組帳號。 */
@Composable
internal fun SimilarEntriesPanel(similar: List<Entry>, onPick: (Entry) -> Unit, modifier: Modifier = Modifier) {
    // 收起來的動畫途中還要畫剛剛那幾筆，不是先變空再縮。
    val lastShown = remember { mutableListOf<Entry>() }
    val shown = similar.ifEmpty { lastShown.toList() }
    SideEffect {
        if (similar.isNotEmpty()) {
            lastShown.clear()
            lastShown.addAll(similar)
        }
    }
    val sizeSpring = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold
    )
    AnimatedVisibility(
        visible = similar.isNotEmpty(),
        enter = expandVertically(sizeSpring) + fadeIn(tween(160, delayMillis = 60)),
        exit = shrinkVertically(sizeSpring) + fadeOut(tween(90)),
        modifier = modifier
    ) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "已經有類似的",
                style = MaterialTheme.typography.labelSmall,
                color = PassProtectTheme.extraColors.label,
                modifier = Modifier.padding(start = 4.dp)
            )
            Column {
                shown.forEachIndexed { index, entry ->
                    GroupedRow(GroupPosition.of(index, shown.size), onClick = { onPick(entry) }) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            listOf(entry.category, "${entry.accounts.size} 組").filter { it.isNotBlank() }.joinToString("・"),
                            style = MaterialTheme.typography.labelMedium,
                            color = PassProtectTheme.extraColors.label,
                            maxLines = 1
                        )
                        Icon(
                            Icons.Outlined.PersonAdd,
                            contentDescription = "在「${entry.title}」加一組帳號",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
