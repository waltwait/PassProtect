package com.passprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.SaveMessage
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.components.DashedAddButton
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.GroupedRow
import com.passprotect.ui.components.IconTopBar
import com.passprotect.ui.components.PassProtectField
import com.passprotect.ui.components.PillButton
import com.passprotect.ui.components.SectionLabel
import com.passprotect.ui.components.SnippetChip
import com.passprotect.ui.theme.PassProtectTheme
import com.passprotect.ui.motion.SharedKeys
import com.passprotect.ui.motion.sharedBoundsOrSelf
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

/**
 * 刪掉一組帳號之後該展開哪一組。[ids] 是刪除**前**的順序。刪的不是展開中的那組就
 * 不動；刪的正是展開中的那組，就換它後面那組補上（使用者眼睛停在原位，補上來的
 * 就是原位的下一組），它是最後一組才往前找。
 */
internal fun expandedAfterRemoval(ids: List<UUID>, removed: UUID, expanded: UUID): UUID {
    if (removed != expanded) return expanded
    val index = ids.indexOf(removed)
    return ids.getOrNull(index + 1) ?: ids[index - 1]
}

/** [EditScreen] 裡一組帳號欄位的可變狀態——`username`/`hint` 各自獨立可觀察，改其中一個不會重組整個列表。 */
private class AccountFieldState(val id: UUID, initialUsername: String, initialHint: String) {
    var username by mutableStateOf(initialUsername)
    var hint by mutableStateOf(initialHint)
}

/** 「儲存」按鈕的三個階段——見 [SaveButtonContent]。 */
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
    // 同一時間只展開一組帳號（規格 §4.4），其他組收成一列摘要——這是編輯畫面縮短到
    // 一屏的關鍵。存成字串交給 rememberSaveable，理由同上面 AccountFieldsSaver：
    // 去網址搜尋再回來時，展開的是哪一組不能被重置回第一組。
    var expandedId by rememberSaveable { mutableStateOf(accountFields.first().id.toString()) }
    val expanded = accountFields.firstOrNull { it.id.toString() == expandedId } ?: accountFields.first()
    var pickingCategory by rememberSaveable { mutableStateOf(false) }

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
                // 打勾要停留到真的被看見才導覽離開：淡入本身就要 ~220ms，之前的 280ms
                // 只剩一閃而過，「已儲存」幾乎沒人看到。
                delay(500)
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

    if (pickingCategory) {
        CategoryPickerSheet(
            current = category,
            existing = existingCategories,
            onPick = {
                category = it
                pickingCategory = false
            },
            onDismiss = { pickingCategory = false }
        )
    }

    Scaffold(
        // 新增頁從主畫面的「＋」長出來（容器轉換）；編輯既有記錄是從左滑按鈕進來，
        // 沒有對應的元素可以長，走一般換頁。
        modifier = if (entryId == null) Modifier.sharedBoundsOrSelf(SharedKeys.NEW_ENTRY) else Modifier,
        topBar = {
            IconTopBar(
                onBack = { navController.popBackStack() },
                centerTitle = if (existing == null) "新增" else "編輯"
            ) {
                PillButton(
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
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    SaveButtonContent(saveButtonPhase)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                // 鍵盤彈出時把可視範圍往上收，讓下面的 verticalScroll 把「目前
                // 聚焦的欄位還在鍵盤後面」這件事算進可視範圍內，捲動才會真的把
                // 欄位捲到鍵盤上方，不是捲到一個仍被鍵盤蓋住的位置。
                .imePadding()
                // 帳號可以加很多組，內容仍可能超出一屏，保留捲動。
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            if (didAttemptSave) {
                (uiState.saveMessage as? SaveMessage.Conflict)?.let {
                    SaveNotice("修改已存到另一個檔案（${it.conflictFileName}），同步資料夾裡的本尊沒有被動到，請到 KeePassXC 手動合併")
                }
                // 與 Conflict 同樣的處理方式：留在原地並說明，不可靜默 pop back stack。
                if (uiState.saveMessage == SaveMessage.Error) {
                    SaveNotice("存檔失敗，請再試一次")
                }
            }
            TitleField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                GroupedRow(GroupPosition.First, onClick = { pickingCategory = true }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Label,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("分類", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
                    Text(
                        category.ifBlank { "未分類" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                GroupedRow(GroupPosition.Last) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("網址", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
                    UrlInlineField(url, onValueChange = { url = it }, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val query = URLEncoder.encode(title.ifBlank { "" }, "UTF-8")
                            navController.navigate("websearch/$query")
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜尋網址", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 一個標題/網址底下可能不只一組帳號（例如同一個網域下私人跟工作各一組），
            // 所以帳號＋提示是可以新增/刪除的清單，標題/網址/分類則整筆記錄只有一份。
            SectionLabel("帳號", trailing = "${accountFields.size} 組")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                accountFields.forEachIndexed { index, field ->
                    val number = if (accountFields.size > 1) index + 1 else null
                    key(field.id) {
                        if (field === expanded) {
                            ExpandedAccountCard(
                                field = field,
                                number = number,
                                onRemove = if (accountFields.size > 1) {
                                    {
                                        val next = expandedAfterRemoval(accountFields.map { it.id }, field.id, expanded.id)
                                        accountFields.remove(field)
                                        expandedId = next.toString()
                                    }
                                } else null,
                                snippets = uiState.hintSnippets,
                                onSaveSnippet = { viewModel.addHintSnippet(field.hint) }
                            )
                        } else {
                            CollapsedAccountRow(field, number, onExpand = { expandedId = field.id.toString() })
                        }
                    }
                }
                DashedAddButton("新增帳號", onClick = {
                    val added = AccountFieldState(UUID.randomUUID(), "", "")
                    accountFields.add(added)
                    expandedId = added.id.toString()
                })
            }
        }
    }
}

@Composable
private fun SaveButtonContent(phase: SaveButtonPhase) {
    AnimatedContent(
        targetState = phase,
        // 舊內容先快速淡出、新內容稍晚才淡入並從略小放大到原尺寸——之前兩者
        // 同時交叉淡化，「儲存」跟「儲存中…」有一段半透明疊在一起糊成一團。
        transitionSpec = {
            (fadeIn(tween(160, delayMillis = 60)) +
                scaleIn(tween(160, delayMillis = 60), initialScale = 0.9f)) togetherWith
                fadeOut(tween(80))
        },
        label = "save-button-phase"
    ) { current ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (current) {
                SaveButtonPhase.Idle -> Text("儲存")
                SaveButtonPhase.Saving -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 2.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("儲存中…")
                }
                SaveButtonPhase.Saved -> {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("已儲存")
                }
            }
        }
    }
}

@Composable
private fun SaveNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    )
}

/**
 * 標題用大字、沒有底色的輸入框，看起來像在替這筆記錄命名，而不是在填一格表單。
 * contentDescription 直接掛在輸入框上（跟 PassProtectField 同一個理由），
 * `UiEndToEndTest` 用「標題」找它。
 */
@Composable
private fun TitleField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val lineColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "title-underline"
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("標題", style = MaterialTheme.typography.labelMedium, color = PassProtectTheme.extraColors.label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "標題" }
                .drawBehind {
                    val stroke = (if (focused) 2.dp else 1.dp).toPx()
                    drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                }
                .padding(top = 2.dp, bottom = 10.dp)
        )
    }
}

