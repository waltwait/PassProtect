package com.hintvault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HistoryCodecTest {

    private val older = HistoricalHint(Instant.parse("2026-01-04T10:00:00Z"), "國中同學的綽號")
    private val newer = HistoricalHint(Instant.parse("2026-06-20T08:30:00Z"), "第一台電腦型號")

    @Test
    fun encodesNewestFirst() {
        val props = HistoryCodec.encode(listOf(older, newer))
        assertEquals("2026-06-20T08:30:00Z", props["hist.1.at"])
        assertEquals("第一台電腦型號", props["hist.1.hint"])
        assertEquals("2026-01-04T10:00:00Z", props["hist.2.at"])
        assertEquals("國中同學的綽號", props["hist.2.hint"])
    }

    @Test
    fun decodeReversesEncode() {
        val original = listOf(newer, older)
        assertEquals(original, HistoryCodec.decode(HistoryCodec.encode(original)))
    }

    @Test
    fun decodeIgnoresUnrelatedProperties() {
        val props = mapOf(
            "Title" to "github.com",
            "Password" to "目前的提示",
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.1.hint" to "第一台電腦型號"
        )
        assertEquals(listOf(newer), HistoryCodec.decode(props))
    }

    @Test
    fun decodeSkipsIncompletePairs() {
        // 只有 at 沒有 hint，或反之，都不成一筆
        val props = mapOf(
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.2.hint" to "沒有時間戳"
        )
        assertTrue(HistoryCodec.decode(props).isEmpty())
    }

    @Test
    fun decodeSkipsUnparseableTimestamp() {
        val props = mapOf("hist.1.at" to "not-a-timestamp", "hist.1.hint" to "x")
        assertTrue(HistoryCodec.decode(props).isEmpty())
    }

    @Test
    fun encodeTruncatesToMaxEntriesKeepingNewest() {
        val many = (1..30).map {
            HistoricalHint(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it.toLong()), "hint$it")
        }
        val props = HistoryCodec.encode(many)
        assertEquals(HistoryCodec.MAX_ENTRIES * 2, props.size)
        assertEquals("hint30", props["hist.1.hint"])          // 最新的留下
        assertEquals(null, props["hist.${HistoryCodec.MAX_ENTRIES + 1}.hint"])
    }

    @Test
    fun encodeEmptyHistoryProducesNoProperties() {
        assertTrue(HistoryCodec.encode(emptyList()).isEmpty())
    }

    @Test
    fun decodeIgnoresNonCanonicalIndicesInsteadOfMergingThem() {
        // KeePassXC 讓使用者手動編輯這些欄位，hist.01.* 是可能出現的。
        // 它絕不能跟 hist.1.* 併成同一筆——那會讓時間戳與提示文字錯配。
        val props = mapOf(
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.1.hint" to "正確的提示",
            "hist.01.at" to "2020-01-01T00:00:00Z",
            "hist.01.hint" to "不該出現的提示"
        )

        val decoded = HistoryCodec.decode(props)

        assertEquals(1, decoded.size)
        assertEquals("正確的提示", decoded.single().hint)
        assertEquals(Instant.parse("2026-06-20T08:30:00Z"), decoded.single().at)
    }

    @Test
    fun decodeHandlesSparseIndices() {
        val props = mapOf(
            "hist.1.at" to "2026-06-20T08:30:00Z",
            "hist.1.hint" to "較新",
            "hist.7.at" to "2026-01-04T10:00:00Z",
            "hist.7.hint" to "較舊"
        )

        val decoded = HistoryCodec.decode(props)

        assertEquals(listOf("較新", "較舊"), decoded.map { it.hint })
    }
}
