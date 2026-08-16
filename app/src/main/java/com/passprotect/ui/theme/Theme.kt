package com.passprotect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 固定配色，不用 API 31+ 的桌布動態取色（`dynamicColorScheme`）。密碼保險庫
 * app 刻意不跟著桌布變色：每台裝置、每次換桌布都要長一樣，卡片跟背景的
 * 層次也才不會被桌布配色吃掉變成一片灰。
 *
 * 配色一路從深藍 → 中性灰 → 現在的灰白（拿掉暖金強調色），理由：灰白／
 * 銀白比金色更貼近「保險庫」這個主題本身——金庫、保險箱實際上是拋光鋼鐵
 * 的顏色，不是黃金；金色反而比較像珠寶廣告。整個 app 現在只有一個真正
 * 「有顏色」的地方（刪除用的紅），其餘全部是中性色階，這樣那個紅色才會
 * 顯得是刻意的警示，不是眾多強調色之一。
 */
private val FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF3D3D3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E4E1),
    onPrimaryContainer = Color(0xFF1A1A18),
    secondary = Color(0xFF6B6B68),
    tertiary = Color(0xFF1A1A18),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E4E1),
    onTertiaryContainer = Color(0xFF1A1A18),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1A1A18),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1A1A18),
    surfaceContainerLow = Color(0xFFF1F0EE),
    surfaceContainer = Color(0xFFEAE9E6),
    surfaceContainerHigh = Color(0xFFE2E1DE),
    surfaceContainerHighest = Color(0xFFDBDAD7),
    outline = Color(0xFF8D8D89),
    outlineVariant = Color(0xFFCCCBC7),
    error = Color(0xFFBA1B1B),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410001),
)

private val FallbackDarkColorScheme = darkColorScheme(
    // 灰白／銀白色主色——不是純灰，帶一點點暖中性調（跟 D8D8D5 這種數值一樣，
    // R=G=B 差在個位數），比死板的正灰（AAAAAA 那種）看起來更像「拋光金屬」
    // 而不是「螢幕沒訊號的灰畫面」。
    primary = Color(0xFFD6D6D3),
    onPrimary = Color(0xFF1A1A18),
    primaryContainer = Color(0xFF3D3D3B),
    onPrimaryContainer = Color(0xFFE8E8E5),
    secondary = Color(0xFFA8A8A5),
    // tertiary 比 primary 更亮、更接近純白——欄位聚焦、選中狀態用這個，
    // 跟一般按鈕的 primary 銀灰分出一點層次，不然全部同一個灰會分不清楚
    // 「這是按鈕」還是「這是現在聚焦的東西」。
    tertiary = Color(0xFFEDEDEA),
    onTertiary = Color(0xFF1A1A18),
    tertiaryContainer = Color(0xFF3D3D3B),
    onTertiaryContainer = Color(0xFFEDEDEA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F4),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF5F5F4),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFF6B6B68),
    outlineVariant = Color(0xFF3A3A38),
    // 原本的淡珊瑚色只夠當文字/圖示色調，拿去當滑動刪除鈕的實心底色會太淡、
    // 警示感不夠；換成飽和一點的紅，文字色調跟實心填色都能共用同一個值，
    // 不用再另外寫一個沒有跟主題掛鉤的紅色常數。全 app 唯一的彩色。
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD5),
)

@Composable
fun PassProtectTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) FallbackDarkColorScheme else FallbackLightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
