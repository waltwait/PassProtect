package com.passprotect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.passprotect.crypto.BiometricUnlock
import com.passprotect.crypto.WrappedKeyStore
import com.passprotect.session.LockState
import com.passprotect.ui.UnlockError
import com.passprotect.ui.VaultUiState
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.pressScale
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

    // 第一次進到這個畫面（真的上鎖、切換成生體辨識分頁…）就自動跳一次。
    LaunchedEffect(state.biometricEnabled, biometric, usePassword) {
        if (state.biometricEnabled && !usePassword && biometric != null) {
            viewModel.unlockWithBiometric(biometric)
        }
    }

    /**
     * 只靠上面那個 LaunchedEffect 不夠：使用者若「已經停在這個上鎖畫面」時
     * 把 app 切到背景又切回來（沒有離開過 unlock 這個目的地，`PassProtectApp`
     * 的導覽 effect 不會重新導覽、這個 composable 也就不會重新進入 composition），
     * key 沒有變，LaunchedEffect 不會重新觸發，指紋提示因此不會自動再跳一次。
     * 額外訂閱 Activity 的生命週期，在每次 ON_RESUME 時主動再觸發一次。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state.biometricEnabled, usePassword, biometric) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                state.biometricEnabled && !usePassword && biometric != null
            ) {
                viewModel.unlockWithBiometric(biometric)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            // 主密碼欄位刻意放在螢幕下半部（貼近指紋感應器位置），沒有這行的話
            // 鍵盤一彈出正好蓋住它。imePadding 讓可用高度縮小，78/22 的比例會在
            // 縮小後的範圍裡重新計算，欄位跟著往上讓，不會被鍵盤蓋住。
            .imePadding()
            .padding(24.dp)
    ) {
        // 把整組內容往下推，讓圖示落在螢幕下半部——比較貼近實體/螢幕下指紋
        // 感應器實際的位置，不會叫使用者對著螢幕正中央按指紋。78/22 是抓大概，
        // 不同機型感應器位置本來就有落差，抓一個普遍偏下的比例即可。
        Spacer(Modifier.weight(0.78f))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.biometricEnabled && !usePassword) {
                // 圖示本身可以點——自動觸發之外，另外給一個明確、隨時可按的
                // 入口，不用等自動觸發的時機或它失敗了才有辦法重試。
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = "驗證指紋",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(56.dp)
                        .clickable(enabled = biometric != null) {
                            biometric?.let { viewModel.unlockWithBiometric(it) }
                        }
                )
                Text("點一下驗證指紋", style = MaterialTheme.typography.bodySmall)
            } else {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
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
                PassProtectField(
                    value = password, onValueChange = { password = it },
                    label = "主密碼", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                val unlockInteractionSource = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        viewModel.unlockWithPassword(password.toCharArray())
                        password = ""
                    },
                    interactionSource = unlockInteractionSource,
                    modifier = Modifier.pressScale(unlockInteractionSource)
                ) { Text("解鎖") }
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
        Spacer(Modifier.weight(0.22f))
    }
}
