@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.passprotect.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import com.passprotect.ui.motion.LocalSharedTransitionScope
import com.passprotect.ui.motion.LocalNavAnimatedScope
import androidx.navigation.NavGraphBuilder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
private val NavSlideDistance = 30.dp
private val AuthRoutes = setOf("setup", "unlock")

private fun AnimatedContentTransitionScope<NavBackStackEntry>.crossesAuthGate() =
    initialState.destination.route in AuthRoutes || targetState.destination.route in AuthRoutes

private fun navFadeIn() = fadeIn(
    tween(NavDurationMillis - NavFadeOutMillis, delayMillis = NavFadeOutMillis, easing = LinearOutSlowInEasing)
)

private fun navFadeOut() = fadeOut(tween(NavFadeOutMillis, easing = FastOutLinearInEasing))

/** 每個目的地都把自己的 AnimatedContentScope 交給畫面，共享元素才知道這一頁何時進出場。 */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route) { backStackEntry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(backStackEntry) }
    }
}

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

    val slidePx = with(LocalDensity.current) { NavSlideDistance.roundToPx() }

    // 換頁用 Material 的「共軸 X（shared axis）」節奏：舊畫面在前 90ms 就淡出，
    // 新畫面接著淡入，兩者同時只平移一小段距離。之前兩邊在整段時間裡同時交叉
    // 淡入淡出，中間有一段兩個畫面都半透明疊在一起，文字互相重影。
    // 上鎖／解鎖（進出 setup、unlock）不是往下一層鑽，只淡入淡出、不橫向滑。
    // SharedTransitionLayout 包住整個 NavHost，「點的東西長成下一頁」的共享元素
    // 轉場才接得起來（見 ui/motion/SharedElements.kt）。沒有共享元素的換頁照舊走
    // 下面的共軸節奏。
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = "setup",
                enterTransition = {
                    if (crossesAuthGate()) navFadeIn()
                    else navFadeIn() + slideInHorizontally(tween(NavDurationMillis)) { slidePx }
                },
                exitTransition = {
                    if (crossesAuthGate()) navFadeOut()
                    else navFadeOut() + slideOutHorizontally(tween(NavDurationMillis)) { -slidePx }
                },
                popEnterTransition = {
                    if (crossesAuthGate()) navFadeIn()
                    else navFadeIn() + slideInHorizontally(tween(NavDurationMillis)) { -slidePx }
                },
                popExitTransition = {
                    if (crossesAuthGate()) navFadeOut()
                    else navFadeOut() + slideOutHorizontally(tween(NavDurationMillis)) { slidePx }
                }
            ) {
                screen("setup") { SetupScreen(uiState, viewModel) }
                screen("unlock") { UnlockScreen(uiState, viewModel) }
                screen("list") { ListScreen(uiState.entries, navController, viewModel) }
                screen("category/{categoryName}") { backStackEntry ->
                    val name = categoryNameFromArg(backStackEntry.arguments?.getString("categoryName"))
                    CategoryEntriesScreen(name, uiState.entries, navController, viewModel)
                }
                screen("detail/{entryId}") { backStackEntry ->
                    val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
                    DetailScreen(id, uiState.entries, navController)
                }
                screen("edit_new") { EditScreen(null, uiState.entries, navController, viewModel) }
                screen("edit/{entryId}") { backStackEntry ->
                    val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
                    EditScreen(id, uiState.entries, navController, viewModel)
                }
                screen("history/{entryId}/{accountId}") { backStackEntry ->
                    val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
                    val accountId = UUID.fromString(backStackEntry.arguments?.getString("accountId"))
                    HistoryScreen(id, accountId, uiState.entries, navController)
                }
                screen("settings") { SettingsScreen(uiState, navController, viewModel) }
                screen("websearch/{query}") { backStackEntry ->
                    val query = backStackEntry.arguments?.getString("query").orEmpty()
                    WebSearchScreen(query, navController)
                }
            }
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
