package com.passprotect.ui.screens

import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** 主畫面資料夾格：哪些分類、各幾筆、什麼順序——規格 §4.1。 */
class ListScreenFoldersTest {

    private fun entry(category: String, title: String = "t") = Entry(
        id = UUID.randomUUID(),
        title = title,
        category = category,
        accounts = listOf(Account(id = UUID.randomUUID(), hint = "h")),
        modifiedAt = Instant.parse("2026-10-01T00:00:00Z")
    )

    @Test
    fun foldersAreCountedAndSortedAndBlankIsExcluded() {
        val entries = listOf(entry("金融"), entry("工作"), entry("金融"), entry(""))
        assertEquals(
            listOf(FolderSummary("工作", 1, listOf("t")), FolderSummary("金融", 2, listOf("t", "t"))),
            folderSummaries(entries)
        )
    }

    @Test
    fun previewIsTheFirstTwoTitlesInTheCategoryPageOrder() {
        val entries = listOf(entry("金融", "玉山"), entry("金融", "國泰"), entry("金融", "台新"), entry("工作", "Slack"))
        assertEquals(
            listOf(FolderSummary("工作", 1, listOf("Slack")), FolderSummary("金融", 3, listOf("台新", "國泰"))),
            folderSummaries(entries)
        )
    }

    @Test
    fun noCategoriesMeansNoFolders() =
        assertEquals(emptyList<FolderSummary>(), folderSummaries(listOf(entry(""))))
}
