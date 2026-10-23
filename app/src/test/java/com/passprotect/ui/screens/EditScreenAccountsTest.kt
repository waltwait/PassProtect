package com.passprotect.ui.screens

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 編輯畫面同時只展開一組帳號（規格 §4.4）。刪掉一組之後該展開哪一組——刪的不是
 * 展開中的那組就不動；刪的正是展開中的那組，就換它後面那組補上，它是最後一組
 * 才往前找。
 */
class EditScreenAccountsTest {

    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val ids = listOf(a, b, c)

    @Test
    fun removingAnotherAccountKeepsTheExpandedOne() =
        assertEquals(b, expandedAfterRemoval(ids, removed = a, expanded = b))

    @Test
    fun removingTheExpandedOneExpandsTheNextOne() =
        assertEquals(c, expandedAfterRemoval(ids, removed = b, expanded = b))

    @Test
    fun removingTheExpandedLastOneExpandsThePreviousOne() =
        assertEquals(b, expandedAfterRemoval(ids, removed = c, expanded = c))
}
