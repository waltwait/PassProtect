package com.passprotect.ui

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.passprotect.session.LockState
import com.passprotect.ui.screens.DetailScreen
import com.passprotect.ui.screens.EditScreen
import com.passprotect.ui.screens.HistoryScreen
import com.passprotect.ui.screens.ListScreen
import com.passprotect.ui.screens.SettingsScreen
import com.passprotect.ui.screens.SetupScreen
import com.passprotect.ui.screens.UnlockScreen
import java.util.UUID

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

    NavHost(
        navController = navController,
        startDestination = "setup",
        enterTransition = { slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
        exitTransition = { slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(220)) },
        popEnterTransition = { slideInHorizontally(tween(220)) { -it / 8 } + fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(220)) }
    ) {
        composable("setup") { SetupScreen(uiState, viewModel) }
        composable("unlock") { UnlockScreen(uiState, viewModel) }
        composable("list") { ListScreen(uiState.entries, navController, viewModel) }
        composable("detail/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            DetailScreen(id, uiState.entries, navController, viewModel)
        }
        composable("edit_new") { EditScreen(null, uiState.entries, navController, viewModel) }
        composable("edit/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            EditScreen(id, uiState.entries, navController, viewModel)
        }
        composable("history/{entryId}") { backStackEntry ->
            val id = UUID.fromString(backStackEntry.arguments?.getString("entryId"))
            HistoryScreen(id, uiState.entries, navController)
        }
        composable("settings") { SettingsScreen(uiState, navController, viewModel) }
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
