@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.passprotect.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import java.util.UUID

/**
 * 「點的東西長成下一頁」的共享元素轉場（規格 §5）：資料夾 → 分類頁、清單列 → 詳情、
 * 「＋」→ 新增頁。`PassProtectApp` 用 `SharedTransitionLayout` 包住整個 `NavHost`，
 * 各目的地再把自己的 `AnimatedContentScope` 放進 [LocalNavAnimatedScope]，畫面裡
 * 只要掛上 [sharedElementOrSelf] / [sharedBoundsOrSelf] 跟同一把 key 就會接起來。
 *
 * 兩個 scope 任一個拿不到（例如之後某個元件被拿去別的地方用）就原樣回傳，不會壞。
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

object SharedKeys {
    fun folderIcon(name: String) = "folder-icon-$name"
    fun folderTitle(name: String) = "folder-title-$name"
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

/** 兩頁上「同一個東西」（資料夾圖示、標題文字）：直接從舊位置移動、縮放到新位置。 */
@Composable
fun Modifier.sharedElementOrSelf(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedElementOrSelf.sharedElement(
            rememberSharedContentState(key),
            animated,
            boundsTransform = GraphiteBoundsTransform
        )
    }
}

/**
 * 兩頁上「同一塊區域、內容不同」（清單列 → 整個詳情頁、FAB → 新增頁）：外框從舊的
 * 大小長成新的大小，裡面的內容交叉淡化。
 */
@Composable
fun Modifier.sharedBoundsOrSelf(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedBoundsOrSelf.sharedBounds(
            rememberSharedContentState(key),
            animated,
            enter = fadeIn(),
            exit = fadeOut(),
            boundsTransform = GraphiteBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
        )
    }
}
