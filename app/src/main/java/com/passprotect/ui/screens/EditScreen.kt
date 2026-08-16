package com.passprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.SaveMessage
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.pressScale
import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import com.passprotect.vault.HistoricalHint
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * 畫面上一組「帳號＋提示」欄位的存檔草稿——[EditScreen] 用它把 Compose 狀態
 * 交給 [entryToSave]，讓後者維持純函式、JVM 測試碰得到。
 */
internal data class AccountDraft(val id: UUID, val username: String = "", val hint: String)

/**
 * 組出要存檔的 [Entry]，並在這裡（也只有這裡）決定每一組帳號要不要往
 * [Account.history] 添一筆。
 *
 * 規則：只有「修改既有帳號」且「提示真的變了」才把**舊的**提示存成
 * [HistoricalHint]——那正是 `HistoryScreen` 要救援的東西（改了提示，但某台裝置
 * 還在用舊密碼）。只改標題、什麼都沒改、或新增帳號都不寫歷史，否則歷史清單會被
 * 一堆「跟現在一模一樣」的雜訊塞滿，反而看不出真正變過幾次。改帳號名稱同理不算
 * 改提示。用 [AccountDraft.id] 對回 [existing] 裡的舊帳號——這個 id 在畫面上
 * 新增一組帳號時就地產生，編輯既有帳號時延續舊的，藉此分辨「這組是本來就有的」
 * 還是「這組是這次新加的」。
 *
 * 從 composable 的 onClick 抽成獨立函式，是為了讓這條規則能在 JVM 單元測試裡
 * 直接驗證——Compose 的畫面本身只有真機 instrumented 測試碰得到。
 *
 * [now] 可注入，測試才能斷言時間戳；[Entry.modifiedAt] 則怎麼傳都會被底層函式庫
 * 在寫入時蓋掉（見 `Entry` 的 KDoc），這裡照舊傳現在時間即可。
 */
internal fun entryToSave(
    existing: Entry?,
    title: String,
    url: String = existing?.url.orEmpty(),
    category: String = existing?.category.orEmpty(),
    accounts: List<AccountDraft>,
    now: Instant = Instant.now()
): Entry {
    val existingById = existing?.accounts?.associateBy { it.id }.orEmpty()
    val resultAccounts = accounts.map { draft ->
        val old = existingById[draft.id]
        val history = when {
            old == null -> emptyList()
            old.hint != draft.hint -> listOf(HistoricalHint(at = now, hint = old.hint)) + old.history
            else -> old.history
        }
        Account(id = draft.id, username = draft.username, hint = draft.hint, history = history)
    }
    return Entry(
        id = existing?.id ?: UUID.randomUUID(),
        title = title,
        url = url,
        category = category,
        accounts = resultAccounts,
        modifiedAt = now
    )
}

/** [EditScreen] 裡一組帳號欄位的可變狀態——`username`/`hint` 各自獨立可觀察，改其中一個不會重組整個列表。 */
private class AccountFieldState(val id: UUID, initialUsername: String, initialHint: String) {
    var username by mutableStateOf(initialUsername)
    var hint by mutableStateOf(initialHint)
}

/** 「儲存」按鈕的三個階段——見 [EditScreen] 裡的 `AnimatedContent` 用法。 */
private enum class SaveButtonPhase { Idle, Saving, Saved }

/**
 * 點「搜尋網址」會導覽去 WebSearchScreen 再導覽回來——Navigation Compose
 * 離開目前畫面時不會把它留在 composition 裡，回來時是重新組合一次，所有
 * 沒有用 `rememberSaveable` 的 `remember` 狀態（標題、帳號清單…）都會被
 * 重置回 `existing` 當初的值（新增畫面的話就是空字串），使用者剛打好的
 * 標題就這樣憑空消失。改用 `rememberSaveable` 讓這些欄位撐過那趟往返。
 */
