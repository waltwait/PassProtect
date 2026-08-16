package com.passprotect.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.passprotect.session.AutoLock
import com.passprotect.session.AutoLockPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 用 [FragmentActivity] 而非普通的 [androidx.activity.ComponentActivity]：
 * `BiometricPrompt` 的建構子要求 FragmentActivity。它仍然是
 * androidx.activity.ComponentActivity 的子類，setContent {} 一樣能用。
 */
class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    private var autoLock: AutoLock = newAutoLock(AutoLockPolicy())
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            autoLock.tick()
            tickHandler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            autoLock.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 規格要求：畫面內容含密碼提示，不可被系統截圖或在「最近使用」
        // 清單裡露出縮圖。必須在 setContent 之前設，否則第一幀會漏一下。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // 設定畫面改自動上鎖逾時後，policy 會變；AutoLock 本身沒有「換 policy」
        // 的 API，重建一個新的最單純，成本可忽略。
        lifecycleScope.launch {
            viewModel.uiState.map { it.autoLockPolicy }.distinctUntilChanged()
                .collect { policy -> autoLock = newAutoLock(policy) }
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        setContent {
            MaterialTheme {
                Surface {
                    PassProtectApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        autoLock.onReturnToForeground()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS)
    }

    override fun onPause() {
        super.onPause()
        autoLock.onEnterBackground()
        tickHandler.removeCallbacks(tickRunnable)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLock.onUserInteraction()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
    }

    private fun newAutoLock(policy: AutoLockPolicy) = AutoLock(
        policy = policy,
        now = { SystemClock.elapsedRealtime() },
        onLock = { viewModel.lock() }
    )

    private companion object {
        const val TICK_INTERVAL_MILLIS = 15_000L
    }
}
