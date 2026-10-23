package com.passprotect.ui

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分類名稱是使用者自己打的字，放進 "category/{categoryName}" 之前要編碼；分類頁讀回來的
 * 名字要跟資料夾上的一字不差，篩選和共享元素的 key（SharedKeys.folderIcon/folderTitle）
 * 才對得上。
 */
class NavRoutesTest {

    private val names = listOf("金融", "a/b", "a?b", "50%", "#tag", "網路 銀行", "a+b", "a%2Fb")

    /**
     * Navigation 比對路徑參數時會先 `Uri.decode` 一次（NavDeepLink.getMatchingPathArguments），
     * 目的地的 arguments 拿到的已經是解碼後的字串。JVM 測試裡沒有 android.net.Uri，這裡用
     * URLDecoder 代替：路徑段裡沒有 `+` 時，兩者對 `%XX` 的解法一樣。
     */
    private fun navigationDecodes(segment: String) = URLDecoder.decode(segment, "UTF-8")

    private fun segmentOf(route: String) = route.removePrefix("category/")

    @Test
    fun categoryNameRoundTripsUnchanged() {
        for (name in names) {
            val arg = navigationDecodes(segmentOf(categoryRoute(name)))
            assertEquals("「$name」來回一趟", name, categoryNameFromArg(arg))
        }
    }

    @Test
    fun routeSegmentHasNothingNavigationReadsAsSyntax() {
        for (name in names) {
            val route = categoryRoute(name)
            assertTrue(route, route.startsWith("category/"))
            // `/` 會多切一段路徑、`?` `#` 會被當成查詢字串和片段、`+` `%` 會被解碼成別的字。
            assertTrue(route, Regex("[A-Za-z0-9._*~-]|%[0-9A-F]{2}").replace(segmentOf(route), "").isEmpty())
        }
    }

    @Test
    fun missingArgumentReadsAsEmpty() = assertEquals("", categoryNameFromArg(null))
}
