package com.passprotect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.passprotect.ui.theme.PassProtectTheme

/**
 * 提示本身——整個 app 真正的重點。底色比所在的卡片深一階，像一張夾在卡片裡的
 * 紙條；等寬大字跟編輯畫面的提示欄位一致，一眼認得出「這是密語」。
 *
 * 不設 `maxLines`：提示被截斷就失去意義，再長都完整換行。
 */
@Composable
fun HintSlip(hint: String, modifier: Modifier = Modifier, label: String = "提示", fontSize: TextUnit = 20.sp) {
    val shape = MaterialTheme.shapes.small
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
            color = PassProtectTheme.extraColors.label
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = fontSize,
                lineHeight = fontSize * 1.5f
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