private val AccountFieldsSaver = listSaver<SnapshotStateList<AccountFieldState>, List<String>>(
    save = { list -> list.map { listOf(it.id.toString(), it.username, it.hint) } },
    restore = { saved ->
        saved.map { AccountFieldState(UUID.fromString(it[0]), it[1], it[2]) }.toMutableStateList()
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(entryId: UUID?, entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val existing = entryId?.let { id -> entries.firstOrNull { it.id == id } }
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(existing?.url.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(existing?.category.orEmpty()) }
    val accountFields = rememberSaveable(saver = AccountFieldsSaver) {
        val initial = existing?.accounts?.takeIf { it.isNotEmpty() }
            ?: listOf(Account(id = UUID.randomUUID(), hint = ""))
        initial.map { AccountFieldState(it.id, it.username, it.hint) }.toMutableStateList()
    }
    val uiState by viewModel.uiState.collectAsState()
    var didAttemptSave by rememberSaveable { mutableStateOf(false) }
    val existingCategories = remember(entries) {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }

    // 存檔按鈕的三段式動畫狀態。存檔本身是背景跑一次完整 Argon2d KDF
    // （規格量測約 2.2 秒），這段時間原本按鈕完全沒反應、感覺像卡住；
    // 現在按下去先進 Saving（轉圈），存完成功後短暫進 Saved（打勾），
    // 再導覽離開——讓那 2 秒等待跟收尾都有東西看，不是憑空多等。
    var saveButtonPhase by remember { mutableStateOf(SaveButtonPhase.Idle) }

    // 只在成功存檔時離開這個畫面；SaveMessage.Conflict 時必須留在原地，
    // 讓使用者看到規格 §4.4 要求的衝突訊息，不可靜默導覽走。
    LaunchedEffect(uiState.saveMessage) {
        if (!didAttemptSave) return@LaunchedEffect
        when (uiState.saveMessage) {
            is SaveMessage.Saved -> {
                saveButtonPhase = SaveButtonPhase.Saved
                delay(280) // 打勾動畫留一點時間被看到，才導覽離開
                navController.popBackStack()
            }
            SaveMessage.Error, is SaveMessage.Conflict -> saveButtonPhase = SaveButtonPhase.Idle
            null -> {}
        }
    }

    // 從 WebSearchScreen 帶回來的網址，經 SavedStateHandle 傳回——這是
    // Navigation Compose「畫面 B 把結果交回畫面 A」的標準做法，不需要另外
    // 設計一個跨畫面共用的暫存狀態。撿到就馬上從 SavedStateHandle 移除，
    // 避免使用者之後隨便切一次頁又撿到同一個舊值。
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val pickedUrl = savedStateHandle
        ?.getStateFlow<String?>(WEB_SEARCH_RESULT_KEY, null)
        ?.collectAsState()
    LaunchedEffect(pickedUrl?.value) {
        pickedUrl?.value?.let {
            url = it
            savedStateHandle.remove<String>(WEB_SEARCH_RESULT_KEY)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "新增" else "編輯") },
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
                // 鍵盤彈出時把可視範圍往上收，讓下面的 verticalScroll 把「目前
                // 聚焦的欄位還在鍵盤後面」這件事算進可視範圍內，捲動才會真的把
                // 欄位捲到鍵盤上方，不是捲到一個仍被鍵盤蓋住的位置。
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // 帳號清單長度不固定——新增幾組帳號後內容很容易超出一個螢幕高度，
                // 沒有這行的話畫面下半部（含「儲存」按鈕）會被裁掉、滑不到。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (didAttemptSave) {
                (uiState.saveMessage as? SaveMessage.Conflict)?.let {
                    Text("修改已存到另一個檔案（${it.conflictFileName}），同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併")
                }
                // 與 Conflict 同樣的處理方式：留在原地並說明，不可靜默 pop back stack。
                if (uiState.saveMessage == SaveMessage.Error) {
                    Text("存檔失敗，請再試一次")
                }
            }
            PassProtectField(
                value = title, onValueChange = { title = it }, label = "標題",
                modifier = Modifier.fillMaxWidth()
            )
            PassProtectField(
                value = url, onValueChange = { url = it }, label = "網址（選填）",
                trailingIcon = {
                    IconButton(onClick = {
                        val query = URLEncoder.encode(title.ifBlank { "" }, "UTF-8")
                        navController.navigate("websearch/$query")
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜尋網址")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            // 原本用 ExposedDropdownMenuBox／ExposedDropdownMenu 做這個欄位，但那個
            // popup 空間不夠時（鍵盤開著、下方可用高度變小）會自動往上長，跟輸入框
            // 脫節，位置很怪。改成跟下面「常用提示詞」一樣的做法：建議清單直接排在
            // 欄位下方、正常排版流程裡的一排 chip，不是浮動 popup，永遠只會往下，
            // 不會因為鍵盤或可用空間而反過來。
            PassProtectField(
                value = category,
                onValueChange = { category = it },
                label = "分類（選填）",
                modifier = Modifier.fillMaxWidth()
            )
            if (existingCategories.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    existingCategories.forEach { name ->
                        SuggestionChip(onClick = { category = name }, label = { Text(name) })
                    }
                }
            }

            HorizontalDivider()

            // 一個標題/網址底下可能不只一組帳號（例如同一個網域下私人跟工作各一組），
            // 所以帳號＋提示是可以新增/刪除的清單，標題/網址/分類則整筆記錄只有一份。
            accountFields.forEachIndexed { index, field ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (accountFields.size > 1) "帳號 ${index + 1}" else "帳號",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (accountFields.size > 1) {
                            IconButton(onClick = { accountFields.removeAt(index) }) {
                                Icon(Icons.Filled.Close, contentDescription = "刪除這組帳號")
                            }
                        }
                    }
                    PassProtectField(
                        value = field.username, onValueChange = { field.username = it }, label = "帳號（選填）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    PassProtectField(
                        value = field.hint, onValueChange = { field.hint = it }, label = "提示",
                        monospace = true,
                        trailingIcon = {
                            IconButton(
                                onClick = { viewModel.addHintSnippet(field.hint) },
                                enabled = field.hint.isNotBlank()
                            ) {
                                Icon(Icons.Filled.Star, contentDescription = "存成常用提示詞")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (uiState.hintSnippets.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 常用提示詞是拿來組合的字詞/短句（例如「生日」「寵物名」各存一則，
                            // 點兩下拼成「生日 + 寵物名」），不是整句互斥的預設值，所以點下去是
                            // 接到這一組帳號的提示欄位後面，不是整欄取代掉。
                            uiState.hintSnippets.forEach { snippet ->
                                AssistChip(
                                    onClick = {
                                        field.hint = if (field.hint.isBlank()) snippet else "${field.hint} + $snippet"
                                    },
                                    label = { Text(snippet) }
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { accountFields.add(AccountFieldState(UUID.randomUUID(), "", "")) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("新增帳號")
            }

            val saveInteractionSource = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    didAttemptSave = true
                    saveButtonPhase = SaveButtonPhase.Saving
                    viewModel.saveEntry(
                        entryToSave(
                            existing,
                            title = title,
                            url = url,
                            category = category,
                            accounts = accountFields.map { AccountDraft(it.id, it.username, it.hint) }
                        )
                    )
                },
                enabled = title.isNotBlank() && saveButtonPhase == SaveButtonPhase.Idle,
                interactionSource = saveInteractionSource,
                modifier = Modifier.pressScale(saveInteractionSource)
            ) {
                AnimatedContent(
                    targetState = saveButtonPhase,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "save-button-phase"
                ) { phase ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (phase) {
                            SaveButtonPhase.Idle -> {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("儲存")
                            }
                            SaveButtonPhase.Saving -> {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 6.dp),
                                    strokeWidth = 2.dp,
                                    color = LocalContentColor.current
                                )
                                Text("儲存中…")
                            }
                            SaveButtonPhase.Saved -> {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("已儲存")
                            }
                        }
                    }
                }
            }
        }
    }
}
