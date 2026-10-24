package com.passprotect.ui.screens

import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import java.time.Instant
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

    // 從新增頁的「已經有類似的」換到既有那筆時：新增頁上已經打的帳號帶過去，接在既有
    // 帳號後面；一組都沒打就帶一組空的，換過去才有一組可以填。

    @Test
    fun carriesOnlyTypedAccounts() {
        val typed = AccountDraft(a, "me@example.com", "")
        val blank = AccountDraft(b, " ", "")
        assertEquals(listOf(typed), accountsToCarry(listOf(blank, typed)))
    }

    @Test
    fun carriesOneBlankAccountWhenNothingWasTyped() {
        val carried = accountsToCarry(listOf(AccountDraft(a, "", "")))
        assertEquals(1, carried.size)
        assertEquals("" to "", carried[0].username to carried[0].hint)
    }

    @Test
    fun carriedAccountsGoAfterTheExistingOnes() {
        val existing = Entry(
            id = UUID.randomUUID(),
            title = "GitHub",
            accounts = listOf(Account(id = a, username = "old", hint = "h")),
            modifiedAt = Instant.parse("2026-10-01T00:00:00Z")
        )
        val added = AccountDraft(b, "new", "h2")
        assertEquals(
            listOf(AccountDraft(a, "old", "h"), added),
            initialAccountDrafts(existing, listOf(added))
        )
    }

    @Test
    fun newEntryStartsWithOneBlankAccount() {
        val drafts = initialAccountDrafts(existing = null, added = emptyList())
        assertEquals(1, drafts.size)
        assertEquals("" to "", drafts[0].username to drafts[0].hint)
    }
}
