package com.passprotect.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 左滑動作鈕只在這一列真的被拉開時才畫，收著的時候不會從列底下透出來。 */
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
