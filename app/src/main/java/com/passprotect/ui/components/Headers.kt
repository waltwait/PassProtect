package com.passprotect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.passprotect.ui.theme.PassProtectTheme

/**
 * 頂列只放圖示按鈕（返回／搜尋／鎖定／設定），標題不擠在這裡，改在下面用
 * [LargeTitleHeader] 大字顯示、跟著內容一起捲走。編輯頁例外，用 [centerTitle]
 * 放一個置中的小標（「新增」「編輯」），因為右邊有「儲存」要對稱。主畫面用
 * [startTitle]：大標題捲走時，同一個標題在左邊淡入（[startTitleAlpha] 在繪製階段讀，
 * 捲動時不必每一格都重組）。
 *
 * app 是 edge-to-edge（targetSdk 36），頂列自己吃掉狀態列高度，原本這件事是
 * Material 的 `TopAppBar` 代勞的。
 */
@Composable
fun IconTopBar(
    onBack: (() -> Unit)? = null,
    centerTitle: String? = null,
    startTitle: String? = null,
    startTitleAlpha: () -> Float = { 1f },
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 16.dp, start = 10.dp, end = 10.dp)
            .heightIn(min = 44.dp)
    ) {
        if (onBack != null) {
            TopBarIconButton(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }
        if (centerTitle != null) {
            Text(centerTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
        }
        if (startTitle != null) {
            Text(
                startTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .graphicsLayer { alpha = startTitleAlpha() }
                    // 下面的大標題已經唸過一次，這個小標只是視覺上的，不重複唸。
                    .clearAndSetSemantics {}
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun TopBarIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 每頁頂部的大標題。[large] 只給主畫面（32sp），其他頁 30sp。[leading] 是標題
 * 左邊的 52dp 方塊（分類頁放資料夾圖示）。標題最多兩行，再長就省略。
 */
@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    large: Boolean = false
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (leading != null) {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .size(52.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                contentAlignment = Alignment.Center
            ) { leading() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PassProtectTheme.extraColors.label
                )
            }
        }
    }
}

/** 「資料夾」「未分類」「帳號」這類區段標籤，右邊可選的數量（「2 組」）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = PassProtectTheme.extraColors.label)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = PassProtectTheme.extraColors.label)
        }
    }
}
