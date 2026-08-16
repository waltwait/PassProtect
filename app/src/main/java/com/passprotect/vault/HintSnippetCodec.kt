package com.passprotect.vault

/**
 * 常用提示詞清單與 KDBX 自訂字串欄位之間的轉換。同 [HistoryCodec] 的手法：
 *
 *     hv.snippet.0 = 生日 + 寵物名
 *     hv.snippet.1 = 國中同學綽號
 *
 * 索引只代表清單順序，不代表新舊。
 */
object HintSnippetCodec {

    /** 超過此數量時捨棄清單末端的。避免自訂欄位讓檔案無限成長。 */
    const val MAX_ENTRIES = 50

    private const val PREFIX = "hv.snippet."
    private val KEY_PATTERN = Regex("""^hv\.snippet\.(\d+)$""")

    /** 同 [HistoryCodec.isHistoryKey]：[Vault.setHintSnippets] 清舊欄位要用這個，理由相同。 */
    fun isSnippetKey(key: String): Boolean = KEY_PATTERN.matches(key)

    fun encode(snippets: List<String>): Map<String, String> =
        snippets.take(MAX_ENTRIES)
            .withIndex()
            .associate { (i, s) -> "$PREFIX$i" to s }

    fun decode(properties: Map<String, String>): List<String> =
        properties.entries
            .mapNotNull { (key, value) ->
                val match = KEY_PATTERN.matchEntire(key) ?: return@mapNotNull null
                val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                index to value
            }
            .sortedBy { it.first }
            .map { it.second }
}
