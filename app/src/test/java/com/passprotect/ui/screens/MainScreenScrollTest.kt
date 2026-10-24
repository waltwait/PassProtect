package com.passprotect.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主畫面：在頂端往下拉露出搜尋框、往上捲時大標題縮進頂列。 */
class MainScreenScrollTest {

    private val fullPx = 200f

    @Test
    fun pullingDownRevealsHalfTheDragDistance() =
        assertEquals(0.25f, pulledSearchReveal(0f, 100f, fullPx), 0.0001f)

    @Test
    fun revealStopsAtFullyShown() = assertEquals(1f, pulledSearchReveal(0.9f, 400f, fullPx), 0f)

    @Test
    fun pushingBackUpHidesItAgain() = assertEquals(0f, pulledSearchReveal(0.25f, -200f, fullPx), 0f)

    @Test
    fun unknownHeightRevealsNothing() = assertEquals(0f, pulledSearchReveal(0f, 100f, 0f), 0f)

    @Test
    fun releasingPastSixtyPercentOpensSearch() {
        assertTrue(pullOpensSearch(0.6f))
        assertFalse(pullOpensSearch(0.59f))
    }

    @Test
    fun titleAtTheTopIsNotCollapsed() = assertEquals(0f, titleCollapseFraction(0, 0, 100f), 0f)

    @Test
    fun titleCollapsesWithTheScrollOffset() = assertEquals(0.5f, titleCollapseFraction(0, 50, 100f), 0.0001f)

    @Test
    fun titleStaysCollapsedPastTheHeader() {
        assertEquals(1f, titleCollapseFraction(0, 300, 100f), 0f)
        assertEquals(1f, titleCollapseFraction(2, 0, 100f), 0f)
    }
}
