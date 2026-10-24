package com.passprotect.vault

import com.passprotect.crypto.KdfParams
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 記錄多的時候解鎖不能變慢：之前每讀一個欄位都跑一次 XPath，成本跟整份資料庫一樣大，
 * 讀出全部記錄是平方成長（電腦上 100 筆 4 秒、300 筆 36 秒）。
 */
class VaultScaleTest {

    @Test
    fun readingManyEntriesStaysFast() {
        val password = "correct horse"
        val vault = Vault.create(KdfParams.FLOOR)
        repeat(200) { i ->
            vault.upsert(
                Entry(
                    id = UUID.randomUUID(),
                    title = "Entry $i",
                    url = "https://example$i.com",
                    category = "分類 ${i % 8}",
                    accounts = listOf(
                        Account(UUID.randomUUID(), "user$i@example.com", "提示 $i"),
                        Account(UUID.randomUUID(), "alt$i", "第二組 $i")
                    ),
                    modifiedAt = Instant.parse("2026-10-01T00:00:00Z")
                )
            )
        }
        val opened = Vault.open(vault.serialize(password.toCharArray()), password.toCharArray())

        val started = System.nanoTime()
        val entries = opened.entries()
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(200, entries.size)
        assertTrue("讀出 200 筆花了 $millis ms", millis < 2000)
    }
}
