package com.passprotect.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passprotect.ui.theme.PassProtectTheme

private val PillShape = RoundedCornerShape(50)

/** 頂列右邊的「儲存」：實心、全圓角。按壓縮放內建，呼叫端不用自己接 interactionSource。 */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.primary.copy(alpha = 0.38f),
            disabledContentColor = colors.onPrimary.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 38.dp)
            .pressScale(interactionSource),
        content = content
    )
}

/** 全寬的主要動作（「建立資料庫」「解鎖」「變更」）。 */
@Composable
fun PrimaryWideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .pressScale(interactionSource)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

/** 次要動作（「查看歷史」）：比卡片亮一階的實心底，不搶主要按鈕的視覺重量。 */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal))
    }
}

/** 常用提示詞：點了接到提示後面，所以字前面帶「＋」。 */
@Composable
fun SnippetChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 可點的 Surface 會自己把觸控範圍撐到 48dp，畫出來的膠囊維持 32dp 高。
    Surface(
        onClick = onClick,
        shape = PillShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, PassProtectTheme.extraColors.borderStrong),
        modifier = modifier.heightIn(min = 32.dp)
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                "＋ $text",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 設定頁的常用提示詞：整顆都能點，點了就刪掉（同原本 InputChip 的行為）。 */
@Composable
fun RemovableChip(text: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onRemove,
        shape = PillShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, PassProtectTheme.extraColors.borderStrong),
        modifier = modifier.heightIn(min = 32.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                Icons.Rounded.Close,
                contentDescription = "刪除「$text」",
                tint = PassProtectTheme.extraColors.label,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** 「新增帳號」：虛線框，看起來是「還沒有東西的位置」而不是另一張卡片。 */
@Composable
fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val borderColor = PassProtectTheme.extraColors.borderStrong
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                    )
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 互斥的幾個選項（自動上鎖 1／5／15 分鐘）。選中段的底色有短暫淡入，不是瞬間跳。 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val background by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "segment-background"
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(background)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
