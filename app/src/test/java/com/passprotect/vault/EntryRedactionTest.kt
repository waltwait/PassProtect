package com.passprotect.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class EntryRedactionTest {

    private val secret = "貓的名字加生日"

    @Test
    fun entryToStringDoesNotContainHint() {
        val entry = Entry(
            id = UUID.randomUUID(),
            title = "github.com",
            accounts = listOf(Account(id = UUID.randomUUID(), hint = secret)),
            modifiedAt = Instant.parse("2026-08-13T00:00:00Z")
        )

        val rendered = entry.toString()

        assertFalse("toString 不可包含提示內容", rendered.contains(secret))
        assertTrue("標題仍應可見，方便除錯", rendered.contains("github.com"))
    }

    @Test
    fun accountToStringDoesNotContainHintOrUsername() {
        val username = "user@example.com"
        val account = Account(id = UUID.randomUUID(), username = username, hint = secret)

        val rendered = account.toString()

        assertFalse("toString 不可包含提示內容", rendered.contains(secret))
        assertFalse("toString 不可包含帳號內容", rendered.contains(username))
    }

    @Test
    fun historicalHintToStringDoesNotContainHint() {
        val h = HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), secret)

        val rendered = h.toString()

        assertFalse("toString 不可包含提示內容", rendered.contains(secret))
        assertTrue("時間戳仍應可見", rendered.contains("2026-01-04"))
    }

    @Test
    fun stringInterpolationOfEntryListAlsoRedacts() {
        // 集合的 toString 會呼叫元素的 toString，這是最容易不小心外洩的路徑
        val entry = Entry(
            id = UUID.randomUUID(),
            title = "gmail.com",
            accounts = listOf(
                Account(
                    id = UUID.randomUUID(),
                    hint = secret,
                    history = listOf(HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), secret))
                )
            ),
            modifiedAt = Instant.parse("2026-08-13T00:00:00Z")
        )

        assertFalse(listOf(entry).toString().contains(secret))
    }

    @Test
    fun equalsStillComparesAccounts() {
        // 只覆寫 toString，不可動到 data class 生成的 equals
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-08-13T00:00:00Z")
        val accountId = UUID.randomUUID()
        val a = Entry(id = id, title = "t", accounts = listOf(Account(id = accountId, hint = "hint-a")), modifiedAt = at)
        val b = Entry(id = id, title = "t", accounts = listOf(Account(id = accountId, hint = "hint-b")), modifiedAt = at)

        assertFalse(a == b)
        assertTrue(a == a.copy())
    }
}
