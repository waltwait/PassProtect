package com.passprotect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passprotect.ui.SetupState
import com.passprotect.ui.VaultUiState
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.PrimaryWideButton
import com.passprotect.ui.theme.PassProtectTheme

internal enum class PasswordMatch { Empty, Match, Mismatch }

/** 確認欄還沒打字時是 [PasswordMatch.Empty]，畫面上什麼都不顯示。 */
internal fun passwordMatch(password: String, confirm: String): PasswordMatch = when {
    confirm.isEmpty() -> PasswordMatch.Empty
    password == confirm -> PasswordMatch.Match
    else -> PasswordMatch.Mismatch
}

/**
 * 收整個 [VaultUiState] 而非只收 [SetupState]：建立新資料庫是一段數秒的
 * Argon2 運算，畫面需要 `isCreatingVault`（進度圈）與 `setupError`（失敗訊息），
 * 這兩個欄位不屬於 [SetupState] 這個狀態機本身。
 */
@Composable
fun SetupScreen(uiState: VaultUiState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }

    when (val state = uiState.setup) {
        SetupState.CheckingFolder -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        SetupState.NeedsFolderPicker -> SetupPage(
            icon = Icons.Outlined.CreateNewFolder,
            title = "選擇同步資料夾",
            description = "例如 Syncthing、Nextcloud 的資料夾"
        ) {
            Spacer(Modifier.height(32.dp))
            PrimaryWideButton("選擇資料夾", onClick = { pickFolder.launch(null) })
        }

        is SetupState.NeedsFileChoice -> SetupPage(
            icon = Icons.Outlined.FolderOpen,
            title = "選擇資料庫檔案",
            description = "這個資料夾裡找到多個資料庫檔案，請選擇一個"
        ) {
            Spacer(Modifier.height(24.dp))
            state.candidateNames.forEachIndexed { index, name ->
                GroupedRow(
                    GroupPosition.of(index, state.candidateNames.size),
                    onClick = { viewModel.onFileChosen(context, name) }
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SetupState.NeedsNewVaultPassword -> {
            var password by remember { mutableStateOf("") }
            var confirm by remember { mutableStateOf("") }
            SetupPage(
                icon = Icons.Outlined.Security,
                title = "建立新的資料庫",
                description = "這個資料夾裡還沒有資料庫。設定一組主密碼來建立；忘記主密碼就打不開資料庫，請記牢。",
                // 內容從上方排下來、整頁可捲動：鍵盤一彈出，imePadding 先把可用高度
                // 收小，聚焦的欄位會被自動捲到鍵盤上方。按鈕緊跟在欄位後面，不釘在
                // 螢幕最底——釘在最底的話鍵盤一開就被蓋住，得先收鍵盤才按得到。
                modifier = Modifier.imePadding()
            ) {
                uiState.setupError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                }
                Spacer(Modifier.height(32.dp))
                PassProtectField(
                    value = password, onValueChange = { password = it },
                    label = "主密碼", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                PassProtectField(
                    value = confirm, onValueChange = { confirm = it },
                    label = "確認主密碼", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                MatchHint(passwordMatch(password, confirm))
                Spacer(Modifier.height(32.dp))
                PrimaryWideButton(
                    "建立資料庫",
                    onClick = { viewModel.onNewVaultPasswordChosen(context, password.toCharArray()) },
                    enabled = !uiState.isCreatingVault && password.isNotEmpty() && password == confirm
                )
                // 與 UnlockScreen 的 isUnlocking 同理：建立資料庫要跑一次完整的
                // Argon2（實測 2.2-5.9 秒），沒有進度指示會被當成當機。
                AnimatedVisibility(
                    visible = uiState.isCreatingVault,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                Text(
                    "建立約需 3 到 6 秒，期間請勿離開",
                    style = MaterialTheme.typography.labelMedium,
                    color = PassProtectTheme.extraColors.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        }

        is SetupState.NeedsBackupDecision -> AlertDialog(
            onDismissRequest = {},
            title = { Text("偵測到資料庫檔案異常") },
            text = { Text("找不到資料庫檔案，或檔案本身已損毀。是否從最近一次的備份還原？") },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveBackupDecision(true) }) { Text("還原") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveBackupDecision(false) }) { Text("不還原") }
            }
        )

        SetupState.Ready -> {}
    }
}

/** 首次設定每一步的共同版型：圖示方塊、大標題、說明，下面接這一步自己的內容。 */
@Composable
private fun SetupPage(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 32.dp)
    ) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .size(56.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

/** 確認欄下方的即時提示；確認欄還沒打字時什麼都不畫。 */
@Composable
private fun MatchHint(match: PasswordMatch) {
    if (match == PasswordMatch.Empty) return
    val ok = match == PasswordMatch.Match
    val color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Row(
        Modifier.padding(start = 2.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(if (ok) "兩次輸入一致" else "兩次輸入不一致", style = MaterialTheme.typography.bodySmall, color = color)
    }
}
