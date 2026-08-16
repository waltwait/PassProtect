package com.passprotect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.VpnKeyOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.format.toDisplayString
import com.passprotect.vault.Entry
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val EntryCardShape = RoundedCornerShape(14.dp)
private val SwipeActionWidth = 64.dp
// 先快後慢的減速曲線，沒有回彈；240ms 使用者反應還是「一閃就到底」，
// 拉長到 450ms 讓開合的過程真的看得出來在動。
private val SwipeSettleSpec = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val categories = remember(entries) {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val filtered = remember(entries, query, selectedCategory) {
        entries
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .filter { selectedCategory == null || it.category == selectedCategory }
            .sortedBy { it.title }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提示保險庫") },
                actions = {
                    IconButton(onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) query = ""
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = if (searchExpanded) "關閉搜尋" else "搜尋"
                        )
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = "鎖定")
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("edit_new") }) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 規格 §4.2 的慢速裝置提醒：建立資料庫成功後畫面已經切到這裡，
            // SetupScreen 早就不在畫面上了，所以這句話得在這裡講。
            uiState.setupError?.let { message ->
                Text(message)
                TextButton(onClick = { viewModel.clearSetupError() }) { Text("知道了") }
            }
            AnimatedVisibility(visible = searchExpanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜尋") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            searchExpanded = false
                            query = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "關閉搜尋")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    // Material3 OutlinedTextField 預設高度是 56dp，跟 PassProtectField
                    // 統一用的 52dp 差 4dp——全 app 其他輸入框都是 52dp（EditScreen／
                    // SetupScreen／UnlockScreen／SettingsScreen 共用同一個
                    // PassProtectField 元件，天生一致），只有這個搜尋框是獨立的
                    // OutlinedTextField，得自己補這行才會跟其他欄位一樣高。
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }
            if (categories.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("全部") }
                    )
                    categories.forEach { name ->
                        FilterChip(
                            selected = selectedCategory == name,
                            onClick = { selectedCategory = if (selectedCategory == name) null else name },
                            label = { Text(name) }
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                EmptyEntriesMessage(hasFilter = query.isNotBlank() || selectedCategory != null)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.id.toString() }) { entry ->
                        SwipeActionsRow(
                            onEdit = { navController.navigate("edit/${entry.id}") },
                            onDelete = { viewModel.deleteEntry(entry.id) }
                        ) {
                            Card(
                                onClick = { navController.navigate("detail/${entry.id}") },
                                // 圓角形狀交給 SwipeActionsRow 最外層的 clip 統一處理（見那邊的
                                // 註解）：卡片自己不裁圓角，收起來時外層 clip 剛好蓋出跟一般卡片
                                // 一樣的四角圓角；拉開時卡片右邊界露出來的是直角接縫，不會有卡片
                                // 自己裁出的圓角在拉開後卡在中間變成一個奇怪的浮動缺角。
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.VpnKey,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (entry.category.isNotBlank()) entry.category
                                            else entry.modifiedAt.toDisplayString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyEntriesMessage(hasFilter: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.VpnKeyOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            if (hasFilter) "沒有符合條件的提示" else "還沒有任何提示，點右下角新增第一筆",
            color = MaterialTheme.colorScheme.outline
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
private fun SwipeActionsRow(onEdit: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
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

    suspend fun settleTo(target: Float) {
        settleAnim.snapTo(offsetPx)
        settleAnim.animateTo(target, animationSpec = SwipeSettleSpec) { offsetPx = value }
    }

    fun close() {
        scope.launch { settleTo(0f) }
    }

    // clip 放在最外層，讓卡片＋兩顆按鈕被裁成同一個圓角矩形，整組是「連在一起
    // 的一個整體」：不管拉開多少，只有最外緣（卡片左邊、刪除鈕右邊）是圓角，
    // 中間（卡片—編輯、編輯—刪除）維持無縫直角。卡片自己不裁圓角（見下面
    // Card 的 shape = RectangleShape），全部交給這一層 clip 統一決定，才不會有
    // 卡片自己裁出的圓角在拉開後卡在中間變成浮動缺角。
    Box(
        Modifier
            .fillMaxWidth()
            .clip(EntryCardShape)
    ) {
        Row(
            Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            SwipeActionButton(
                label = "編輯",
                icon = Icons.Filled.Edit,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    close()
                    onEdit()
                }
            )
            SwipeActionButton(
                label = "刪除",
                icon = Icons.Filled.Delete,
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
                    onDragStopped = {
                        // 門檻壓到 28%：滑一點點就整組打開，不用滑到快一半才有反應。
                        val target = if (offsetPx < -revealPx * 0.28f) -revealPx else 0f
                        settleTo(target)
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape
) {
    // 圖示單獨就能表意（鉛筆＝編輯、垃圾桶＝刪除），拿掉中文字讓兩顆按鈕更精簡；
    // label 還是留著當 contentDescription，螢幕報讀器需要的文字說明沒有少。
    Column(
        Modifier
            .width(SwipeActionWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
    }
}
