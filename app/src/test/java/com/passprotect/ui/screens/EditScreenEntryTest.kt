package com.passprotect.ui.screens

import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import com.passprotect.vault.HistoricalHint
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EditScreen` 的存檔規則——[entryToSave] 這條「什麼時候該往 Account.history 添一筆」
 * 的邏輯。畫面本身（Compose）只有真機 instrumented 測試碰得到，但這條規則是純函式，
 * 在這裡測得完整。
 */
class EditScreenEntryTest {

    private val now: Instant = Instant.parse("2026-08-16T12:00:00Z")

    private fun existing(hint: String, history: List<HistoricalHint> = emptyList(), accountId: UUID = UUID.randomUUID()) = Entry(
        id = UUID.randomUUID(),
        title = "github.com",
        accounts = listOf(Account(id = accountId, hint = hint, history = history)),
        modifiedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    @Test
    fun changedHintPrependsThePreviousHintToHistory() {
        val old = existing("舊提示")
        val accountId = old.accounts.single().id

        val saved = entryToSave(old, title = "github.com", accounts = listOf(AccountDraft(accountId, hint = "新提示")), now = now)

        val savedAccount = saved.accounts.single()
        assertEquals(1, savedAccount.history.size)
        assertEquals("舊提示", savedAccount.history.single().hint)
        assertEquals(now, savedAccount.history.single().at)
        assertEquals("新提示", savedAccount.hint)
        assertEquals(old.id, saved.id)
    }

    @Test
    fun changedHintKeepsOlderHistoryEntriesAfterTheNewOne() {
        val older = HistoricalHint(at = Instant.parse("2025-01-01T00:00:00Z"), hint = "更早的提示")
        val old = existing("舊提示", history = listOf(older))
        val accountId = old.accounts.single().id

        val saved = entryToSave(old, title = "github.com", accounts = listOf(AccountDraft(accountId, hint = "新提示")), now = now)

        assertEquals(listOf("舊提示", "更早的提示"), saved.accounts.single().history.map { it.hint })
    }

    @Test
    fun titleOnlyChangeDoesNotTouchHistory() {
        val old = existing("同一個提示")
        val accountId = old.accounts.single().id

        val saved = entryToSave(old, title = "改過的標題", accounts = listOf(AccountDraft(accountId, hint = "同一個提示")), now = now)

        assertTrue(saved.accounts.single().history.isEmpty())
        assertEquals("改過的標題", saved.title)
    }

    @Test
    fun savingWithNothingChangedDoesNotTouchHistory() {
        val older = HistoricalHint(at = Instant.parse("2025-01-01T00:00:00Z"), hint = "更早的提示")
        val old = existing("同一個提示", history = listOf(older))
        val accountId = old.accounts.single().id

        val saved = entryToSave(
            old, title = old.title,
            accounts = listOf(AccountDraft(accountId, hint = old.accounts.single().hint)),
            now = now
        )

        assertEquals(listOf(older), saved.accounts.single().history)
    }

    @Test
    fun brandNewEntryStartsWithEmptyHistoryAndAFreshId() {
        val saved = entryToSave(
            existing = null, title = "新的",
            accounts = listOf(AccountDraft(UUID.randomUUID(), hint = "提示")),
            now = now
        )

        assertTrue(saved.accounts.single().history.isEmpty())
        assertEquals("新的", saved.title)
        assertNotEquals(UUID(0L, 0L), saved.id)
    }

    @Test
    fun usernameIsCarriedThroughAndDoesNotAffectHistory() {
        val base = existing("同一個提示")
        val accountId = base.accounts.single().id
        val old = base.copy(accounts = listOf(base.accounts.single().copy(username = "old-user")))

        val saved = entryToSave(
            old, title = old.title,
            accounts = listOf(AccountDraft(accountId, username = "new-user", hint = "同一個提示")),
            now = now
        )

        assertEquals("new-user", saved.accounts.single().username)
        assertTrue("改帳號不算改提示，不該寫進 history", saved.accounts.single().history.isEmpty())
    }

    @Test
    fun addingASecondAccountKeepsTheFirstAccountsHistoryAndStartsTheNewOneEmpty() {
        val old = existing("第一組提示")
        val firstId = old.accounts.single().id
        val secondId = UUID.randomUUID()

        val saved = entryToSave(
            old, title = old.title,
            accounts = listOf(
                AccountDraft(firstId, hint = "第一組提示"),
                AccountDraft(secondId, hint = "第二組提示")
            ),
            now = now
        )

        assertEquals(2, saved.accounts.size)
        assertTrue(saved.accounts.first().history.isEmpty())
        assertTrue(saved.accounts.last().history.isEmpty())
    }
}
