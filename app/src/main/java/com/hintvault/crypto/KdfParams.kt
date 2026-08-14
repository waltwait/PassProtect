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

    companion object {
        val DEFAULT = KdfParams(memoryMib = 32, iterations = 2, parallelism = 2)

        /** 建立資料庫時允許的最長耗時。超過就往下一級退。 */
        const val MAX_ACCEPTABLE_MILLIS = 3000L

        /** 由強到弱。第一個在預算內的即為所選。 */
        val CALIBRATION_LADDER = listOf(
            DEFAULT,
            KdfParams(memoryMib = 24, iterations = 2, parallelism = 2),
            KdfParams(memoryMib = 16, iterations = 2, parallelism = 2),
            KdfParams(memoryMib = 8, iterations = 2, parallelism = 2)
        )

        /**
         * 挑出第一個耗時在 [MAX_ACCEPTABLE_MILLIS] 內的參數組合。
         *
         * [measure] 應實際以該組參數跑一次 KDF 並回傳毫秒數。
         * 若每一級都太慢（極慢的裝置），回傳最弱的一級——寧可慢，
         * 也不能讓使用者完全無法建立資料庫。
         */
        fun calibrate(measure: (KdfParams) -> Long): KdfParams {
            for (params in CALIBRATION_LADDER) {
                if (measure(params) <= MAX_ACCEPTABLE_MILLIS) return params
            }
            return CALIBRATION_LADDER.last()
        }
    }
}
