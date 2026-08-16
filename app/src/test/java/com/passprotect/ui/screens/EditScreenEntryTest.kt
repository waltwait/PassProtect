package com.passprotect.ui.screens

import com.passprotect.vault.Entry
import com.passprotect.vault.HistoricalHint
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EditScreen` 的存檔規則——[entryToSave] 這條「什麼時候該往 Entry.history 添一筆」
 * 的邏輯。畫面本身（Compose）只有真機 instrumented 測試碰得到，但這條規則是純函式，
 * 在這裡測得完整。
 */
class EditScreenEntryTest {

    private val now: Instant = Instant.parse("2026-08-16T12:00:00Z")

    private fun existing(hint: String, history: List<HistoricalHint> = emptyList()) = Entry(
        id = UUID.randomUUID(),
        title = "github.com",
        hint = hint,
        modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
        history = history
    )

    @Test
    fun changedHintPrependsThePreviousHintToHistory() {
        val old = existing("舊提示")

        val saved = entryToSave(old, title = "github.com", hint = "新提示", now = now)

        assertEquals(1, saved.history.size)
        assertEquals("舊提示", saved.history.single().hint)
        assertEquals(now, saved.history.single().at)
        assertEquals("新提示", saved.hint)
        assertEquals(old.id, saved.id)
    }

    @Test
    fun changedHintKeepsOlderHistoryEntriesAfterTheNewOne() {
        val older = HistoricalHint(at = Instant.parse("2025-01-01T00:00:00Z"), hint = "更早的提示")
        val old = existing("舊提示", history = listOf(older))

        val saved = entryToSave(old, title = "github.com", hint = "新提示", now = now)

        assertEquals(listOf("舊提示", "更早的提示"), saved.history.map { it.hint })
    }

    @Test
    fun titleOnlyChangeDoesNotTouchHistory() {
        val old = existing("同一個提示")

        val saved = entryToSave(old, title = "改過的標題", hint = "同一個提示", now = now)

        assertTrue(saved.history.isEmpty())
        assertEquals("改過的標題", saved.title)
    }

    @Test
    fun savingWithNothingChangedDoesNotTouchHistory() {
        val older = HistoricalHint(at = Instant.parse("2025-01-01T00:00:00Z"), hint = "更早的提示")
        val old = existing("同一個提示", history = listOf(older))

        val saved = entryToSave(old, title = old.title, hint = old.hint, now = now)

        assertEquals(listOf(older), saved.history)
    }

    @Test
    fun brandNewEntryStartsWithEmptyHistoryAndAFreshId() {
        val saved = entryToSave(existing = null, title = "新的", hint = "提示", now = now)

        assertTrue(saved.history.isEmpty())
        assertEquals("新的", saved.title)
        assertNotEquals(UUID(0L, 0L), saved.id)
    }
}
