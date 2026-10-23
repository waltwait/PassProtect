package com.passprotect.ui.format

import com.passprotect.vault.Entry
import java.time.Instant
import java.time.ZoneId

/**
 * 清單列副標該講什麼：瀏覽（主畫面「未分類」、分類頁）時使用者已經知道分類，
 * 副標講帳號數與修改日期；搜尋結果跨所有分類，副標改講這筆在哪個分類。
 */
enum class EntryRowMode { Browse, Search }

/** 「10 月 18 日修改」；不是今年就帶年份，免得去年的修改看起來像這個月才改。 */
fun modifiedLabel(at: Instant, now: Instant, zone: ZoneId): String {
    val date = at.atZone(zone).toLocalDate()
    val sameYear = date.year == now.atZone(zone).year
    return if (sameYear) "${date.monthValue} 月 ${date.dayOfMonth} 日修改" else "${longDate(at, zone)}修改"
}

/** 「2026 年 10 月 20 日」——詳情頁的最後修改、舊提示頁都用這個完整寫法。 */
fun longDate(at: Instant, zone: ZoneId): String {
    val date = at.atZone(zone).toLocalDate()
    return "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}

fun entryRowSubtitle(entry: Entry, mode: EntryRowMode, now: Instant, zone: ZoneId): String {
    val modified = modifiedLabel(entry.modifiedAt, now, zone)
    return when (mode) {
        EntryRowMode.Browse ->
            if (entry.accounts.size > 1) "${entry.accounts.size} 組帳號・$modified" else modified
        EntryRowMode.Search -> entry.category.ifBlank { modified }
    }
}

fun historyCountLabel(count: Int): String = if (count == 0) "沒有舊提示" else "$count 筆舊提示"

/** `HistoricalHint.at` 是那個提示被換掉的時間（見 `entryToSave`），所以說「換掉」不是「建立」。 */
fun replacedLabel(at: Instant, zone: ZoneId): String = "${longDate(at, zone)}換掉"
