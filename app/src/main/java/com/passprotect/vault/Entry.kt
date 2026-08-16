package com.passprotect.vault

import java.time.Instant
import java.util.UUID

/** 某筆記錄先前使用過的提示。改了密碼但某裝置還是舊密碼時，靠這個救援。 */
data class HistoricalHint(val at: Instant, val hint: String) {
    /**
     * 必須覆寫。data class 生成的 toString() 會輸出所有欄位，而這個型別會傳遍
     * vault / storage / session / ui 每一層——任何一句 `Log.e(TAG, "$h")`
     * 或崩潰回報工具自動擷取物件狀態，提示內容就外洩了。
     */
    override fun toString(): String = "HistoricalHint(at=$at, hint=<redacted>)"
}

/**
 * 一筆提示記錄。
 *
 * 刻意沒有「帳號」欄位——需要的話寫在 [title] 裡。
 * [hint] 對應 KDBX 的 Password 欄位，因為那是 KDBX 預設受 inner stream 保護的欄位。
 *
 * [modifiedAt] 建構時傳入的值在任何路徑都不被採用——不論是新建還是修改
 * 既有記錄，`Vault.upsert()` 寫入時，這個時間都是由底層函式庫在寫入當下
 * 自動蓋成「現在」，呼叫端傳的值一律被忽略。這是輸出欄位，不是輸入欄位。
 */
data class Entry(
    val id: UUID,
    val title: String,
    val hint: String,
    val modifiedAt: Instant,
    val history: List<HistoricalHint> = emptyList()
) {
    /** 同 [HistoricalHint]：不讓提示內容進入任何字串化路徑。 */
    override fun toString(): String =
        "Entry(id=$id, title=$title, hint=<redacted>, modifiedAt=$modifiedAt, history=${history.size} 筆)"
}
