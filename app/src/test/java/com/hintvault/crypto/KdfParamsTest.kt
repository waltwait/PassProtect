package com.hintvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KdfParamsTest {

    @Test
    fun defaultMatchesSpec() {
        assertEquals(32L, KdfParams.DEFAULT.memoryMib)
        assertEquals(2L, KdfParams.DEFAULT.iterations)
        assertEquals(2, KdfParams.DEFAULT.parallelism)
    }

    @Test
    fun ladderStartsAtDefaultAndWeakensMonotonically() {
        val ladder = KdfParams.CALIBRATION_LADDER
        assertEquals(KdfParams.DEFAULT, ladder.first())
        val costs = ladder.map { it.memoryMib * it.iterations }
        assertEquals(costs.sortedDescending(), costs)
    }

    @Test
    fun calibrateKeepsDefaultWhenFastEnough() {
        // 每一級都很快 → 應該用最強的
        val chosen = KdfParams.calibrate { 500L }
        assertEquals(KdfParams.DEFAULT, chosen)
    }

    @Test
    fun calibrateStepsDownWhenDefaultTooSlow() {
        // 只有最弱的一級在預算內
        val weakest = KdfParams.CALIBRATION_LADDER.last()
        val chosen = KdfParams.calibrate { params ->
            if (params == weakest) 1000L else 9000L
        }
        assertEquals(weakest, chosen)
    }

    @Test
    fun calibrateReturnsWeakestWhenEverythingTooSlow() {
        // 極慢的裝置：仍須回傳可用值，不能擲出例外
        val chosen = KdfParams.calibrate { 60_000L }
        assertEquals(KdfParams.CALIBRATION_LADDER.last(), chosen)
    }

    @Test
    fun calibrateStopsMeasuringOnceAcceptable() {
        val measured = mutableListOf<KdfParams>()
        KdfParams.calibrate { params -> measured.add(params); 100L }
        assertEquals(1, measured.size)
    }

    @Test
    fun budgetMatchesSpec() {
        assertTrue(KdfParams.MAX_ACCEPTABLE_MILLIS == 3000L)
    }
}
