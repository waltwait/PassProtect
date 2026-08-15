package com.hintvault.vault

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * history 與 KDBX 自訂字串欄位之間的轉換。
 *
 * KDBX 原生有 <History> 元素，但 KeePassJava2 沒有實作，且底層 Element 為
 * package-private 無法從外部操作。因此改用自訂欄位：
 *
 *     hist.1.at   = 2026-06-20T08:30:00Z
 *     hist.1.hint = 第一台電腦型號
 *
 * 索引 1 為最近的一筆舊提示，遞增代表越舊。KeePassXC 會把這些顯示為
 * entry 的「額外屬性」。
 */
object HistoryCodec {

    /** 超過此數量時捨棄最舊的。避免自訂欄位讓檔案無限成長。 */
    const val MAX_ENTRIES = 20

    private const val PREFIX = "hist."
    private val KEY_PATTERN = Regex("""^hist\.(\d+)\.(at|hint)$""")

    /**
     * 是否為本 codec 會處理的 history 欄位鍵值，例如 `hist.1.at`。
     *
     * [Vault.upsert] 清除舊 history 欄位時要用這個而非鬆散的字首比對——
     * 否則使用者手動加在 KeePassXC 上、湊巧以 "hist." 開頭但格式不同的
     * 屬性（例如 `hist.note`）會被本 app 每次編輯時當垃圾清掉，違反
     * 「本 app 不認識的內容原樣保留」這個核心設計承諾。
     */
    fun isHistoryKey(key: String): Boolean = KEY_PATTERN.matches(key)

    fun encode(history: List<HistoricalHint>): Map<String, String> =
        history.sortedByDescending { it.at }
            .take(MAX_ENTRIES)
            .withIndex()
            .flatMap { (i, h) ->
                listOf(
                    "$PREFIX${i + 1}.at" to h.at.toString(),
                    "$PREFIX${i + 1}.hint" to h.hint
                )
            }
            .toMap()

    fun decode(properties: Map<String, String>): List<HistoricalHint> {
        val byIndex = mutableMapOf<Int, MutableMap<String, String>>()
        for ((key, value) in properties) {
            val match = KEY_PATTERN.matchEntire(key) ?: continue
            val digits = match.groupValues[1]
            val index = digits.toIntOrNull() ?: continue
            if (digits != index.toString()) continue // 非正規索引（如 "01"）一律忽略，不得併入其他筆
            byIndex.getOrPut(index) { mutableMapOf() }[match.groupValues[2]] = value
        }
        return byIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (_, parts) ->
                val at = parts["at"] ?: return@mapNotNull null
                val hint = parts["hint"] ?: return@mapNotNull null
                val instant = try {
                    Instant.parse(at)
                } catch (_: DateTimeParseException) {
                    return@mapNotNull null
                }
                HistoricalHint(instant, hint)
            }
    }
}
