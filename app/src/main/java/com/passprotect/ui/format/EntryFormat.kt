package com.passprotect.ui.format

import com.passprotect.storage.BackupEntry
import com.passprotect.vault.Entry
import java.time.Instant
import java.time.LocalDate
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

/** 設定頁「立即備份」右側：今天的寫「今天 14:32」，更早的寫「10 月 22 日」（不是今年就帶年份）。 */
fun lastBackupLabel(at: Instant, now: Instant, zone: ZoneId): String {
    val time = at.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    return if (time.toLocalDate() == today) {
        "今天 %02d:%02d".format(time.hour, time.minute)
    } else {
        monthDay(time.toLocalDate(), today)
    }
}

/** 還原清單的一列：「10 月 22 日 週四」（不是今年就帶年份）。 */
fun backupDateLabel(entry: BackupEntry, today: LocalDate): String =
    "${monthDay(entry.date, today)} ${Weekdays[entry.date.dayOfWeek.value - 1]}"

private val Weekdays = listOf("週一", "週二", "週三", "週四", "週五", "週六", "週日")

private fun monthDay(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) "${date.monthValue} 月 ${date.dayOfMonth} 日"
    else "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"

