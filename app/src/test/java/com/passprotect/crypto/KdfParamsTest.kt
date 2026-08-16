package com.passprotect.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun ladderNeverGoesBelowTheSecurityFloor() {
        // 這是安全決策，不是實作細節：階梯不得再往下加更弱的級數。
        assertEquals(16L, KdfParams.FLOOR.memoryMib)
        assertEquals(KdfParams.FLOOR, KdfParams.CALIBRATION_LADDER.last())
        assertTrue(KdfParams.CALIBRATION_LADDER.all { it.memoryMib >= KdfParams.FLOOR.memoryMib })
    }

    @Test
    fun calibrateReturnsFloorRatherThanSomethingWeakerOnAVerySlowDevice() {
        val chosen = KdfParams.calibrate { 60_000L }
        assertEquals(KdfParams.FLOOR, chosen)
        assertTrue("絕不可為了速度低於安全下限", chosen.memoryMib >= 16L)
    }

    @Test
    fun exceedsBudgetReportsWhetherUiShouldWarn() {
        assertTrue(KdfParams.exceedsBudget(KdfParams.MAX_ACCEPTABLE_MILLIS + 1))
        assertFalse(KdfParams.exceedsBudget(KdfParams.MAX_ACCEPTABLE_MILLIS))
        assertFalse(KdfParams.exceedsBudget(500L))
    }
}
