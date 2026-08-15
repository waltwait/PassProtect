package com.hintvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hintvault.session.LockState
import com.hintvault.ui.screens.DetailScreen
import com.hintvault.ui.screens.EditScreen
import com.hintvault.ui.screens.HistoryScreen
import com.hintvault.ui.screens.ListScreen
import com.hintvault.ui.screens.SettingsScreen
import com.hintvault.ui.screens.SetupScreen
import com.hintvault.ui.screens.UnlockScreen
import java.util.UUID

@Composable
fun HintVaultApp(viewModel: VaultViewModel) {
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

    NavHost(navController = navController, startDestination = "setup") {
        composable("setup") { SetupScreen(uiState.setup, viewModel) }
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
            HistoryScreen(id, uiState.entries)
        }
        composable("settings") { SettingsScreen(uiState, viewModel) }
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
