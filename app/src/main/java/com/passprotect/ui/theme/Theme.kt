package com.passprotect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * API 29/30 沒有桌布動態取色（`dynamicColorScheme` 需要 API 31+），用固定配色
 * 當後備——以圖示的深藍（`ic_launcher_background` 的 #1E2A5E）當主色，維持
 * app 內外視覺一致。
 */
private val FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF3452B4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001355),
    secondary = Color(0xFF5B5D72),
    background = Color(0xFFFAF8FF),
    surface = Color(0xFFFAF8FF),
)

private val FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7C3FF),
    onPrimary = Color(0xFF152276),
    primaryContainer = Color(0xFF1E2A5E),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC4C5DD),
    background = Color(0xFF121317),
    surface = Color(0xFF121317),
)

@Composable
fun PassProtectTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
