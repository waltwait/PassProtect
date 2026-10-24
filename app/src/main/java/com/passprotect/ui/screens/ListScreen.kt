package com.passprotect.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKeyOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.categoryRoute
import com.passprotect.ui.components.GroupPosition
import com.passprotect.ui.components.IconTopBar
import com.passprotect.ui.components.LargeTitleHeader
import com.passprotect.ui.components.SectionLabel
import com.passprotect.ui.components.TopBarIconButton
import com.passprotect.ui.components.hasDividerAbove
import com.passprotect.ui.components.pressScale
import com.passprotect.ui.components.shape
import com.passprotect.ui.format.EntryRowMode
import com.passprotect.ui.format.entryRowSubtitle
import com.passprotect.ui.theme.PassProtectTheme
import com.passprotect.vault.Entry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val SwipeActionWidth = 64.dp
// 放開後用臨界阻尼彈簧（沒有回彈）接手，不是固定時長的 tween：彈簧能接住手指
// 放開那一刻的速度繼續滑，拖曳跟收尾之間不會有「頓一下再重新加速」的接縫。
// 剛度抓 StiffnessLow，整體節奏跟之前調過的 450ms 差不多（240ms 使用者反應是
// 「一閃就到底」），開合的過程一樣看得出來在動。
private const val SwipeSettleStiffness = Spring.StiffnessLow
private val SwipeSettleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = SwipeSettleStiffness
)
// 放開時順著手指速度多推算的慣性距離（秒）：快速一甩，就算位移還沒拖過門檻，
// 也照甩的方向打開／關上。
private const val SwipeProjectionSeconds = 0.1f

/**
 * 動作鈕只在這一列真的被拉開（畫面上至少位移 1px）時才畫。收著的時候按鈕整個被列
 * 蓋住，本來就看不到；不畫就不會有任何情況讓它從列底下透出來。
 */
internal fun swipeActionsShown(offsetPx: Float): Boolean = offsetPx.roundToInt() < 0

private const val FolderPreviewCount = 2

/**
 * 主畫面資料夾格的一格：分類名稱、底下幾筆，跟分類頁最前面兩筆的標題（[preview]，
 * 直接印在資料夾上，不點進去也知道裡面是什麼）。
 */
internal data class FolderSummary(val name: String, val count: Int, val preview: List<String>)

/** 沒有分類（空白）的記錄不算資料夾，它們直接列在資料夾下方的「未分類」。 */
internal fun folderSummaries(entries: List<Entry>): List<FolderSummary> =
    entries.filter { it.category.isNotBlank() }
        .groupBy { it.category }
        .map { (name, inFolder) ->
            FolderSummary(name, inFolder.size, inFolder.map { it.title }.sorted().take(FolderPreviewCount))
        }
        .sortedBy { it.name }

// 在頂端往下拉叫出搜尋框：手指拉的距離只算一半（有阻力），最多拉到整個搜尋框露出來；
// 放開時露出超過六成就打開，不到就縮回去。
private const val SearchPullResistance = 0.5f
private const val SearchPullOpensAt = 0.6f
// 搜尋框區塊（上方留白＋框本身）的高度，拉的進度用它換算。
private val SearchAreaHeight = 68.dp
private val SearchRevealSpec = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
// 大標題往上捲這麼多就整個縮進頂列。
private val TitleCollapseDistance = 48.dp

/** 從露出 [current]（0～1）再拉 [dragPx] 之後，搜尋框露出多少；[fullPx] 是搜尋框區塊的高度。 */
internal fun pulledSearchReveal(current: Float, dragPx: Float, fullPx: Float): Float =
    if (fullPx <= 0f) current else (current + dragPx * SearchPullResistance / fullPx).coerceIn(0f, 1f)

internal fun pullOpensSearch(reveal: Float): Boolean = reveal >= SearchPullOpensAt

/** 大標題縮進頂列的進度：0 是還在原位，1 是只剩頂列的小標。大標題是清單的第一列。 */
internal fun titleCollapseFraction(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, collapseDistancePx: Float): Float =
    if (firstVisibleIndex > 0) 1f else (firstVisibleOffsetPx / collapseDistancePx).coerceIn(0f, 1f)