/** 網址列裡的輸入框：靠右對齊、沒有自己的底色，整列看起來像設定頁的一列。 */
@Composable
private fun UrlInlineField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.semantics { contentDescription = "網址" },
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) {
                    Text("選填", style = MaterialTheme.typography.bodyMedium, color = PassProtectTheme.extraColors.label)
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun ExpandedAccountCard(
    field: AccountFieldState,
    number: Int?,
    onRemove: (() -> Unit)?,
    snippets: List<String>,
    onSaveSnippet: () -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, PassProtectTheme.extraColors.borderStrong, shape)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (number != null) "帳號 $number" else "帳號",
                style = MaterialTheme.typography.labelSmall,
                color = PassProtectTheme.extraColors.label,
                modifier = Modifier.weight(1f)
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "刪除這組帳號", tint = PassProtectTheme.extraColors.label)
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
                IconButton(onClick = onSaveSnippet, enabled = field.hint.isNotBlank()) {
                    Icon(Icons.Rounded.StarBorder, contentDescription = "存成常用提示詞")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (snippets.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 常用提示詞是拿來組合的字詞/短句（例如「生日」「寵物名」各存一則，
                // 點兩下拼成「生日 + 寵物名」），不是整句互斥的預設值，所以點下去是
                // 接到這一組帳號的提示欄位後面，不是整欄取代掉。
                snippets.forEach { snippet ->
                    SnippetChip(snippet, onClick = {
                        field.hint = if (field.hint.isBlank()) snippet else "${field.hint} + $snippet"
                    })
                }
            }
        }
    }
}

/** 沒在編輯的帳號收成一列：看得到是哪個帳號、提示大概是什麼，點了就換它展開。 */
@Composable
private fun CollapsedAccountRow(field: AccountFieldState, number: Int?, onExpand: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onExpand)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(number?.let { "帳號 $it" }, field.username.ifBlank { null }).joinToString("・"),
                style = MaterialTheme.typography.bodySmall,
                color = PassProtectTheme.extraColors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                field.hint.ifBlank { "（還沒有提示）" },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f)
        )
    }
}
