package com.passprotect.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 石墨配色的色值與對比度——色值照規格 2.1 的表，對比度門檻是 WCAG AA 的 4.5:1。
 * 主題換色後最容易在不知不覺中壞掉的就是「灰字在灰底上看不清楚」，這裡直接釘住。
 */
class GraphiteThemeTest {

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun darkBackgroundIsGraphite() = assertEquals(Color(0xFF0B0C0E), GraphiteDarkColors.background)

    @Test
    fun lightCardIsWhite() = assertEquals(Color(0xFFFFFFFF), GraphiteLightColors.surfaceContainer)

    @Test
    fun labelPassesAaOnBackgroundInBothModes() {
        assertTrue(contrast(GraphiteDarkExtra.label, GraphiteDarkColors.background) >= 4.5f)
        assertTrue(contrast(GraphiteLightExtra.label, GraphiteLightColors.background) >= 4.5f)
    }

    @Test
    fun valuesPassAaOnCards() {
        assertTrue(contrast(GraphiteDarkColors.onSurfaceVariant, GraphiteDarkColors.surfaceContainer) >= 4.5f)
        assertTrue(contrast(GraphiteLightColors.onSurfaceVariant, GraphiteLightColors.surfaceContainer) >= 4.5f)
    }

    @Test
    fun primaryButtonTextIsReadable() {
        assertTrue(contrast(GraphiteDarkColors.onPrimary, GraphiteDarkColors.primary) >= 4.5f)
        assertTrue(contrast(GraphiteLightColors.onPrimary, GraphiteLightColors.primary) >= 4.5f)
    }

    /**
     * 規格：整個 app 只有刪除用的紅色是彩色。只設定規格表裡那幾個角色的話，沒設定的
     * 角色（primaryContainer、secondary、*Fixed…）會退回 Material 預設的紫色，任何
     * 用預設配色的元件（例如網址搜尋頁的 FAB 用 primaryContainer）就會冒出紫色。
     * 用反射掃過 ColorScheme 的每一個顏色，避免之後 Material 新增角色時又漏掉。
     */
    @Test
    fun everyNonErrorRoleIsNeutral() {
        for ((name, scheme) in listOf("dark" to GraphiteDarkColors, "light" to GraphiteLightColors)) {
            val roles = scheme.javaClass.methods.filter {
                it.name.startsWith("get") && it.parameterCount == 0 &&
                    it.returnType == Long::class.javaPrimitiveType && !it.name.contains("Error")
            }
            assertTrue("no color roles found", roles.size > 20)
            for (role in roles) {
                val color = Color((role.invoke(scheme) as Long).toULong())
                val chroma = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
                assertTrue("$name ${role.name.substringBefore('-')} is chromatic: $color", chroma <= 0.06f)
            }
        }
    }

    @Test
    fun tertiaryMirrorsPrimary() {
        assertEquals(GraphiteDarkColors.primary, GraphiteDarkColors.tertiary)
        assertEquals(GraphiteLightColors.primary, GraphiteLightColors.tertiary)
    }
}
