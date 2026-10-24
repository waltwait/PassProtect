@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.passprotect.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import java.util.UUID

/**
 * 「點的東西長成下一頁」的共享元素轉場（規格 §5）：資料夾 → 分類頁、清單列 → 詳情、
 * 「＋」→ 新增頁。`PassProtectApp` 用 `SharedTransitionLayout` 包住整個 `NavHost`，
 * 各目的地再把自己的 `AnimatedContentScope` 放進 [LocalNavAnimatedScope]，畫面裡
 * 只要掛上下面其中一個 modifier 跟同一把 key 就會接起來。
 *
 * 兩個 scope 任一個拿不到（例如之後某個元件被拿去別的地方用）就原樣回傳，不會壞。
 *
 * 轉場途中，兩頁上配對到的東西都搬到 overlay 去畫，跟原本所在的畫面脫鉤：換頁的淡入
 * 淡出管不到它們，外層的裁切（例如清單列的圓角）也管不到，原本的位置則空出來。所以
 * 每一種角色要自己講清楚怎麼縮放、要不要淡、疊在哪一層——都用預設值的話，整頁會
 * 幾乎原尺寸地滑向被點的那一列再自己淡掉（關閉時「閃一下就下去」），「＋」會被等比
 * 放大成一大塊色塊。
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

object SharedKeys {
    fun folderTile(name: String) = "folder-tile-$name"
    fun folderIcon(name: String) = "folder-icon-$name"
    fun folderTitle(name: String) = "folder-title-$name"
    fun folderCount(name: String) = "folder-count-$name"
    fun entryBounds(id: UUID) = "entry-bounds-$id"
    fun entryTitle(id: UUID) = "entry-title-$id"
    const val NEW_ENTRY = "new-entry"
}

// 石墨的動態個性是俐落、不回彈：臨界阻尼彈簧，元素滑到定位就停，不晃。
private val GraphiteBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

// overlay 裡的疊放順序（數字大的畫在上面）。整頁、資料夾方塊這類沒有上層的共享元素
// 用 0，函式庫會把它排到 -1 最底層；被點的那一列／FAB 疊在整頁上面淡入淡出；文字跟
// 圖示永遠在最上面。不指定的話同一層誰先畫看註冊順序，分類名稱可能被不透明的資料夾
// 方塊蓋住，轉場途中消失一下又冒出來。
private const val PageLayer = 0f
private const val SourceLayer = 0.5f
private const val ContentLayer = 1f

// 整頁：等比縮到剛好蓋滿外框、超出的部分裁掉（Crop 會開裁切，其他縮放方式不會），
// 頂端對齊——外框從那一列長大時，頁面像從那一列裡面往下展開。
private val PageResize = ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.TopCenter)

/** 兩頁上「同一個東西」（資料夾圖示）：直接從舊位置移動、縮放到新位置。 */
@Composable
fun Modifier.sharedElementOrSelf(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedElementOrSelf.sharedElement(
            rememberSharedContentState(key),
            animated,
            boundsTransform = GraphiteBoundsTransform,
            zIndexInOverlay = ContentLayer
        )
    }
}

/**
 * 從被點的東西長出來的整頁（詳情頁、新增頁），整頁本身就是那個變大變小的外框。
 *
 * 不淡入淡出：外框一淡掉就看不到它在縮，關閉時只剩被點的那一列在淡入。打開時被點
 * 的東西疊在上面淡出、露出底下這一頁；關閉時這一頁一路不透明地縮回去，被點的東西
 * 在上面淡入接手。頁裡的內容另外用 [sharedPageContentOrSelf] 淡入淡出。
 */
@Composable
fun Modifier.sharedPageOrSelf(key: String): Modifier =
    sharedBoundsOrSelf(key, PageResize, EnterTransition.None, ExitTransition.None, PageLayer, clip = null)

// 整頁內容的 fade-through 時序：打開時等外框長到大半才淡入，關閉時一開始就淡出。
private const val PageContentEnterDelayMillis = 90
private const val PageContentEnterMillis = 180
private const val PageContentExitMillis = 90

