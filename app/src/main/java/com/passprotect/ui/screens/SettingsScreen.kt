package com.passprotect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.passprotect.crypto.BiometricUnlock
import com.passprotect.crypto.WrappedKeyStore
import com.passprotect.ui.VaultUiState
import com.passprotect.ui.VaultViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(uiState: VaultUiState, navController: NavController, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderChosen(context, uri)
    }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newSnippet by remember { mutableStateOf("") }

    fun currentBiometric(): BiometricUnlock? =
        activity?.let { BiometricUnlock(it, store = WrappedKeyStore(File(context.applicationContext.filesDir, "keys"))) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSectionLabel(Icons.Filled.Folder, "資料庫位置")
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("重選資料夾") }

            HorizontalDivider()

            SettingsSectionLabel(Icons.Filled.TextFields, "常用提示詞")
            Text(
                "存一些常用的字詞，編輯提示時可以直接點選組合，不用每次重打",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newSnippet,
                    onValueChange = { newSnippet = it },
                    label = { Text("新增提示詞") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        viewModel.addHintSnippet(newSnippet)
                        newSnippet = ""
                    },
                    enabled = newSnippet.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增")
                }
            }
            if (uiState.hintSnippets.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.hintSnippets.forEach { snippet ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.removeHintSnippet(snippet) },
                            label = { Text(snippet) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "刪除「$snippet」",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            SettingsSectionLabel(Icons.Filled.Fingerprint, "生體辨識解鎖")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("開啟後可用指紋/臉部辨識取代主密碼")
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

            HorizontalDivider()

            SettingsSectionLabel(Icons.Filled.Timer, "自動上鎖逾時")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1L to "1 分鐘", 5L to "5 分鐘", 15L to "15 分鐘").forEach { (minutes, label) ->
                    val millis = minutes * 60_000L
                    val active = uiState.autoLockPolicy.idleTimeoutMillis == millis
                    if (active) {
                        Button(onClick = { viewModel.setAutoLockTimeout(millis) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.setAutoLockTimeout(millis) }) { Text(label) }
                    }
                }
            }

            HorizontalDivider()

            SettingsSectionLabel(Icons.Filled.Password, "變更主密碼")
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it },
                label = { Text("新主密碼") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it },
                label = { Text("確認新主密碼") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
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

@Composable
private fun SettingsSectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
