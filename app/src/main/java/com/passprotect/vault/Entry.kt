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
 * 一筆記錄底下的一組帳號＋提示——同一個網站/標題常常不只一個帳號
 * （例如同一個 Gmail 網域下有私人跟工作兩組帳號），所以帳號跟提示是
 * [Entry] 底下可以有多筆的清單，不是記錄本身的欄位。
 *
 * [id] 是這組帳號自己的識別碼，用來在編輯畫面裡追蹤「這是哪一筆」（新增/
 * 刪除/重新排序時用），不是 KDBX 的欄位。
 * [username] 選填，對應到底層儲存時的使用者名稱欄位。
 * [hint] 對應到底層儲存時受保護的密碼欄位。
 */
data class Account(
    val id: UUID,
    val username: String = "",
    val hint: String,
    val history: List<HistoricalHint> = emptyList()
) {
    /** 同 [HistoricalHint]：不讓帳號/提示內容進入任何字串化路徑。 */
    override fun toString(): String =
        "Account(id=$id, username=<redacted>, hint=<redacted>, history=${history.size} 筆)"
}

/**
 * 一筆提示記錄。
 *
 * [url] 選填，對應 KDBX 標準的 URL 欄位。
 *
 * [category] 選填，對應 KDBX 原生的「群組」（Group）概念——一筆記錄只能屬於
 * 一個分類，空字串代表「未分類」（直接掛在根群組下）。分類本身沒有獨立的
 * 領域型別，因為它不需要除了名稱以外的任何屬性；`Vault` 內部會依名稱
 * 找到或建立對應的群組。KeePassXC 開這個檔案時看到的就是資料夾樹狀結構，
 * 不是本 app 自訂的欄位。
 *
 * [accounts] 一筆記錄底下的帳號清單，至少一筆——見 [Account] 的 KDoc。
 * 第一筆（`accounts[0]`）對應底層儲存的原生使用者名稱/密碼欄位，跟
 * KeePassXC 等桌面端的相容性完全不受影響；第二筆以後是本 app 自己加的
 * 延伸，桌面端看不到那些帳號，但看得到第一筆跟其他所有欄位。
 *
 * [modifiedAt] 建構時傳入的值在任何路徑都不被採用——不論是新建還是修改
 * 既有記錄，`Vault.upsert()` 寫入時，這個時間都是由底層函式庫在寫入當下
 * 自動蓋成「現在」，呼叫端傳的值一律被忽略。這是輸出欄位，不是輸入欄位。
 */
data class Entry(
    val id: UUID,
    val title: String,
    val url: String = "",
    val category: String = "",
    val accounts: List<Account>,
    val modifiedAt: Instant
) {
    override fun toString(): String =
        "Entry(id=$id, title=$title, url=$url, category=$category, accounts=${accounts.size} 筆, modifiedAt=$modifiedAt)"
}
