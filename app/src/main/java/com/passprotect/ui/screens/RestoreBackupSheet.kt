package com.passprotect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.passprotect.storage.BackupEntry
import com.passprotect.ui.RestoreState
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.format.backupDateLabel
import com.passprotect.ui.theme.PassProtectTheme
import java.time.LocalDate

/**
 * 設定頁「從備份還原」：列出備份（最新在上），點一份輸入那份備份的主密碼再還原
 * （規格 2026-10-24-local-backup §2、§4）。還原成功就收起面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestoreBackupSheet(
    backups: List<BackupEntry>,
    restoreState: RestoreState,
    onRestore: (name: String, password: CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now() }
    var picked by remember { mutableStateOf<BackupEntry?>(null) }

    LaunchedEffect(restoreState) {
        if (restoreState == RestoreState.Done) {
            picked = null
            sheetState.hide()
            onDismiss()
        }
    }

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
                "從備份還原",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
            if (backups.isEmpty()) {
                Text(
                    "沒有備份",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PassProtectTheme.extraColors.label,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            backups.forEachIndexed { index, backup ->
                GroupedRow(
                    GroupPosition.of(index, backups.size),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onClick = { picked = backup }
                ) {
                    Text(
                        backupDateLabel(backup, today),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (backup.beforeRestore) {
                        Text("還原前", style = MaterialTheme.typography.labelMedium, color = PassProtectTheme.extraColors.label)
                    }
                }
            }
        }
    }

    picked?.let { backup ->
        RestorePasswordDialog(
            title = backupDateLabel(backup, today),
            restoreState = restoreState,
            onRestore = { password -> onRestore(backup.name, password) },
            onCancel = { picked = null }
        )
    }
}

@Composable
private fun RestorePasswordDialog(
    title: String,
    restoreState: RestoreState,
    onRestore: (CharArray) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    // 只顯示這個對話框裡按過「還原」之後的結果，不顯示上一份備份留下來的錯誤。
    var attempted by remember { mutableStateOf(false) }
    val restoring = restoreState == RestoreState.Restoring
    val error = when {
        !attempted -> null
        restoreState == RestoreState.WrongPassword -> "主密碼不對"
        restoreState == RestoreState.Failed -> "還原失敗"
        restoreState is RestoreState.Conflict ->
            "資料庫剛被別處改過，備份另存成 ${restoreState.conflictFileName}，請到 KeePassXC 手動合併"
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!restoring) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PassProtectField(
                    value = password,
                    onValueChange = { password = it },
                    label = "主密碼",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !restoring
                )
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (restoring) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    onClick = {
                        attempted = true
                        onRestore(password.toCharArray())
                    },
                    enabled = password.isNotEmpty()
                ) { Text("還原") }
            }
        },
        dismissButton = {
            if (!restoring) TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}
