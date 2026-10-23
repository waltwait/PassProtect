package com.passprotect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.VpnKeyOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.passprotect.ui.VaultViewModel
import com.passprotect.ui.format.toDisplayString
import com.passprotect.vault.Entry
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val EntryCardShape = RoundedCornerShape(14.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(entries: List<Entry>, navController: NavController, viewModel: VaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val categories = remember(entries) {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val uncategorized = remember(entries) {
        entries.filter { it.category.isBlank() }.sortedBy { it.title }
    }
    // 搜尋是跨所有分類的（像 Spotlight，不是只在目前資料夾裡找）——分類現在是
    // 獨立的畫面（見 CategoryEntriesScreen），主畫面搜尋要能一次找到所有記錄，
    // 不用先猜是哪個資料夾、點進去才搜得到。
    val searchResults = remember(entries, query) {
        if (query.isBlank()) emptyList()
        else entries.filter { it.title.contains(query, ignoreCase = true) }.sortedBy { it.title }
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
                .imePadding()
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
            when {
                query.isNotBlank() -> {
                    if (searchResults.isEmpty()) {
                        EmptyEntriesMessage(hasFilter = true)
                    } else {
                        // 搜尋結果每打一個字就變一次，這裡不做增刪動畫，結果要即時跟上打字。
                        EntryList(searchResults, navController, viewModel, animateRowChanges = false)
                    }
                }
                entries.isEmpty() -> EmptyEntriesMessage(hasFilter = false)
                else -> {
                    // 分類改成 Finder 式的資料夾格狀顯示：點資料夾換頁看該分類底下的
                    // 記錄（見 CategoryEntriesScreen），沒有分類的記錄直接列在資料夾
                    // 下方——跟 Finder 資料夾／散落檔案並存同一層的邏輯一樣。
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (categories.isNotEmpty()) {
                            item {
                                CategoryFolderGrid(
                                    categories = categories,
                                    onSelect = { name -> navController.navigate("category/$name") }
                                )
                            }
                        }
                        if (categories.isNotEmpty() && uncategorized.isNotEmpty()) {
                            item {
                                Text(
                                    "未分類",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(uncategorized, key = { it.id.toString() }) { entry ->
                            EntryCard(entry, navController, viewModel, Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

/**
 * Finder 式的資料夾格狀顯示：灰色資料夾圖示＋名稱，固定一排三個。用三等分
 * 欄位（每欄 weight(1f)）排版，不是靠 FlowRow 自動換行——這樣每個資料夾左右
 * 兩側的留白永遠相等，不會因為名稱長短不一而擠成一團或留白不對稱；最後一排
 * 不滿三個時，用等寬的空白 Spacer 補位，維持同一套三欄格線。
 */
@Composable
private fun CategoryFolderGrid(categories: List<String>, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        categories.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { name ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CategoryFolderTile(name = name, onClick = { onSelect(name) })
                    }
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryFolderTile(name: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            // 灰色，跟主題本身的灰白配色一致——不是 Finder 原本那種藍色。
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 點分類資料夾換頁進來的畫面：只顯示這個分類底下的記錄，跟主畫面共用同一套
 * 卡片／滑動編輯刪除元件（見 EntryList／EntryCard）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryName) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (filtered.isEmpty()) {
                EmptyEntriesMessage(hasFilter = true)
            } else {
                EntryList(filtered, navController, viewModel)
            }
        }
    }
}

/**
 * [animateRowChanges]：刪除／新增一筆時，被刪的那列淡出、下面的列滑上來補位，
 * 不是整串瞬間跳一格。
 */
@Composable
private fun EntryList(
    entries: List<Entry>,
    navController: NavController,
    viewModel: VaultViewModel,
    animateRowChanges: Boolean = true
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entries, key = { it.id.toString() }) { entry ->
            EntryCard(
                entry, navController, viewModel,
                if (animateRowChanges) Modifier.animateItem() else Modifier
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    navController: NavController,
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier
) {
    SwipeActionsRow(
        onEdit = { navController.navigate("edit/${entry.id}") },
        onDelete = { viewModel.deleteEntry(entry.id) },
        modifier = modifier
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
private fun SwipeActionsRow(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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

    // clip 放在最外層，讓卡片＋兩顆按鈕被裁成同一個圓角矩形，整組是「連在一起
    // 的一個整體」：不管拉開多少，只有最外緣（卡片左邊、刪除鈕右邊）是圓角，
    // 中間（卡片—編輯、編輯—刪除）維持無縫直角。卡片自己不裁圓角（見下面
    // Card 的 shape = RectangleShape），全部交給這一層 clip 統一決定，才不會有
    // 卡片自己裁出的圓角在拉開後卡在中間變成浮動缺角。
    Box(
        modifier
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
