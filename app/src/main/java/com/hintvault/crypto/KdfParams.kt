package com.hintvault.crypto

/**
 * Argon2 的成本參數。
 *
 * 注意這裡是 Argon2**d** 而非 Argon2id——KeePassJava2 的 Argon2 實作寫死
 * Argon2Parameters.ARGON2_d，且註冊於 Argon2d 的 UUID。
 *
 * 實測（Galaxy S25 / Android 16）：32 MiB / I=2 端到端約 2.2 秒。
 * 端到端之所以是原始 KDF 時間的兩倍，是因為 KdbxHeader.getTransformedKeyDigest()
 * 沒有快取，存取路徑各會呼叫它 2-3 次。
 */
data class KdfParams(
    val memoryMib: Long,
    val iterations: Long,
    val parallelism: Int
) {
    val memoryBytes: Long get() = memoryMib * 1024 * 1024

    /**
     * 逐欄位夾到不低於 [FLOOR]。用於任何「即將真的拿去建立資料庫」的參數，
     * 不論來源是使用者輸入、外部檔案沿用（見 Vault.kdfParamsOrDefault），
     * 還是呼叫端的硬編碼——這裡是最後一道防線。
     */
    fun clampedToFloor(): KdfParams = KdfParams(
        memoryMib = maxOf(memoryMib, FLOOR.memoryMib),
        iterations = maxOf(iterations, FLOOR.iterations),
        parallelism = maxOf(parallelism, FLOOR.parallelism)
    )

    companion object {
        val DEFAULT = KdfParams(memoryMib = 32, iterations = 2, parallelism = 2)

        /** 建立資料庫時允許的最長耗時。超過就往下一級退——但不會退破 [FLOOR]。 */
        const val MAX_ACCEPTABLE_MILLIS = 3000L

        /**
         * 階梯的最弱一級，也是安全下限。
         *
         * 不再往下退。Argon2 會把記憶體分給各 lane，16 MiB / p=2 等於每 lane 8 MiB；
         * 再低就明顯低於 OWASP 對 Argon2 的最低建議，而 KDF 正是離線暴力破解的
         * 唯一屏障。本 app 存的是提示，提示外洩會大幅縮小密碼搜尋空間，
         * 因此這裡選擇「慢一點」而非「弱一點」。
         */
        val FLOOR = KdfParams(memoryMib = 16, iterations = 2, parallelism = 2)

        /** 由強到弱。第一個在預算內的即為所選。 */
        val CALIBRATION_LADDER = listOf(
            DEFAULT,
            KdfParams(memoryMib = 24, iterations = 2, parallelism = 2),
            FLOOR
        )

        /**
         * 挑出第一個耗時在 [MAX_ACCEPTABLE_MILLIS] 內的參數組合。
         *
         * [measure] 應實際以該組參數跑一次 KDF 並回傳毫秒數。
         *
         * 若連 [FLOOR] 都超過預算（極慢的裝置），仍回傳 [FLOOR]——寧可解鎖慢，
         * 也不能讓使用者無法建立資料庫，更不能為了速度把 KDF 削到不安全。
         * 呼叫端應在此情況下告知使用者解鎖會較慢，用 [exceedsBudget] 判斷。
         */
        fun calibrate(measure: (KdfParams) -> Long): KdfParams {
            for (params in CALIBRATION_LADDER) {
                if (measure(params) <= MAX_ACCEPTABLE_MILLIS) return params
            }
            return FLOOR
        }

        /** 這組參數在本機的實測耗時是否超出預算，供 UI 決定要不要提醒使用者。 */
        fun exceedsBudget(measuredMillis: Long): Boolean =
            measuredMillis > MAX_ACCEPTABLE_MILLIS
    }
}
