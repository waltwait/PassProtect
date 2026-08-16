package com.hintvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.hintvault.crypto.BiometricUnlock
import com.hintvault.crypto.WrappedKeyStore
import com.hintvault.session.LockState
import com.hintvault.ui.UnlockError
import com.hintvault.ui.VaultUiState
import com.hintvault.ui.VaultViewModel
import java.io.File

@Composable
fun UnlockScreen(state: VaultUiState, viewModel: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    val biometric = remember(activity) {
        activity?.let { BiometricUnlock(it, store = WrappedKeyStore(File(context.applicationContext.filesDir, "keys"))) }
    }

    LaunchedEffect(state.biometricEnabled, biometric, usePassword) {
        if (state.biometricEnabled && !usePassword && biometric != null) {
            viewModel.unlockWithBiometric(biometric)
        }
    }

    Column(Modifier.padding(16.dp)) {
        when (state.unlockError) {
            // `Vault.open()` 分不出「密碼錯」與「檔案壞掉/不是 KDBX」（見 Vault.kt 的
            // WrongPasswordException KDoc），WRONG_PASSWORD 兩者都涵蓋，訊息就必須兩者
            // 都講——只說「密碼錯誤」會讓拿著壞檔案的使用者一直重打正確的密碼。
            UnlockError.WRONG_PASSWORD -> Text("密碼錯誤，或檔案已損毀/格式不符")
            UnlockError.UNSUPPORTED_FORMAT ->
                Text("此資料庫使用本 app 不支援的格式（例如 KeePassXC 用 Argon2id 建立的資料庫），請到 KeePassXC 把 KDF 改回 Argon2d")
            UnlockError.FILE_MISSING -> Text("找不到資料庫檔案")
            UnlockError.BIOMETRIC_INVALIDATED -> Text("生體辨識金鑰已失效，請改用主密碼")
            null -> {}
        }

        if (state.lockState == LockState.Locked && (usePassword || !state.biometricEnabled)) {
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("主密碼") }, visualTransformation = PasswordVisualTransformation()
            )
            Button(onClick = {
                viewModel.unlockWithPassword(password.toCharArray())
                password = ""
            }) { Text("解鎖") }
        }

        if (state.biometricEnabled && !usePassword) {
            TextButton(onClick = { usePassword = true }) { Text("改用主密碼") }
        }

        // 規格 §4.1：即使是生體辨識，實際開檔仍完整跑一次 Argon2d（約 2.2 秒），
        // 畫面必須顯示進度指示，不可看起來像卡住。
        if (state.isUnlocking) {
            CircularProgressIndicator()
        }
    }
}
