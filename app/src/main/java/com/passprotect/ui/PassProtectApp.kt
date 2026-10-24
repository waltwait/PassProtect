package com.passprotect.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.passprotect.session.LockState
import com.passprotect.ui.screens.CategoryEntriesScreen
import com.passprotect.ui.screens.DetailScreen
import com.passprotect.ui.screens.EditScreen
import com.passprotect.ui.screens.HistoryScreen
import com.passprotect.ui.screens.ListScreen
import com.passprotect.ui.screens.SettingsScreen
import com.passprotect.ui.screens.SetupScreen
import com.passprotect.ui.screens.UnlockScreen
import com.passprotect.ui.screens.WebSearchScreen
import java.util.UUID

private const val NavDurationMillis = 300
private const val NavFadeOutMillis = 90
private val AuthRoutes = setOf("setup", "unlock")

// 推入式換頁：新頁整頁從右邊滑進來蓋住舊頁，舊頁往左退四分之一、淡到 0.7 往後沉。
// 兩頁同一條曲線、同一段時間（一開始快、最後慢慢停）。
private const val PushMillis = 350
private val PushEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val BehindAlpha = 0.7f
private fun <T> pushSpec() = tween<T>(PushMillis, easing = PushEasing)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.crossesAuthGate() =
    initialState.destination.route in AuthRoutes || targetState.destination.route in AuthRoutes

private fun navFadeIn() = fadeIn(
    tween(NavDurationMillis - NavFadeOutMillis, delayMillis = NavFadeOutMillis, easing = LinearOutSlowInEasing)
)

private fun navFadeOut() = fadeOut(tween(NavFadeOutMillis, easing = FastOutLinearInEasing))

@Composable
fun PassProtectApp(viewModel: VaultViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.checkExistingSetup(context) }

    // 首次設定尚未完成、或已上鎖：一律導去對應畫面。
    // 這個 effect 是「進入未解鎖狀態」唯一的導覽來源，其餘畫面之間的
    // 切換（清單 → 詳情 → 編輯…）由各畫面自己呼叫 navController.navigate()。
    LaunchedEffect(uiState.setup, uiState.lockState) {
        val target = when {
            uiState.setup != SetupState.Ready -> "setup"
            uiState.lockState == LockState.Locked -> "unlock"
            else -> null
        }
        if (target != null && navController.currentDestination?.route != target) {
            navController.navigate(target) { popUpTo(0) }
        }
    }

    // 換頁一律用推入式：整個過程畫面上一直有一頁是不透明的。之前的共軸換頁讓舊頁先淡到
    // 全透明、新頁才淡進來，中間約 0.1 秒整個畫面只剩底色，看起來像閃一下。
    // 返回時 NavHost 會把要離開的那頁疊在上面（zIndex 比底下那頁大），往右滑走就露出底下那頁。
    // 上鎖／解鎖（進出 setup、unlock）不是往下一層鑽，只淡入淡出、不橫向滑。
    NavHost(
        navController = navController,
        startDestination = "setup",
        enterTransition = {
            if (crossesAuthGate()) navFadeIn()
            else slideInHorizontally(pushSpec()) { it }
        },
        exitTransition = {
            if (crossesAuthGate()) navFadeOut()
            else slideOutHorizontally(pushSpec()) { -it / 4 } + fadeOut(pushSpec(), targetAlpha = BehindAlpha)
        },
        popEnterTransition = {
            if (crossesAuthGate()) navFadeIn()
            else slideInHorizontally(pushSpec()) { -it / 4 } + fadeIn(pushSpec(), initialAlpha = BehindAlpha)
        },
        popExitTransition = {
            if (crossesAuthGate()) navFadeOut()
            else slideOutHorizontally(pushSpec()) { it }
        }
    ) {
        composable("setup") { SetupScreen(uiState, viewModel) }
        composable("unlock") { UnlockScreen(uiState, viewModel) }
        composable("list") { ListScreen(uiState.entries, navController, viewModel) }
        composable("category/{categoryName}") { backStackEntry ->
            val name = categoryNameFromArg(backStackEntry.arguments?.getString("categoryName"))
            CategoryEntriesScreen(name, uiState.entries, navController, viewModel)
        }
        composable("detail/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            DetailScreen(id, uiState.entries, navController)
        }
        composable("edit_new") { EditScreen(null, uiState.entries, navController, viewModel) }
        composable("edit/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            EditScreen(id, uiState.entries, navController, viewModel)
        }
        composable("history/{entryId}/{accountId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            val accountId = UUID.fromString(backStackEntry.arguments?.getString("accountId"))
            HistoryScreen(id, accountId, uiState.entries, navController)
        }
        composable("settings") { SettingsScreen(uiState, navController, viewModel) }
        composable("websearch/{query}") { backStackEntry ->
            val query = backStackEntry.arguments?.getString("query").orEmpty()
            WebSearchScreen(query, navController)
        }
    }

    // 一旦設定完成且已解鎖，且目前停在 setup/unlock，導去清單。
    // 分兩個 LaunchedEffect 是為了讓「鎖定」永遠優先於「導去清單」，
    // 避免兩個方向的導覽競爭同一次 recomposition。
    LaunchedEffect(uiState.setup, uiState.lockState) {
        if (uiState.setup == SetupState.Ready && uiState.lockState == LockState.Unlocked) {
            val current = navController.currentDestination?.route
            if (current == "setup" || current == "unlock") {
                navController.navigate("list") { popUpTo(0) }
            }
        }
    }
}
