package com.passprotect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.passprotect.crypto.BiometricUnlock
import com.passprotect.crypto.WrappedKeyStore
import com.passprotect.ui.VaultUiState
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.components.IconTopBar
import com.passprotect.ui.components.LargeTitleHeader
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.PrimaryWideButton
import com.passprotect.ui.components.RemovableChip
import com.passprotect.ui.components.SectionLabel
import com.passprotect.ui.components.SegmentedControl
import com.passprotect.ui.components.SwitchRow
import com.passprotect.ui.theme.PassProtectTheme
import java.io.File

/**
 * 區段順序是「安全 → 常用提示詞 → 資料庫」：最常回來調的（自動上鎖、指紋）放最上面，
 * 換資料夾這種一年動不到一次的放最下面。
 */
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
    // 「變更主密碼」平常收成一列，點了才在原地展開兩個欄位——整頁因此不用捲動。
    var changingPassword by rememberSaveable { mutableStateOf(false) }

    fun currentBiometric(): BiometricUnlock? =
        activity?.let { BiometricUnlock(it, store = WrappedKeyStore(File(context.applicationContext.filesDir, "keys"))) }

    Scaffold(topBar = { IconTopBar(onBack = { navController.popBackStack() }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                // 「變更主密碼」展開後的兩個欄位在「安全」區段裡，常用提示詞的輸入框
                // 在更下面——沒有捲動的話鍵盤一彈出，這些欄位直接被蓋住又滑不到。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            LargeTitleHeader("設定")

            SectionLabel("安全")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    title = "生體辨識解鎖",
                    description = "用指紋或臉部辨識取代輸入主密碼",
                    checked = uiState.biometricEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            currentBiometric()?.let { viewModel.enrollBiometric(it) }
                        } else {
                            viewModel.disableBiometric(context)
                        }
                    },
                    position = GroupPosition.First
                )
                GroupedRow(GroupPosition.Middle) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("自動上鎖", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "沒有操作多久後自動上鎖",
                                style = MaterialTheme.typography.labelMedium,
                                color = PassProtectTheme.extraColors.label
                            )
                        }
                        SegmentedControl(
                            options = listOf(60_000L to "1 分鐘", 300_000L to "5 分鐘", 900_000L to "15 分鐘"),
                            selected = uiState.autoLockPolicy.idleTimeoutMillis,
                            onSelect = { viewModel.setAutoLockTimeout(it) }
                        )
                    }
                }
                GroupedRow(GroupPosition.Middle) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("離開 app 後", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "切到別的 app 多久後上鎖；關螢幕一律立刻上鎖",
                                style = MaterialTheme.typography.labelMedium,
                                color = PassProtectTheme.extraColors.label
                            )
                        }
                        SegmentedControl(
                            options = listOf(0L to "立即", 30_000L to "30 秒", 60_000L to "1 分鐘"),
                            selected = uiState.autoLockPolicy.backgroundGraceMillis,
                            onSelect = { viewModel.setBackgroundGrace(it) }
                        )
                    }
                }
                GroupedRow(GroupPosition.Last, onClick = { changingPassword = !changingPassword }) {
                    Text("變更主密碼", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val rotation by animateFloatAsState(if (changingPassword) 180f else 0f, label = "change-password-chevron")
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f),
                        modifier = Modifier.rotate(rotation)
                    )
                }
            }
            AnimatedVisibility(visible = changingPassword) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PassProtectField(
                        value = newPassword, onValueChange = { newPassword = it },
                        label = "新主密碼", visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PassProtectField(
                        value = confirmPassword, onValueChange = { confirmPassword = it },
                        label = "確認新主密碼", visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PrimaryWideButton(
                        "變更",
                        onClick = {
                            val biometric = if (uiState.biometricEnabled) currentBiometric() else null
                            viewModel.changeMasterPassword(newPassword.toCharArray(), biometric)
                            newPassword = ""
                            confirmPassword = ""
                            changingPassword = false
                        },
                        enabled = newPassword.isNotEmpty() && newPassword == confirmPassword
                    )
                }
            }

            SectionLabel("常用提示詞")
            val cardShape = MaterialTheme.shapes.medium
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "存一些常用的字詞，編輯提示時可以直接點選組合，不用每次重打",
                    style = MaterialTheme.typography.labelMedium,
                    color = PassProtectTheme.extraColors.label
                )
                PassProtectField(
                    value = newSnippet,
                    onValueChange = { newSnippet = it },
                    label = "新增提示詞",
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                viewModel.addHintSnippet(newSnippet)
                                newSnippet = ""
                            },
                            enabled = newSnippet.isNotBlank()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "新增")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 換行排列，不再橫向捲動：存了一堆詞的時候一眼看得完，不用左右滑著找。
                if (uiState.hintSnippets.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.hintSnippets.forEach { snippet ->
                            RemovableChip(snippet, onRemove = { viewModel.removeHintSnippet(snippet) })
                        }
                    }
                }
            }

            SectionLabel("資料庫")
            GroupedRow(
                GroupPosition.Only,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = { pickFolder.launch(null) }
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = PassProtectTheme.extraColors.label,
                    modifier = Modifier.size(22.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("資料庫位置", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "重新選擇同步資料夾",
                        style = MaterialTheme.typography.labelMedium,
                        color = PassProtectTheme.extraColors.label
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f)
                )
            }
        }
    }
}
