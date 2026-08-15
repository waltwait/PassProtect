package com.hintvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.WrappedKeyStore
import com.hintvault.ui.VaultUiState
import com.hintvault.ui.VaultViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(uiState: VaultUiState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    fun currentBiometric(): BiometricUnlock? =
        activity?.let { BiometricUnlock(it, store = WrappedKeyStore(File(context.applicationContext.filesDir, "keys"))) }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text("資料庫位置")
            Button(onClick = { pickFolder.launch(null) }) { Text("重選資料夾") }

            Row {
                Text("生體辨識解鎖")
                Switch(
                    checked = uiState.biometricEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            currentBiometric()?.let { viewModel.enrollBiometric(it) }
                        } else {
                            viewModel.disableBiometric(context)
                        }
                    }
                )
            }

            Text("自動上鎖逾時")
            Row {
                listOf(1L to "1 分鐘", 5L to "5 分鐘", 15L to "15 分鐘").forEach { (minutes, label) ->
                    val millis = minutes * 60_000L
                    TextButton(onClick = { viewModel.setAutoLockTimeout(millis) }) {
                        Text(label + if (uiState.autoLockPolicy.idleTimeoutMillis == millis) "（使用中）" else "")
                    }
                }
            }

            Text("變更主密碼")
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it },
                label = { Text("新主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it },
                label = { Text("確認新主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    val biometric = if (uiState.biometricEnabled) currentBiometric() else null
                    viewModel.changeMasterPassword(newPassword.toCharArray(), biometric)
                    newPassword = ""
                    confirmPassword = ""
                },
                enabled = newPassword.isNotEmpty() && newPassword == confirmPassword
            ) { Text("變更") }
        }
    }
}