/**
 * [sharedPageOrSelf] 那一頁裡的內容（頂列、內文），掛在 Scaffold 的頂列與內容上，不掛在
 * Scaffold 本身——整頁的底色就是外框，要一路不透明地長大縮小。
 *
 * 內容照 Material 容器轉換的 fade-through：打開時等外框長到大半才淡入，關閉時一開始就先
 * 淡出。不這樣做的話內容一路看得到，返回鍵跟頂列會貼著外框上緣，從被點的那一列一路滑上去；
 * 關閉時整頁又縮小、跟著滑回那一列。共享的標題文字另外在 overlay 裡飛，不受這裡影響。
 *
 * 只在共享元素轉場進行中才作用；沒有配對到的一般換頁（例如詳情 → 舊提示）照舊只有換頁淡入淡出。
 */
@Composable
fun Modifier.sharedPageContentOrSelf(): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    val alpha by animated.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(PageContentEnterMillis, delayMillis = PageContentEnterDelayMillis, easing = LinearOutSlowInEasing)
            } else {
                tween(PageContentExitMillis, easing = FastOutLinearInEasing)
            }
        },
        label = "page-content-alpha"
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    return graphicsLayer { this.alpha = if (shared.isTransitionActive) alpha else 1f }
}

/**
 * 長成整頁的那個東西（清單列、FAB）：疊在 [sharedPageOrSelf] 上面，打開時淡出、關閉時淡入。
 *
 * [contentScale]／[alignment] 決定它在變大的外框裡怎麼擺：清單列等比放大到外框寬、
 * 貼著頂端（詳情頁的標題列就在那裡）；FAB 不縮放、留在右下角原地淡掉——跟著外框
 * 等比放大的話，60dp 的按鈕會被撐成一整塊色塊。[clip] 是它原本的外形：移到 overlay
 * 之後，外層替它裁的圓角就不在了。
 */
@Composable
fun Modifier.sharedPageSourceOrSelf(
    key: String,
    contentScale: ContentScale,
    alignment: Alignment,
    clip: Shape? = null
): Modifier = sharedBoundsOrSelf(
    key,
    ResizeMode.scaleToBounds(contentScale, alignment),
    fadeIn(),
    fadeOut(),
    SourceLayer,
    clip
)

/**
 * 兩頁上長得一樣、只差大小的方塊（資料夾磚 ↔ 分類頁標題旁的方塊）。每一格都用動畫中
 * 的大小重新量、重新畫，圓角跟 1dp 邊框維持原本的粗細，不會跟著被放大縮小；兩邊都
 * 不透明、不交叉淡化——兩個一樣的方塊各自半透明疊在一起，中途反而會變淡。
 */
@Composable
fun Modifier.sharedBoxOrSelf(key: String): Modifier =
    sharedBoundsOrSelf(key, ResizeMode.RemeasureToBounds, EnterTransition.None, ExitTransition.None, PageLayer, clip = null)

/**
 * 兩頁上同一段文字（資料夾名稱、筆數、記錄標題），字級不同：交叉淡化、等比縮放、
 * 靠左對齊，永遠疊在方塊與整頁上面。
 *
 * 預設 [ContentScale.FillHeight]：照行高的比例放大縮小。照寬度（FillWidth）的話，
 * 兩邊字不一樣時會被拉變形——資料夾磚上被截成「很長的名…」的名稱，會被放大到整個
 * 標題的寬度、變成幾倍大的字；「3 筆」也會被撐到「3 筆提示」那麼寬。
 */
@Composable
fun Modifier.sharedTextOrSelf(key: String, contentScale: ContentScale = ContentScale.FillHeight): Modifier =
    sharedBoundsOrSelf(
        key,
        ResizeMode.scaleToBounds(contentScale, Alignment.CenterStart),
        fadeIn(),
        fadeOut(),
        ContentLayer,
        clip = null
    )

@Composable
private fun Modifier.sharedBoundsOrSelf(
    key: String,
    resizeMode: ResizeMode,
    enter: EnterTransition,
    exit: ExitTransition,
    zIndex: Float,
    clip: Shape?
): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        // 沒有自己的外形時沿用預設的裁切（跟著外層的共享外框裁）；那個預設值沒有公開，
        // 只能不傳。
        if (clip == null) {
            this@sharedBoundsOrSelf.sharedBounds(
                state, animated,
                enter = enter, exit = exit,
                boundsTransform = GraphiteBoundsTransform,
                resizeMode = resizeMode,
                zIndexInOverlay = zIndex
            )
        } else {
            this@sharedBoundsOrSelf.sharedBounds(
                state, animated,
                enter = enter, exit = exit,
                boundsTransform = GraphiteBoundsTransform,
                resizeMode = resizeMode,
                zIndexInOverlay = zIndex,
                clipInOverlayDuringTransition = OverlayClip(clip)
            )
        }
    }
}
