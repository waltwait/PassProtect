package com.passprotect.vault

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 第 2 筆以後的帳戶跟 KDBX 自訂欄位之間的轉換。第 1 筆帳戶沿用原生
 * UserName/Password 欄位＋既有的 `hist.<n>` 歷史紀錄（見 `Vault.toDomainEntry`），
 * 這裡只處理「額外」的帳戶：
 *
 *     acct.2.id        = <uuid>
 *     acct.2.user       = 另一個帳號
 *     acct.2.hint       = 這組帳號的提示
 *     acct.2.hist.1.at   = 2026-06-20T08:30:00Z
 *     acct.2.hist.1.hint = 這組帳號的舊提示
 *
 * 索引從 2 開始（1 對應原生欄位那組，不會出現在這裡），跟畫面上「第一組帳號
 * /第二組帳號…」的編號對得起來，不會有「陣列從 0 但索引從 1」那種錯位。
 */
object AccountCodec {

    /** 含第一筆（原生欄位）在內，一筆記錄最多允許的帳戶數。 */
    const val MAX_ACCOUNTS = 20

    private val FIELD_PATTERN = Regex("""^acct\.(\d+)\.(id|user|hint)$""")
    private val HIST_PATTERN = Regex("""^acct\.(\d+)\.hist\.(\d+)\.(at|hint)$""")

    /**
     * 是否為本 codec 會處理的欄位鍵值。跟 [HistoryCodec.isHistoryKey] 同樣的理由：
     * `Vault.upsert` 清除舊欄位時要用這個嚴格判斷，不能鬆散字首比對，否則使用者
     * 手動加在 KeePassXC 上、湊巧以 "acct." 開頭但格式不同的屬性會被誤刪。
     */
    fun isAccountKey(key: String): Boolean = FIELD_PATTERN.matches(key) || HIST_PATTERN.matches(key)

    /** [extraAccounts] 是 `entry.accounts.drop(1)`——不含第一筆。 */
    fun encode(extraAccounts: List<Account>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        extraAccounts.take(MAX_ACCOUNTS - 1).forEachIndexed { offset, account ->
            val i = offset + 2
            result["acct.$i.id"] = account.id.toString()
            result["acct.$i.user"] = account.username
            result["acct.$i.hint"] = account.hint
            account.history.sortedByDescending { it.at }
                .take(HistoryCodec.MAX_ENTRIES)
                .forEachIndexed { j, h ->
                    result["acct.$i.hist.${j + 1}.at"] = h.at.toString()
                    result["acct.$i.hist.${j + 1}.hint"] = h.hint
                }
        }
        return result
    }

    fun decode(properties: Map<String, String>): List<Account> {
        val fieldsByIndex = mutableMapOf<Int, MutableMap<String, String>>()
        val historyByIndex = mutableMapOf<Int, MutableMap<Int, MutableMap<String, String>>>()

        for ((key, value) in properties) {
            FIELD_PATTERN.matchEntire(key)?.let { m ->
                val digits = m.groupValues[1]
                val index = digits.toIntOrNull()
                if (index != null && digits == index.toString()) {
                    fieldsByIndex.getOrPut(index) { mutableMapOf() }[m.groupValues[2]] = value
                }
                return@let
            }
            HIST_PATTERN.matchEntire(key)?.let { m ->
                val accountDigits = m.groupValues[1]
                val accountIndex = accountDigits.toIntOrNull() ?: return@let
                if (accountDigits != accountIndex.toString()) return@let
                val histDigits = m.groupValues[2]
                val histIndex = histDigits.toIntOrNull() ?: return@let
                if (histDigits != histIndex.toString()) return@let
                historyByIndex.getOrPut(accountIndex) { mutableMapOf() }
                    .getOrPut(histIndex) { mutableMapOf() }[m.groupValues[3]] = value
            }
        }

        return fieldsByIndex.entries.sortedBy { it.key }.mapNotNull { (index, fields) ->
            val id = fields["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@mapNotNull null
            val hint = fields["hint"] ?: return@mapNotNull null
            val history = historyByIndex[index].orEmpty().entries
                .sortedBy { it.key }
                .mapNotNull { (_, parts) ->
                    val at = parts["at"] ?: return@mapNotNull null
                    val h = parts["hint"] ?: return@mapNotNull null
                    val instant = try {
                        Instant.parse(at)
                    } catch (_: DateTimeParseException) {
                        return@mapNotNull null
                    }
                    HistoricalHint(instant, h)
                }
            Account(id = id, username = fields["user"].orEmpty(), hint = hint, history = history)
        }
    }
}
