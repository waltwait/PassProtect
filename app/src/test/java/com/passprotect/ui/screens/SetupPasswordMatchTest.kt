package com.passprotect.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 建立資料庫時「兩次輸入一致／不一致」的即時提示（規格 §4.7）。確認欄還沒打字
 * 時什麼都不顯示——一進畫面就跳出「不一致」只會讓人以為自己做錯了什麼。
 */
class SetupPasswordMatchTest {

    @Test
    fun emptyConfirmShowsNothing() = assertEquals(PasswordMatch.Empty, passwordMatch("abc", ""))

    @Test
    fun bothEmptyShowsNothing() = assertEquals(PasswordMatch.Empty, passwordMatch("", ""))

    @Test
    fun equalIsMatch() = assertEquals(PasswordMatch.Match, passwordMatch("abc", "abc"))

    @Test
    fun differentIsMismatch() = assertEquals(PasswordMatch.Mismatch, passwordMatch("abc", "abd"))
}