@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    // 搜尋字跟展開狀態要撐過換頁：點進一筆搜尋結果、再返回時，這個畫面早已被拆掉重組，
    // 用 remember 的話一回來就變回資料夾畫面——剛剛的結果不見了，詳情頁也找不到那一列
    // 可以縮回去，只能整頁淡掉，看起來像閃一下就沒了。
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    // 只有剛按下放大鏡時才捲回頂端、叫出鍵盤；從詳情頁返回、還原成展開狀態時不要。
    var focusSearchOnOpen by remember { mutableStateOf(false) }
    val folders = remember(entries) { folderSummaries(entries) }
    val uncategorized = remember(entries) {
        entries.filter { it.category.isBlank() }.sortedBy { it.title }
    }
    // 搜尋是跨所有分類的（像 Spotlight，不是只在目前資料夾裡找）——分類是獨立的
    // 畫面（見 CategoryEntriesScreen），主畫面搜尋要能一次找到所有記錄，不用先猜
    // 是哪個資料夾、點進去才搜得到。
    val searchResults = remember(entries, query) {
        if (query.isBlank()) emptyList()
        else entries.filter { it.title.contains(query, ignoreCase = true) }.sortedBy { it.title }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    // 搜尋框露出多少（0～1）：往下拉時跟著手指，放開或按放大鏡時用彈簧收尾。
    var searchReveal by remember { mutableFloatStateOf(if (searchExpanded) 1f else 0f) }
    val revealAnimation = remember { arrayOfNulls<Job>(1) }
    fun animateSearchReveal(target: Float) {
        revealAnimation[0]?.cancel()
        revealAnimation[0] = scope.launch {
            animate(searchReveal, target, animationSpec = SearchRevealSpec) { value, _ -> searchReveal = value }
        }
    }
    fun openSearch() {
        searchExpanded = true
        focusSearchOnOpen = true
        animateSearchReveal(1f)
    }
    fun closeSearch() {
        searchExpanded = false
        query = ""
        focusManager.clearFocus()
        animateSearchReveal(0f)
    }
    var peekFolder by remember { mutableStateOf<String?>(null) }
    // 搜尋框是 LazyColumn 的第二列，清單捲下去之後點放大鏡，框會在畫面外展開——
    // 圖示變成「關閉搜尋」卻什麼都看不到。展開時先捲回頂端，再把焦點交給搜尋框，
    // 鍵盤直接跳出來，點了就能打字。
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val collapseDistancePx = with(density) { TitleCollapseDistance.toPx() }
    val titleCollapse by remember(collapseDistancePx) {
        derivedStateOf {
            titleCollapseFraction(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, collapseDistancePx)
        }
    }
    // 清單已經在最頂端還往下拉：多出來的距離拿去拉出搜尋框，不是畫邊緣的回彈。拉到一半又往上推，
    // 先把搜尋框收回去，清單才開始捲。
    val searchAreaPx = with(density) { SearchAreaHeight.toPx() }
    val pullToSearch = remember(searchAreaPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (searchExpanded || source != NestedScrollSource.UserInput || available.y >= 0f || searchReveal <= 0f) {
                    return Offset.Zero
                }
                val before = searchReveal
                searchReveal = pulledSearchReveal(before, available.y, searchAreaPx)
                return Offset(0f, (searchReveal - before) * searchAreaPx / SearchPullResistance)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (searchExpanded || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                revealAnimation[0]?.cancel()
                searchReveal = pulledSearchReveal(searchReveal, available.y, searchAreaPx)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (searchExpanded || searchReveal <= 0f) return Velocity.Zero
                if (pullOpensSearch(searchReveal)) openSearch() else animateSearchReveal(0f)
                return available
            }
        }
    }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded && focusSearchOnOpen) {
            focusSearchOnOpen = false
            listState.animateScrollToItem(0)
            // 搜尋框跟著 AnimatedVisibility 才剛組進畫面；萬一這一幀還沒掛上，
            // requestFocus 會丟例外——寧可少一次自動聚焦，也不要整個 app 當掉。
            runCatching { searchFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // 大標題往上捲走時，頂列左邊淡入同一個標題。
                IconTopBar(startTitle = "提示保險庫", startTitleAlpha = { titleCollapse }) {
                    TopBarIconButton(
                        Icons.Rounded.Search,
                        contentDescription = if (searchExpanded) "關閉搜尋" else "搜尋",
                        onClick = { if (searchExpanded) closeSearch() else openSearch() }
                    )
                    TopBarIconButton(Icons.Outlined.Lock, contentDescription = "鎖定", onClick = { viewModel.lock() })
                    TopBarIconButton(Icons.Outlined.Settings, contentDescription = "設定", onClick = { navController.navigate("settings") })
                }
            },
            floatingActionButton = { NewEntryFab(onClick = { navController.navigate("edit_new") }) }
        ) { padding ->
            // 大標題、搜尋框、資料夾、未分類全部在同一個 LazyColumn 裡，標題跟著內容
            // 一起捲走，頂列的三顆圖示按鈕固定不動。底部留白給 FAB，最後一列不會被蓋住。
            LazyColumn(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .nestedScroll(pullToSearch),
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item(key = "header") {
                    LargeTitleHeader(
                        "提示保險庫",
                        subtitle = "${entries.size} 筆提示",
                        large = true,
                        modifier = Modifier.graphicsLayer { alpha = 1f - titleCollapse }
                    )
                }
                item(key = "search") {
                    // 搜尋框從大標題底下滑出來：只畫露出來的那一段（searchReveal），框貼著露出區的底邊。
                    if (searchExpanded || searchReveal > 0f) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            onClose = ::closeSearch,
                            focusRequester = searchFocus,
                            modifier = Modifier
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                    val height = (placeable.height * searchReveal).roundToInt()
                                    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                                }
                                .graphicsLayer { alpha = searchReveal }
                                .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                        )
                    }
                }
                // 規格 §4.2 的慢速裝置提醒：建立資料庫成功後畫面已經切到這裡，
                // SetupScreen 早就不在畫面上了，所以這句話得在這裡講。
                uiState.setupError?.let { message ->
                    item(key = "setup-error") {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { viewModel.clearSetupError() }) { Text("知道了") }
                        }
                    }
                }
                when {
                    query.isNotBlank() -> {
                        if (searchResults.isEmpty()) {
                            item(key = "empty") { EmptyEntriesMessage(hasFilter = true) }
                        } else {
                            item(key = "results-gap") { Spacer(Modifier.height(20.dp)) }
                            // 搜尋結果每打一個字就變一次，這裡不做增刪動畫，結果要即時跟上打字。
                            itemsIndexed(searchResults, key = { _, it -> it.id.toString() }) { index, entry ->
                                EntryRow(
                                    entry, EntryRowMode.Search, GroupPosition.of(index, searchResults.size),
                                    navController, viewModel,
                                    Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                    entries.isEmpty() -> item(key = "empty") { EmptyEntriesMessage(hasFilter = false) }
                    else -> {
                        // 分類是 Finder 式的資料夾格：點資料夾換頁看該分類底下的記錄（見
                        // CategoryEntriesScreen），沒有分類的記錄直接列在資料夾下方——跟
                        // Finder 資料夾／散落檔案並存同一層的邏輯一樣。
                        if (folders.isNotEmpty()) {
                            item(key = "folders-label") { SectionLabel("資料夾") }
                            item(key = "folders") {
                                CategoryFolderGrid(
                                    folders,
                                    onSelect = { name -> navController.navigate(categoryRoute(name)) },
                                    onPeek = { name -> peekFolder = name }
                                )
                            }
                        }
                        if (uncategorized.isNotEmpty()) {
                            item(key = "uncategorized-label") {
                                if (folders.isNotEmpty()) SectionLabel("未分類") else Spacer(Modifier.height(24.dp))
                            }
                            itemsIndexed(uncategorized, key = { _, it -> it.id.toString() }) { index, entry ->
                                EntryRow(
                                    entry, EntryRowMode.Browse, GroupPosition.of(index, uncategorized.size),
                                    navController, viewModel,
                                    Modifier
                                        .animateItem()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        FolderPeek(
            folderName = peekFolder,
            entries = entries,
            onOpenEntry = { entry ->
                peekFolder = null
                navController.navigate("detail/${entry.id}")
            },
            onOpenFolder = { name ->
                peekFolder = null
                navController.navigate(categoryRoute(name))
            },
            onDismiss = { peekFolder = null }
        )
    }
}

/**
 * 主畫面的搜尋框。外觀跟 PassProtectField 一樣（填色、圓角、聚焦底線），但沒有
 * 上方標籤——頂列的放大鏡已經說明了它是什麼。
 *
 * placeholder「搜尋」刻意畫在 decorationBox 裡：這樣它在語意樹上屬於可編輯的那個
 * 節點，`UiEndToEndTest` 用 `onNodeWithText("搜尋").performTextInput(…)` 才找得到
 * 這個欄位、也打得進字。畫在外面（像 PassProtectField 那樣）會變成一個獨立的純
 * 文字節點，測試會對它打字然後失敗。
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val underlineColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "search-underline-color"
    )
    val underlineWidth by animateDpAsState(if (focused) 2.dp else 1.dp, label = "search-underline-width")
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .drawBehind {
                        drawLine(
                            color = underlineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = underlineWidth.toPx()
                        )
                    }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = PassProtectTheme.extraColors.label,
                    modifier = Modifier.size(20.dp)
                )
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜尋", style = MaterialTheme.typography.bodyLarge, color = PassProtectTheme.extraColors.label)
                    }
                    innerTextField()
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "關閉搜尋", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}

@Composable
private fun NewEntryFab(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    FloatingActionButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(60.dp)
            .pressScale(interactionSource)
    ) {
        Icon(Icons.Rounded.Add, contentDescription = "新增", modifier = Modifier.size(26.dp))
    }
}

/**
 * Finder 式的資料夾格狀顯示：灰色資料夾圖示＋名稱＋筆數，固定一排三個。用三等分
 * 欄位（每欄 weight(1f)）排版，不是靠 FlowRow 自動換行——這樣每個資料夾的寬度
 * 永遠相等，不會因為名稱長短不一而擠成一團；最後一排不滿三個時，用等寬的空白
 * Spacer 補位，維持同一套三欄格線。
 */
@Composable
private fun CategoryFolderGrid(folders: List<FolderSummary>, onSelect: (String) -> Unit, onPeek: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        folders.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { folder ->
                    Box(Modifier.weight(1f)) {
                        FolderTile(folder, onClick = { onSelect(folder.name) }, onLongClick = { onPeek(folder.name) })
                    }
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 點了進分類頁，長按浮出預覽（[FolderPeek]，長按時手機會輕震一下）。 */
@Composable
private fun FolderTile(folder: FolderSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .pressScale(interactionSource)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onLongClickLabel = "預覽",
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            // 灰色，跟主題本身的灰階配色一致——不是 Finder 原本那種藍色。
            tint = PassProtectTheme.extraColors.label,
            modifier = Modifier.size(28.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${folder.count} 筆",
                style = MaterialTheme.typography.labelMedium,
                color = PassProtectTheme.extraColors.label
            )
            // 最前面兩筆的標題。不滿兩筆也留兩行，同一排的資料夾才會一樣高。
            Spacer(Modifier.height(4.dp))
            repeat(FolderPreviewCount) { index ->
                Text(
                    folder.preview.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = PassProtectTheme.extraColors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val PeekEnterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 長按資料夾浮出來的預覽：這個分類底下的記錄。點一筆直接開詳情，點最上面的資料夾列進分類頁，
 * 點外面或按返回收起。畫在主畫面同一個視窗裡、不另開 Dialog 視窗，截圖保護（FLAG_SECURE）
 * 一樣蓋得到。
 */
@Composable
private fun FolderPeek(
    folderName: String?,
    entries: List<Entry>,
    onOpenEntry: (Entry) -> Unit,
    onOpenFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 收起的動畫途中 folderName 已經是 null，記住最後一個，淡出時卡片裡才還有東西。
    var shown by remember { mutableStateOf(folderName) }
    if (folderName != null) shown = folderName
    BackHandler(enabled = folderName != null, onBack = onDismiss)

    AnimatedVisibility(
        visible = folderName != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .systemBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            val name = shown ?: return@Box
            val inFolder = remember(entries, name) { entries.filter { it.category == name }.sortedBy { it.title } }
            val now = remember { Instant.now() }
            val shape = MaterialTheme.shapes.large
            Column(
                Modifier
                    .animateEnterExit(
                        enter = scaleIn(tween(220, easing = PeekEnterEasing), initialScale = 0.92f),
                        exit = scaleOut(tween(120), targetScale = 0.96f)
                    )
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    // 點到卡片上沒有東西的地方不要當成點外面。
                    .clickable(interactionSource = null, indication = null) {}
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(name) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${inFolder.size} 筆",
                            style = MaterialTheme.typography.labelMedium,
                            color = PassProtectTheme.extraColors.label
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f)
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    inFolder.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 58.dp)
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entryRowSubtitle(entry, EntryRowMode.Browse, now, ZoneId.systemDefault()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PassProtectTheme.extraColors.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 點分類資料夾換頁進來的畫面：只顯示這個分類底下的記錄，跟主畫面共用同一套
 * 列／滑動編輯刪除元件（見 [EntryRow]）。
 */
@Composable
fun CategoryEntriesScreen(
    categoryName: String,
    entries: List<Entry>,
    navController: NavController,
    viewModel: VaultViewModel
) {
    val filtered = remember(entries, categoryName) {
        entries.filter { it.category == categoryName }.sortedBy { it.title }
    }

    Scaffold(topBar = { IconTopBar(onBack = { navController.popBackStack() }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                LargeTitleHeader(
                    categoryName,
                    subtitle = "${filtered.size} 筆提示",
                    leading = {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = PassProtectTheme.extraColors.label,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                )
            }
            item(key = "gap") { Spacer(Modifier.height(28.dp)) }
            if (filtered.isEmpty()) {
                item(key = "empty") { EmptyEntriesMessage(hasFilter = true) }
            }
            itemsIndexed(filtered, key = { _, it -> it.id.toString() }) { index, entry ->
                EntryRow(
                    entry, EntryRowMode.Browse, GroupPosition.of(index, filtered.size),
                    navController, viewModel,
                    Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

/**
 * 群組清單裡的一筆記錄。分隔線畫在 [SwipeActionsRow] 外面，左滑時不會跟著卡片
 * 一起被拖走。
 */
@Composable
private fun EntryRow(
    entry: Entry,
    mode: EntryRowMode,
    position: GroupPosition,
    navController: NavController,
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier
) {
    val now = remember { Instant.now() }
    Column(modifier) {
        if (position.hasDividerAbove) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        SwipeActionsRow(
            onEdit = { navController.navigate("edit/${entry.id}") },
            onDelete = { viewModel.deleteEntry(entry.id) },
            shape = position.shape()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { navController.navigate("detail/${entry.id}") }
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entryRowSubtitle(entry, mode, now, ZoneId.systemDefault()),
                        style = MaterialTheme.typography.bodySmall,
                        color = PassProtectTheme.extraColors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = PassProtectTheme.extraColors.label.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyEntriesMessage(hasFilter: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.VpnKeyOff,
            contentDescription = null,
            tint = PassProtectTheme.extraColors.label
        )
        Text(
            if (hasFilter) "沒有符合條件的提示" else "還沒有任何提示，點右下角新增第一筆",
            style = MaterialTheme.typography.bodyMedium,
            color = PassProtectTheme.extraColors.label
        )
    }
}

/**
 * 往左滑開啟「編輯／刪除」兩個動作鈕（仿記帳 app 常見的滑動操作列），跟
 * DetailScreen 的兩步驟確認刪除是兩條並存的路徑，不衝突：手勢是給熟悉清單
 * 操作的使用者的捷徑，真的誤觸也還有 KDBX 備份／同步衝突檔案能救回來，
 * 不是唯一防線。
 */
@Composable
private fun SwipeActionsRow(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val revealPx = with(density) { (SwipeActionWidth * 2).toPx() }
    // offsetPx 是畫面位移唯一的真值來源，任何時候都同步更新：拖曳中在
    // onDelta 直接寫（不經過協程）；放開後由 settleAnim 的逐幀 callback 寫。
    // 前一版拖曳中透過 scope.launch { animatable.snapTo() } 非同步套用，
    // 跟放開時另一個協程呼叫的 animateTo() 互相搶——最後一筆殘留、還沒真的
    // 執行的 snapTo 可能在 animateTo 剛開始沒幾格就把值蓋掉，動畫因此變成
    // 「一瞬間跳到底」而不是真的滑過去。改成單一同步狀態，兩邊不會再搶。
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val settleAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    suspend fun settleTo(target: Float, velocity: Float = 0f) {
        // 臨界阻尼彈簧的初速一旦超過「ω × 剩餘距離」就會衝過頭（ω = √剛度，Compose
        // 的彈簧質量固定是 1），衝過頭會露出按鈕外側的空白。初速壓在這個上限內，
        // 用力一甩是平順減速到定位，不是撞到邊界；coerceIn 只是保險。
        val maxVelocity = sqrt(SwipeSettleStiffness) * abs(target - offsetPx)
        settleAnim.snapTo(offsetPx)
        settleAnim.animateTo(
            target,
            animationSpec = SwipeSettleSpec,
            initialVelocity = velocity.coerceIn(-maxVelocity, maxVelocity)
        ) { offsetPx = value.coerceIn(-revealPx, 0f) }
    }

    fun close() {
        scope.launch { settleTo(0f) }
    }

    // clip 放在最外層，讓這一列＋兩顆按鈕被裁成同一個形狀，整組是「連在一起的
    // 一個整體」。形狀由這一列在群組清單裡的位置決定（[GroupPosition]）：頭尾兩列
    // 只有外側兩角是圓的，中間的列是直角——拉開時露出的按鈕跟著同一個形狀裁，
    // 群組最上、最下那兩顆按鈕的外角才會跟容器的圓角接得上。
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        Row(
            Modifier
                .matchParentSize()
                // 在繪製階段讀 offsetPx，拖曳時不會每一格都重組。
                .graphicsLayer { alpha = if (swipeActionsShown(offsetPx)) 1f else 0f },
            horizontalArrangement = Arrangement.End
        ) {
            SwipeActionButton(
                label = "編輯",
                icon = Icons.Outlined.Edit,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    close()
                    onEdit()
                }
            )
            SwipeActionButton(
                label = "刪除",
                icon = Icons.Outlined.Delete,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = {
                    close()
                    onDelete()
                }
            )
        }
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetPx = (offsetPx + delta).coerceIn(-revealPx, 0f)
                    },
                    onDragStopped = { velocity ->
                        // 門檻壓到 28%：滑一點點就整組打開，不用滑到快一半才有反應。
                        // 比的是「照放開時的速度再滑一小段會停在哪」，不只是放開當下的位置。
                        val projected = offsetPx + velocity * SwipeProjectionSeconds
                        val target = if (projected < -revealPx * 0.28f) -revealPx else 0f
                        settleTo(target, velocity)
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionButton(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    // 圖示單獨就能表意（鉛筆＝編輯、垃圾桶＝刪除），拿掉中文字讓兩顆按鈕更精簡；
    // label 還是留著當 contentDescription，螢幕報讀器需要的文字說明沒有少。
    Column(
        Modifier
            .width(SwipeActionWidth)
            .fillMaxHeight()
            .background(containerColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
    }
}
