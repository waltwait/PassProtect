package com.passprotect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.passprotect.ui.theme.PassProtectTheme

/**
 * 一列在「群組清單」裡的位置。群組清單是一個圓角容器裡上下相連的幾列：只有
 * 頭尾兩列帶圓角，中間是直角接縫，列與列之間一條細線。
 *
 * 不用「外層容器 + 底色當分隔線」的做法：`LazyColumn` 的每一列是獨立 item，
 * 外面套不了一個共同容器。改成每列自己知道位置、自己裁圓角、自己畫上方分隔線，
 * lazy 跟一般 `Column` 兩種清單長得一模一樣。
 */
enum class GroupPosition {
    Only, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int): GroupPosition = when {
            count <= 1 -> Only
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

fun GroupPosition.shape(radius: Dp = 16.dp): Shape = when (this) {
    GroupPosition.Only -> RoundedCornerShape(radius)
    GroupPosition.First -> RoundedCornerShape(topStart = radius, topEnd = radius)
    GroupPosition.Middle -> RectangleShape
    GroupPosition.Last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
}

/** 跟在前一列後面的列（中間、最後）要在頂端畫分隔線。 */
internal val GroupPosition.hasDividerAbove: Boolean
    get() = this == GroupPosition.Middle || this == GroupPosition.Last

/**
 * 群組清單的一列。左右外距由呼叫端決定（畫面上的容器一律離邊 16dp），這裡只管
 * 圓角、底色、分隔線與列內距。
 */
@Composable
fun GroupedRow(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(position.shape())
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        if (position.hasDividerAbove) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** 設定頁的開關列：整列都能點，不用瞄準右邊那顆小開關。 */
@Composable
fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: GroupPosition,
    modifier: Modifier = Modifier
) {
    GroupedRow(
        position = position,
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelMedium,
                color = PassProtectTheme.extraColors.label
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedThumbColor = PassProtectTheme.extraColors.label,
                uncheckedBorderColor = PassProtectTheme.extraColors.borderStrong
            )
        )
    }
}
