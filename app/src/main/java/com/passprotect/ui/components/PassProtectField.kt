package com.passprotect.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 取代 Material3 預設 `OutlinedTextField` 的自訂輸入框：標題固定顯示在上方（不是
 * 飄浮進框內的 label），底色用實心的 surface 而不是描邊，聚焦時底線變金色變粗。
 * `monospace = true` 給提示欄位用，強調「這是密碼/密語」的感覺。
 */
@Composable
fun PassProtectField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // 強調色（聚焦底線、游標、focused label）直接取主題的 tertiary，不是另外寫死
    // 一個金色常數——這樣主題色只有一個來源，改主題就整個 app 一起變。
    val accent = MaterialTheme.colorScheme.tertiary
    // 標題顏色、底線顏色、底線粗細是同一個「聚焦」狀態的三個面向，用同一個
    // transition 一起驅動——之前底線有動畫、標題卻是瞬間變色，兩者不同步。
    val focusTransition = updateTransition(focused, label = "field-focus")
    val labelColor by focusTransition.animateColor(label = "field-label-color") {
        if (it) accent else MaterialTheme.colorScheme.onSurfaceVariant
    }
    val underlineColor by focusTransition.animateColor(label = "field-underline-color") {
        if (it) accent else MaterialTheme.colorScheme.outlineVariant
    }
    val underlineWidth by focusTransition.animateDp(label = "field-underline-width") {
        if (it) 2.dp else 1.dp
    }

    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                // 固定高度，不是靠 vertical padding 撐出來——不然帶 trailingIcon
                // 的欄位（IconButton 最小觸控尺寸 48dp）會比純文字欄位高一截，
                // 這批欄位就會大小不一。52dp 涵蓋 48dp 的按鈕，同時比純文字內容
                // 需要的高度寬裕，所有欄位不管有沒有 icon 都會是同一個高度。
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawBehind {
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = underlineWidth.toPx()
                    )
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                it()
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = LocalTextStyle.current,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                // BasicTextField 的純 String 版本，只要 value 是被「外部」改掉的
                // （不是使用者自己打字，而是像常用提示詞 chip 那樣直接指定新字串），
                // 游標就會被重置到最前面——這裡改成自己管一份 TextFieldValue，
                // 偵測到外部改動時明確把游標擺到新文字的最後面，符合「點了提示詞、
                // 接在後面」的直覺（游標理應跟著新接上去的字走，不是跳回開頭）。
                var textFieldValue by remember {
                    mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
                }
                if (textFieldValue.text != value) {
                    textFieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        if (newValue.text != value) onValueChange(newValue.text)
                    },
                    enabled = enabled,
                    singleLine = singleLine,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                    ),
                    cursorBrush = SolidColor(accent),
                    interactionSource = interactionSource,
                    // contentDescription 直接掛在這個欄位自己的語意節點上（不是跨節點合併
                    // label 進來）——跟 Android 原生 EditText 的 hint 概念一樣，這個屬性
                    // 才不會影響 BasicTextField 自己本來就有的 RequestFocus / SetText 等
                    // action。之前試過用 ancestor 的 mergeDescendants 把 label 併進來，
                    // 結果反而把 BasicTextField 的 RequestFocus 弄丟了（測試裡
                    // performTextInput 因此失敗），改成這樣直接掛在同一個節點上就沒有這個問題。
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = label }
                )
            }
            trailingIcon?.let {
                Spacer(Modifier.width(10.dp))
                it()
            }
        }
    }
}
