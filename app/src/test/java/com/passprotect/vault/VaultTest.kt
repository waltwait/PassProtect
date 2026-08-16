package com.passprotect.vault

import com.passprotect.crypto.KdfParams
import com.passprotect.crypto.toUtf8Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.security.Argon2
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

class VaultTest {

    // 單元測試希望用最小成本，只求功能正確；但 Vault.create() 現在會把低於
    // KdfParams.FLOOR 的參數夾到 16 MiB（見 clampedToFloor()），所以這裡
    // 實際套用的是 16 MiB，不是字面上寫的 8——仍比 DEFAULT 的 32 MiB 快，
    // 只是不像數字看起來那麼小。正式預設值由 KdfParams.DEFAULT 決定。
    private val fastKdf = KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)

    private fun pw() = "correct horse".toCharArray()

    private fun sampleEntry(title: String = "github.com") = Entry(
        id = UUID.randomUUID(),
        title = title,
        account = "user@example.com",
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
        assertEquals(entry.account, got.account)
        assertEquals(entry.hint, got.hint)
        assertEquals(entry.history, got.history)
    }

    @Test
    fun upsertWithBlankAccountRoundTripsAsBlank() {
        // account 選填——留白是正常路徑，不該在 KDBX 裡憑空長出 null 或例外。
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(account = "")
        vault.upsert(entry)

        val reopened = Vault.open(vault.serialize(pw()), pw())
        assertEquals("", reopened.entries().single().account)
    }

    @Test
    fun upsertWithBlankCategoryStaysUncategorized() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())

        assertEquals("", vault.entries().single().category)
        assertTrue("未分類不該憑空產生一個群組", vault.categories().isEmpty())
    }

    @Test
    fun upsertWithCategoryRoundTripsAndListsInCategories() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(category = "工作")
        vault.upsert(entry)

        val reopened = Vault.open(vault.serialize(pw()), pw())
        assertEquals("工作", reopened.entries().single().category)
        assertEquals(listOf("工作"), reopened.categories())
    }

    @Test
    fun twoEntriesWithSameCategoryShareOneGroup() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry("github.com").copy(category = "工作"))
        vault.upsert(sampleEntry("gitlab.com").copy(category = "工作"))

        // 同一個分類只該有一個群組，不是每筆記錄各建一個同名群組
        assertEquals(listOf("工作"), vault.categories())
        assertEquals(2, vault.entries().count { it.category == "工作" })
    }

    @Test
    fun changingCategoryMovesEntryToNewGroupAndCreatesItIfMissing() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(category = "工作")
        vault.upsert(entry)

        vault.upsert(entry.copy(category = "遊戲"))

        val got = vault.entries().single()
        assertEquals("遊戲", got.category)
        assertTrue("舊分類群組沒有記錄了，不該再出現在建議清單", "工作" !in vault.categories())
        assertEquals(listOf("遊戲"), vault.categories())
    }

    @Test
    fun changingCategoryBackToUncategorizedMovesEntryToRootGroup() {
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry().copy(category = "工作")
        vault.upsert(entry)

        vault.upsert(entry.copy(category = ""))

        assertEquals("", vault.entries().single().category)
    }

    @Test
    fun hintSnippetsRoundTripAndDoNotAppearAsEntries() {
        val vault = Vault.create(fastKdf)
        vault.upsert(sampleEntry())
        vault.setHintSnippets(listOf("生日 + 寵物名", "國中同學綽號"))

        val reopened = Vault.open(vault.serialize(pw()), pw())
        assertEquals(listOf("生日 + 寵物名", "國中同學綽號"), reopened.hintSnippets())
        // 常用提示詞借用一筆隱藏 entry 存放，entries() 必須把它濾掉，
        // 否則會在清單畫面憑空多出一筆使用者沒新增過的記錄。
        assertEquals(1, reopened.entries().size)
        assertEquals("github.com", reopened.entries().single().title)
    }

    @Test
    fun settingHintSnippetsTwiceReplacesRatherThanAppends() {
        val vault = Vault.create(fastKdf)
        vault.setHintSnippets(listOf("第一版"))
        vault.setHintSnippets(listOf("第二版", "第三版"))

        assertEquals(listOf("第二版", "第三版"), vault.hintSnippets())
    }

    @Test
    fun emptyVaultHasNoHintSnippets() {
        assertTrue(Vault.create(fastKdf).hintSnippets().isEmpty())
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

    @Test
    fun carriesTheFilesOwnKdfCostForwardOnResave() {
        // 校準結果必須跨 session 存活，否則慢機器每次重開都被寫回 DEFAULT
        val calibrated = KdfParams(memoryMib = 16, iterations = 3, parallelism = 2)
        val bytes = Vault.create(calibrated).serialize(pw())

        val resaved = Vault.open(bytes, pw()).serialize(pw())
        val params = Vault.open(resaved, pw()).kdfParamsForTest()

        assertEquals(16L, params.memoryMib)
        assertEquals(3L, params.iterations)
    }

    @Test
    fun neverCarriesForwardAKdfWeakerThanTheFloor() {
        // 8 MiB 的檔（外部工具或手動編輯產生）不得讓本 app 寫出低於下限的 KDF。
        //
        // 繞過 Vault.create()：它現在會把過弱的 kdf 夾到 FLOOR 才建檔（見
        // Vault.create 的修正），所以要測「open() 讀到已經很弱的檔案時是否
        // 還會夾」，必須直接用底層函式庫組一個真正低於下限的檔案，不能經過
        // Vault.create()。
        val header = KdbxHeader(4).apply {
            keyDerivationFunction = Argon2.getInstance()
            kdfParameters.putULong("M", 8L * 1024 * 1024)
            kdfParameters.putULong("I", 1)
            kdfParameters.putUInt("P", 1)
        }
        val out = ByteArrayOutputStream()
        DomDatabaseWrapper().save(KdbxStreamFormat(header), KdbxCreds(pw().toUtf8Bytes()), out)

        val params = Vault.open(out.toByteArray(), pw()).kdfParamsForTest()

        assertEquals(KdfParams.FLOOR.memoryMib, params.memoryMib)
        assertTrue(params.iterations >= KdfParams.FLOOR.iterations)
        assertTrue(params.parallelism >= KdfParams.FLOOR.parallelism)
    }

    @Test
    fun createNeverWritesBelowTheSecurityFloor() {
        // Vault.create() 現在也要夾——不能只信任呼叫端傳的參數
        val weak = KdfParams(memoryMib = 8, iterations = 1, parallelism = 1)
        val vault = Vault.create(weak)

        assertEquals(KdfParams.FLOOR.memoryMib, vault.kdfParamsForTest().memoryMib)
        assertTrue(vault.kdfParamsForTest().iterations >= KdfParams.FLOOR.iterations)
        assertTrue(vault.kdfParamsForTest().parallelism >= KdfParams.FLOOR.parallelism)
    }

    @Test
    fun aFileWithAbsurdlySmallMemoryStillSaves() {
        // M < 1 MiB 會整數截斷成 0，BouncyCastle 會擲例外讓資料庫永久存不了。
        //
        // 偏離審查給的原始寫法之處：原本的 `KdfParams(memoryMib = 0, ...)` 這個
        // 區域變數從未被用到——一開始寫這個測試時，Vault.create()/serialize()
        // 對「自己」的 kdf 還沒有夾制（那時夾制只發生在 open() 讀別人檔案時），
        // 所以 memoryMib=0 一旦真的拿去 Vault.create(absurd).serialize(pw())
        // 會在「建立當下」就讓 BouncyCastle 擲例外，根本到不了「開啟已存在的
        // 檔案」這個要測的路徑。（最終全分支審查之後，Vault.create() 現在也會
        // 夾到 FLOOR 了，但這個測試繞過 Vault 直接組檔案，不受影響，本測試
        // 寫法維持原樣。）因此改為繞過 Vault，直接用底層函式庫組一個
        // M = 512 KiB（位元組數，換算 MiB 時整數除法會截斷成 0）的檔案，
        // 這樣才是真正在跑 kdfParamsOrDefault() 的夾制邏輯，而不是跑一個
        // 永遠不會執行的分支。
        val header = KdbxHeader(4).apply {
            keyDerivationFunction = Argon2.getInstance()
            kdfParameters.putULong("M", 512L * 1024) // 512 KiB：換成 MiB 會整數截斷成 0
            kdfParameters.putULong("I", 2)
            kdfParameters.putUInt("P", 2)
        }
        val out = ByteArrayOutputStream()
        DomDatabaseWrapper().save(KdbxStreamFormat(header), KdbxCreds(pw().toUtf8Bytes()), out)

        val vault = Vault.open(out.toByteArray(), pw())

        // 直接驗證夾制邏輯：不論檔案宣稱什麼，存檔都不能炸
        assertNotNull(vault.serialize(pw()))
        assertTrue(vault.kdfParamsForTest().memoryMib >= KdfParams.FLOOR.memoryMib)
    }

    @Test
    fun nonCanonicalIdMarkerStillUpdatesInPlaceInsteadOfDuplicating() {
        // 大寫 UUID 是完全正常的寫法。字串比對會認不得，於是又變成「找不到→新建」。
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry("github.com")
        vault.upsert(entry)
        vault.corruptIdMarkerForTest(entry.id, entry.id.toString().uppercase())

        val listed = vault.entries().single()
        vault.upsert(listed.copy(hint = "改過的提示"))

        assertEquals(1, vault.entries().size)
        assertEquals("改過的提示", vault.entries().single().hint)
        assertTrue(vault.delete(listed.id))
        assertTrue(vault.entries().isEmpty())
    }

    @Test
    fun upsertPreservesForeignAttributesThatMerelyStartWithHistPrefix() {
        // 使用者可能在 KeePassXC 手動加一個湊巧以 "hist." 開頭、但格式不是
        // hist.<n>.(at|hint) 的屬性。本 app 編輯這筆記錄時不該把它當成自己的
        // history 欄位清掉——那是外來內容，「本 app 不認識的內容原樣保留」
        // 是核心設計承諾。
        val vault = Vault.create(fastKdf)
        val entry = sampleEntry()
        vault.upsert(entry)
        vault.setRawPropertyForTest(entry.id, "hist.note", "不是本 app 寫的")

        vault.upsert(entry.copy(hint = "改過的提示"))

        val raw = vault.rawEntryProperties(entry.id)
        assertEquals("不是本 app 寫的", raw["hist.note"])
    }

    @Test
    fun upsertOnKeePassXcAuthoredEntryUpdatesInPlaceAndDeletes() {
        // C1 的直接迴歸測試：桌面端建立的 entry 沒有 hv.id，寫路徑必須靠 KDBX UUID
        // 找到它。找不到就會「新建一筆」，把使用者的 entry 複製成兩份，
        // 而原本那份帶著舊提示、永遠刪不掉。
        val original = javaClass.getResourceAsStream("/xc.kdbx")!!.readBytes()
        val pw = "xcpass".toCharArray()

        val vault = Vault.open(original, pw)
        val listed = vault.entries().single()
        vault.upsert(listed.copy(hint = "手機上改的提示"))

        val reopened = Vault.open(vault.serialize(pw), pw)
        assertEquals("不可複製成兩份", 1, reopened.entries().size)
        assertEquals("手機上改的提示", reopened.entries().single().hint)

        val again = Vault.open(reopened.serialize(pw), pw)
        assertTrue(again.delete(again.entries().single().id))
        assertTrue(again.entries().isEmpty())
    }
}
