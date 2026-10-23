package com.passprotect.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按下時輕微縮小再彈回——只用在主要 CTA（儲存、新增）上，數量要少才會覺得
 * 是「有互動感」而不是「到處都在動」。
 *
 * 按下要立刻到位（硬、無回彈的彈簧），放開才帶一點點回彈；用彈簧而不是 tween，
 * 快速連點時中途換方向會延續當下的速度，不會每次都從頭重播。縮放只到 0.96：
 * 按壓回饋是「感覺得到」就好，縮太多視線會被縮小這件事本身拉走。
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = if (pressed) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "press-scale"
    )
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}
