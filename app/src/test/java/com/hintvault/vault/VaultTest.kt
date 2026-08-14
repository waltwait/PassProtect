package com.hintvault.vault

import com.hintvault.crypto.KdfParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VaultTest {

    // 單元測試用最小成本，只求功能正確；正式預設值由 KdfParams.DEFAULT 決定
    private val fastKdf = KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)

    private fun pw() = "correct horse".toCharArray()

    private fun sampleEntry(title: String = "github.com") = Entry(
        id = UUID.randomUUID(),
        title = title,
        hint = "提示：貓的名字 + 生日",
        modifiedAt = Instant.parse("2026-08-13T00:00:00Z"),
        history = listOf(
            HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "舊提示：國中同學的綽號")
        )
    )

    @Test
    fun createProducesEmptyVault() {
        assertTrue(Vault.create(fastKdf).entries().isEmpty())
    }

    @Test
    fun upsertThenSerializeThenOpenRoundTrips() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)

        val bytes = vault.serialize(pw())
        val reopened = Vault.open(bytes, pw())
        val got = reopened.entries().single()

        assertEquals(entry.id, got.id)
        assertEquals(entry.title, got.title)
        assertEquals(entry.hint, got.hint)
        assertEquals(entry.history, got.history)
    }

    @Test
    fun serializedFileIsKdbx4() {
        val bytes = Vault.create(fastKdf).serialize(pw())
        // KDBX 簽章 9AA2D903 B54BFB67（little-endian 寫入）
        assertEquals(0x03.toByte(), bytes[0])
        assertEquals(0xD9.toByte(), bytes[1])
        val major = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
        assertEquals(4, major)
    }

    @Test
    fun wrongPasswordThrowsWrongPasswordException() {
        val bytes = Vault.create(fastKdf).serialize(pw())
        try {
            Vault.open(bytes, "wrong".toCharArray())
            throw AssertionError("應該擲出 WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun upsertWithExistingIdReplacesRatherThanDuplicates() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)
        vault.upsert(entry.copy(hint = "改過的提示"))

        assertEquals(1, vault.entries().size)
        assertEquals("改過的提示", vault.entries().single().hint)
    }

    @Test
    fun deleteRemovesEntry() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)

        assertTrue(vault.delete(entry.id))
        assertTrue(vault.entries().isEmpty())
    }

    @Test
    fun deleteReturnsFalseForUnknownId() {
        assertTrue(!Vault.create(fastKdf).delete(UUID.randomUUID()))
    }

    @Test
    fun hintIsStoredInPasswordFieldNotNotes() {
        // 規格 §5.1：提示要放進受 inner stream 保護的 Password 欄位
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())
        val reopened = Vault.open(vault.serialize(pw()), pw())
        val raw = reopened.rawEntryProperties(reopened.entries().single().id)

        assertEquals("提示：貓的名字 + 生日", raw["Password"])
        assertTrue(raw["Notes"].isNullOrEmpty())
    }

    @Test
    fun historyIsStoredAsCustomProperties() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())
        val reopened = Vault.open(vault.serialize(pw()), pw())
        val raw = reopened.rawEntryProperties(reopened.entries().single().id)

        assertEquals("2026-01-04T10:00:00Z", raw["hist.1.at"])
        assertEquals("舊提示：國中同學的綽號", raw["hist.1.hint"])
    }

    @Test
    fun multipleEntriesAreAllPreserved() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry("github.com"))
        vault.upsert(sampleEntry("gmail.com"))

        val titles = Vault.open(vault.serialize(pw()), pw()).entries().map { it.title }
        assertEquals(setOf("github.com", "gmail.com"), titles.toSet())
    }

    @Test
    fun reducingHistoryRemovesStaleCustomProperties() {
        // 從 2 筆 history 減成 1 筆時，hist.2.* 必須消失，
        // 否則舊欄位會殘留成幽靈資料
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(
            history = listOf(
                HistoricalHint(Instant.parse("2026-06-20T08:30:00Z"), "較新"),
                HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "較舊")
            )
        )
        vault.upsert(entry)
        vault.upsert(entry.copy(history = listOf(entry.history.first())))

        val raw = vault.rawEntryProperties(entry.id)
        assertEquals("較新", raw["hist.1.hint"])
        assertNull(raw["hist.2.hint"])
    }

    @Test
    fun preservesContentThisAppDoesNotModelAcrossLoadModifySave() {
        // 這是整個設計最重要的性質：DOM 實作保留整份 XML，所以 KeePassXC 寫入
        // 而本 app 不認識的內容（群組結構、圖示、其他欄位）在「載入→修改→存檔」
        // 之後仍然存在。若有人把 Vault 改成「解析成領域物件再重建文件」，
        // 上面 11 個測試全都還會過，只有這個會抓到。
        val original = javaClass.getResourceAsStream("/xc.kdbx")!!.readBytes()
        val pw = "xcpass".toCharArray()

        val vault = Vault.open(original, pw)
        val existing = vault.entries().single()
        vault.upsert(sampleEntry("added-by-app"))

        val reopened = Vault.open(vault.serialize(pw), pw)

        // KeePassXC 建立的那筆必須原封不動，且不可被複製成兩份
        val survivors = reopened.entries().filter { it.title == "gmail.com" }
        assertEquals("KeePassXC 的 entry 不可消失也不可重複", 1, survivors.size)
        assertEquals(existing.id, survivors.single().id)

        // 本 app 不建模的欄位也必須存活
        val raw = reopened.rawEntryProperties(survivors.single().id)
        assertEquals("milktea", raw["UserName"])

        assertEquals(2, reopened.entries().size)
    }

    @Test
    fun malformedIdMarkerFallsBackInsteadOfBreakingTheWholeList() {
        val vault = Vault.create(fastKdf)
        val good = sampleEntry("github.com")
        vault.upsert(good)
        vault.upsert(sampleEntry("gmail.com"))

        // 模擬使用者在 KeePassXC 裡把 hv.id 清掉或改壞
        vault.corruptIdMarkerForTest(good.id, "not-a-uuid")

        // 兩筆都還要列得出來，不能整個清單掛掉
        assertEquals(2, vault.entries().size)
    }
}
