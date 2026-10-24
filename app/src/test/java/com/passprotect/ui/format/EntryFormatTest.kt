package com.passprotect.ui.format

import com.passprotect.storage.BackupEntry
import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** 清單副標、詳情、舊提示頁共用的日期與文案——規格 §3 `EntryRow`、§4.3、§4.5。 */
class EntryFormatTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private val now = Instant.parse("2026-10-23T04:00:00Z")

    private fun entry(category: String = "", accounts: Int = 1, modifiedAt: String = "2026-10-18T02:00:00Z") = Entry(
        id = UUID.randomUUID(),
        title = "網路銀行",
        category = category,
        accounts = List(accounts) { Account(id = UUID.randomUUID(), hint = "提示 $it") },
        modifiedAt = Instant.parse(modifiedAt)
    )

    @Test
    fun sameYearShowsMonthAndDay() =
        assertEquals("10 月 18 日修改", modifiedLabel(Instant.parse("2026-10-18T02:00:00Z"), now, zone))

    @Test
    fun earlierYearShowsYear() =
        assertEquals("2025 年 11 月 20 日修改", modifiedLabel(Instant.parse("2025-11-20T02:00:00Z"), now, zone))

    @Test
    fun dayIsTakenInLocalZone() =
        // UTC 10/17 20:00 在台北已經是 10/18 凌晨四點。
        assertEquals("10 月 18 日修改", modifiedLabel(Instant.parse("2026-10-17T20:00:00Z"), now, zone))

    @Test
    fun longDateHasYear() =
        assertEquals("2026 年 10 月 20 日", longDate(Instant.parse("2026-10-20T02:00:00Z"), zone))

    @Test
    fun browseSubtitleWithOneAccountIsJustTheDate() =
        assertEquals("10 月 18 日修改", entryRowSubtitle(entry(), EntryRowMode.Browse, now, zone))

    @Test
    fun browseSubtitleWithTwoAccountsPrefixesTheCount() =
        assertEquals(
            "2 組帳號・10 月 20 日修改",
            entryRowSubtitle(entry(accounts = 2, modifiedAt = "2026-10-20T02:00:00Z"), EntryRowMode.Browse, now, zone)
        )

    @Test
    fun searchSubtitleShowsCategory() =
        assertEquals("金融", entryRowSubtitle(entry(category = "金融"), EntryRowMode.Search, now, zone))

    @Test
    fun searchSubtitleWithoutCategoryFallsBackToDate() =
        assertEquals("10 月 18 日修改", entryRowSubtitle(entry(category = ""), EntryRowMode.Search, now, zone))

    @Test
    fun historyCountLabels() {
        assertEquals("沒有舊提示", historyCountLabel(0))
        assertEquals("2 筆舊提示", historyCountLabel(2))
    }

    @Test
    fun replacedLabelReadsAsSentence() =
        assertEquals("2026 年 8 月 3 日換掉", replacedLabel(Instant.parse("2026-08-03T02:00:00Z"), zone))

    // 設定頁「立即備份」右側、還原清單的日期（規格 2026-10-24-local-backup §2）。
    private val backupNow = Instant.parse("2026-10-24T06:00:00Z")

    @Test
    fun lastBackupTodayShowsTheTime() =
        assertEquals("今天 14:32", lastBackupLabel(Instant.parse("2026-10-24T06:32:00Z"), backupNow, zone))

    @Test
    fun lastBackupEarlierShowsTheDate() =
        assertEquals("10 月 22 日", lastBackupLabel(Instant.parse("2026-10-22T01:00:00Z"), backupNow, zone))

    @Test
    fun backupDateLabelIncludesTheWeekday() =
        assertEquals("10 月 22 日 週四", backupDateLabel(BackupEntry("x", LocalDate.of(2026, 10, 22), null, false, 0), today = LocalDate.of(2026, 10, 24)))
}
