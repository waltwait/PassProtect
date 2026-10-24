package com.passprotect.ui.screens

import com.passprotect.vault.Account
import com.passprotect.vault.Entry
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** 新增時打標題，找出標題相近、可能其實是同一筆的記錄。 */
class SimilarEntriesTest {

    private fun entry(title: String) = Entry(
        id = UUID.randomUUID(),
        title = title,
        accounts = listOf(Account(id = UUID.randomUUID(), hint = "h")),
        modifiedAt = Instant.parse("2026-10-01T00:00:00Z")
    )

    private fun titles(query: String, vararg existing: String) =
        similarEntries(query, existing.map(::entry)).map { it.title }

    @Test
    fun ignoresCaseSpacesAndDomainParts() =
        assertEquals(
            listOf("GitHub", "github.com", "GitHub 工作"),
            titles("git hub", "Google", "GitHub 工作", "github.com", "GitHub")
        )

    @Test
    fun urlLikeQueryFindsPlainTitle() =
        assertEquals(listOf("GitHub"), titles("https://www.github.com/login", "GitHub", "Google"))

    @Test
    fun stripsCountryDomainSuffix() =
        assertEquals(listOf("momo"), titles("momo.com.tw", "momo", "PChome"))

    @Test
    fun matchesChineseByContainment() =
        assertEquals(listOf("國泰世華"), titles("國泰", "國泰世華", "中國信託"))

    @Test
    fun existingTitleContainedInQueryAlsoCounts() =
        assertEquals(listOf("Steam"), titles("Steam 副帳", "Steam", "Epic"))

    @Test
    fun oneCharacterQueryFindsNothing() =
        assertEquals(emptyList<String>(), titles(" g ", "GitHub", "Google"))

    @Test
    fun oneCharacterExistingTitleIsNotSuggested() =
        assertEquals(emptyList<String>(), titles("xbox", "X"))

    @Test
    fun showsAtMostThree() =
        assertEquals(3, titles("mail", "Gmail", "Hotmail", "Mailchimp", "ProtonMail").size)
}
