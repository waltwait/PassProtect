package com.hintvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hintvault.ui.SetupState
import com.hintvault.ui.VaultViewModel

@Composable
fun SetupScreen(state: SetupState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }

    when (state) {
        SetupState.CheckingFolder -> CircularProgressIndicator()

        SetupState.NeedsFolderPicker -> Column(Modifier.padding(16.dp)) {
            Text("選擇要用來存放資料庫的同步資料夾（例如 Syncthing 或 Nextcloud 的同步目錄）")
            Button(onClick = { pickFolder.launch(null) }) { Text("選擇資料夾") }
        }

        is SetupState.NeedsFileChoice -> Column(Modifier.padding(16.dp)) {
            Text("這個資料夾裡找到多個資料庫檔案，請選擇一個")
            state.candidateNames.forEach { name ->
                TextButton(onClick = { viewModel.onFileChosen(context, name) }) { Text(name) }
            }
        }

        SetupState.NeedsNewVaultPassword -> {
            var password by remember { mutableStateOf("") }
            var confirm by remember { mutableStateOf("") }
            Column(Modifier.padding(16.dp)) {
                Text("這個資料夾裡沒有找到資料庫，設定主密碼建立一個新的（檔名固定為 vault.kdbx）")
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("主密碼") }, visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    label = { Text("確認主密碼") }, visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { viewModel.onNewVaultPasswordChosen(context, password.toCharArray()) },
                    enabled = password.isNotEmpty() && password == confirm
                ) { Text("建立") }
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
