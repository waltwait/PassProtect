package com.passprotect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.PillButton
import kotlinx.coroutines.launch

/**
 * 編輯畫面點「分類」叫出的選單：最上面「未分類」（清空分類）、接著既有分類、最下面
 * 一個欄位新增分類。取代原本排在分類欄位下方那一整排 chip——那排 chip 是編輯畫面
 * 變長的主因之一，分類又不是每次編輯都會動的東西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPickerSheet(
    current: String,
    existing: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 先讓選單滑下去再交出結果，不然選單會在收合動畫跑完前就被拿掉、直接消失。
    fun pick(value: String) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onPick(value) }
    }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                "分類",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
            val options = listOf("") + existing
            options.forEachIndexed { index, name ->
                GroupedRow(
                    GroupPosition.of(index, options.size),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onClick = { pick(name) }
                ) {
                    Text(
                        name.ifBlank { "未分類" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (name == current) {
                        Icon(Icons.Rounded.Check, contentDescription = "目前的分類", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PassProtectField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "新分類",
                    modifier = Modifier.weight(1f)
                )
                PillButton(onClick = { pick(newName.trim()) }, enabled = newName.isNotBlank()) {
                    Text("確定")
                }
            }
        }
    }
}
