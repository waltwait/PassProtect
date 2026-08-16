package com.passprotect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.passprotect.ui.SetupState
import com.passprotect.ui.VaultUiState
import com.passprotect.ui.VaultViewModel

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
        SetupState.CheckingFolder -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator() }

        SetupState.NeedsFolderPicker -> Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.CreateNewFolder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text("選擇同步資料夾（如 Syncthing、Nextcloud）")
            Button(onClick = { pickFolder.launch(null) }) { Text("選擇資料夾") }
        }

        is SetupState.NeedsFileChoice -> Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("這個資料夾裡找到多個資料庫檔案，請選擇一個")
            state.candidateNames.forEach { name ->
                TextButton(onClick = { viewModel.onFileChosen(context, name) }) { Text(name) }
            }
        }

        SetupState.NeedsNewVaultPassword -> {
            var password by remember { mutableStateOf("") }
            var confirm by remember { mutableStateOf("") }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Text("設定主密碼，建立新的資料庫")
                uiState.setupError?.let { Text(it) }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("主密碼") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    label = { Text("確認主密碼") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.onNewVaultPasswordChosen(context, password.toCharArray()) },
                    enabled = !uiState.isCreatingVault && password.isNotEmpty() && password == confirm
                ) { Text("建立") }
                // 與 UnlockScreen 的 isUnlocking 同理：建立資料庫要跑一次完整的
                // Argon2（實測 2.2-5.9 秒），沒有進度指示會被當成當機。
                if (uiState.isCreatingVault) {
                    CircularProgressIndicator()
                }
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
