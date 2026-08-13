package com.hintvault.vault

import java.time.Instant
import java.util.UUID

/** 某筆記錄先前使用過的提示。改了密碼但某裝置還是舊密碼時，靠這個救援。 */
data class HistoricalHint(val at: Instant, val hint: String)

/**
 * 一筆提示記錄。
 *
 * 刻意沒有「帳號」欄位——需要的話寫在 [title] 裡。
 * [hint] 對應 KDBX 的 Password 欄位，因為那是 KDBX 預設受 inner stream 保護的欄位。
 */
data class Entry(
    val id: UUID,
    val title: String,
    val hint: String,
    val modifiedAt: Instant,
    val history: List<HistoricalHint> = emptyList()
)
