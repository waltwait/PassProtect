package com.hintvault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity

/**
 * 用 [FragmentActivity] 而非普通的 [androidx.activity.ComponentActivity]：
 * `BiometricPrompt` 的建構子要求 FragmentActivity。它仍然是
 * androidx.activity.ComponentActivity 的子類，setContent {} 一樣能用。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 規格要求：畫面內容含密碼提示，不可被系統截圖或在「最近使用」
        // 清單裡露出縮圖。必須在 setContent 之前設，否則第一幀會漏一下。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                Surface {
                    // Task 16 會換成 HintVaultApp(viewModel) 真正的導覽圖。
                    Text("Hint Vault")
                }
            }
        }
    }
}
