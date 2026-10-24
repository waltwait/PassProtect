package com.passprotect.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左滑動作鈕只在這一列真的被拉開時才畫：點一列長成詳情頁時，列本身被搬去共享元素的
 * overlay，原本的位置空出來——按鈕若一直畫在底下，轉場那一瞬間就會露出來。
 */
class SwipeActionsTest {

    @Test
    fun restingRowHidesActions() = assertFalse(swipeActionsShown(0f))

    @Test
    fun subPixelOffsetStillCountsAsResting() = assertFalse(swipeActionsShown(-0.4f))

    @Test
    fun draggedRowShowsActions() = assertTrue(swipeActionsShown(-1f))

    @Test
    fun fullyOpenRowShowsActions() = assertTrue(swipeActionsShown(-336f))
}
