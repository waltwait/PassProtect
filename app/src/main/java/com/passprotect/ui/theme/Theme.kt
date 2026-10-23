package com.passprotect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 固定配色，不用 API 31+ 的桌布動態取色（`dynamicColorScheme`）。密碼保險庫
 * app 刻意不跟著桌布變色：每台裝置、每次換桌布都要長一樣，卡片跟背景的
 * 層次也才不會被桌布配色吃掉變成一片灰。
 *
 * 配色一路從深藍 → 中性灰 → 灰白，2026-10 再從三個重新設計方向（石墨、午夜藍、
 * 紙本帳冊）裡選定「石墨」：灰階＋銀白，比金色更貼近「保險庫」這個主題本身——
 * 金庫、保險箱實際上是拋光鋼鐵的顏色。整個 app 仍然只有一個真正「有顏色」的
 * 地方（刪除用的紅），其餘全部是中性色階，這樣那個紅色才會顯得是刻意的警示。
 * 質感改靠層次（底色／卡片／輸入框三階）、細邊框與大標題來做，不靠加顏色。
 *
 * 色值對照見 `docs/superpowers/specs/2026-10-23-graphite-redesign-design.md` §2.1。
 */
internal val GraphiteDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFF2F2F0),
    onPrimary = Color(0xFF0B0C0E),
    // tertiary 不再當強調色用（聚焦色改讀 primary）。設成跟 primary 同值，
    // 漏改、還在讀 tertiary 的地方至少不會冒出舊配色。
    tertiary = Color(0xFFF2F2F0),
    onTertiary = Color(0xFF0B0C0E),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFF2F2F0),
    surface = Color(0xFF0B0C0E),
    onSurface = Color(0xFFF2F2F0),
    onSurfaceVariant = Color(0xFFC9CACE),
    surfaceContainer = Color(0xFF141518),
    surfaceContainerHigh = Color(0xFF1C1D21),
    outlineVariant = Color(0xFF23252A),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
)

internal val GraphiteLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF141518),
    onPrimary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF141518),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F4F2),
    onBackground = Color(0xFF141518),
    surface = Color(0xFFF4F4F2),
    onSurface = Color(0xFF141518),
    onSurfaceVariant = Color(0xFF3E4046),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFEFED),
    outlineVariant = Color(0xFFE3E3E0),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

/**
 * Material 3 沒有對應角色的兩個顏色：[label] 給區段標籤、說明、日期、資料夾圖示；
 * [borderStrong] 給展開中的帳號卡、虛線「新增帳號」框、chip 的邊框。硬塞進
 * `outline` 之類的角色會讓同一個角色同時當文字色又當邊框色，改一個就壞另一個。
 */
@Immutable
data class PassProtectExtraColors(val label: Color, val borderStrong: Color)

internal val GraphiteDarkExtra = PassProtectExtraColors(label = Color(0xFF8E9096), borderStrong = Color(0xFF34363C))
internal val GraphiteLightExtra = PassProtectExtraColors(label = Color(0xFF6A6C72), borderStrong = Color(0xFFC9C9C5))

private val LocalExtraColors = staticCompositionLocalOf { GraphiteDarkExtra }

/** 跟 `@Composable fun PassProtectTheme` 同名並存，用法同 `MaterialTheme.colorScheme`。 */
object PassProtectTheme {
    val extraColors: PassProtectExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

private val Tight = 0.02.em
private val Tracked = 0.12.em

internal val GraphiteTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = Tight),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = Tight),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = Tracked),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = Tracked),
)

internal val GraphiteShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun PassProtectTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtraColors provides if (dark) GraphiteDarkExtra else GraphiteLightExtra) {
        MaterialTheme(
            colorScheme = if (dark) GraphiteDarkColors else GraphiteLightColors,
            typography = GraphiteTypography,
            shapes = GraphiteShapes,
            content = content
        )
    }
}
