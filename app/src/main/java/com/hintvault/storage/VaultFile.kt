package com.hintvault.storage

/**
 * 資料庫檔的位元組進出。
 *
 * 刻意不認識加密內容——這一層只管位元組與「這個檔有沒有被動過」。
 * 抽成介面是為了讓衝突邏輯能在 JVM 上以假實作窮舉測試，
 * 不必依賴 SAF 或實體裝置。
 */
interface VaultFile {
    val displayName: String
    fun read(): ByteArray
    fun write(bytes: ByteArray)
    fun lastModified(): Long

    /**
     * 在同一目錄寫出一個附帶後綴的檔案，回傳實際檔名。用於衝突副本。
     *
     * 實作**絕不可覆蓋既有檔案**——撞名時必須自行找一個未使用的新檔名再寫入，
     * 並回傳實際使用的那個名字（可能與 [suffix] 拼出的名字不同）。
     * `VaultRepository` 的整個「絕不覆蓋外部改動」保證構築在這個前提上。
     */
    fun writeSibling(suffix: String, bytes: ByteArray): String
}
