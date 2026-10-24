package com.passprotect.session

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLockTest {

    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun subject(
        policy: AutoLockPolicy = AutoLockPolicy(),
        clock: Clock = Clock()
    ): Triple<AutoLock, Clock, () -> Int> {
        var lockCount = 0
        val autoLock = AutoLock(policy, clock::read) { lockCount++ }
        return Triple(autoLock, clock) { lockCount }
    }

    @Test
    fun locksImmediatelyOnBackgroundWhenGraceIsZero() {
        val (autoLock, _, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 0))
        autoLock.onEnterBackground()
        assertEquals(1, locks())
    }

    @Test
    fun defaultGraceLetsYouSwitchAppsBriefly() {
        val (autoLock, clock, locks) = subject()
        autoLock.onEnterBackground()
        clock.now += 20_000
        autoLock.onReturnToForeground()
        assertEquals(0, locks())
    }

    @Test
    fun locksOnScreenOff() {
        val (autoLock, _, locks) = subject()
        autoLock.onScreenOff()
        assertEquals(1, locks())
    }

    @Test
    fun graceperiodDelaysBackgroundLock() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        assertEquals(0, locks())

        clock.now += 29_000
        autoLock.tick()
        assertEquals(0, locks())

        clock.now += 2_000
        autoLock.tick()
        assertEquals(1, locks())
    }

    @Test
    fun returningToForegroundWithinGraceCancelsLock() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        clock.now += 10_000
        autoLock.onReturnToForeground()

        clock.now += 60_000
        autoLock.tick()
        assertEquals(0, locks())
    }

    // 背景時前景的計時器不會跑（程序也可能被系統凍結），寬限期要在回到前景那一刻補判：
    // 之前回來就直接清掉 backgroundedAt，寬限期大於 0 時等於永遠不會因離開而上鎖。
    @Test
    fun returningAfterGraceLocks() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        clock.now += 31_000
        autoLock.onReturnToForeground()
        assertEquals(1, locks())
    }

    @Test
    fun returningExactlyAtGraceDeadlineDoesNotLock() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()
        clock.now += 30_000
        autoLock.onReturnToForeground()
        assertEquals(0, locks())
    }

    @Test
    fun locksAfterIdleTimeout() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 5 * 60 * 1000))
        autoLock.onUserInteraction()

        clock.now += 5 * 60 * 1000 + 1
        autoLock.tick()
        assertEquals(1, locks())
    }

    @Test
    fun interactionResetsIdleTimer() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 60_000))
        autoLock.onUserInteraction()

        clock.now += 50_000
        autoLock.onUserInteraction()
        clock.now += 50_000
        autoLock.tick()

        assertEquals(0, locks())
    }

    @Test
    fun doesNotLockTwiceForTheSameIdlePeriod() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 60_000))
        autoLock.onUserInteraction()

        clock.now += 120_000
        autoLock.tick()
        autoLock.tick()

        assertEquals(1, locks())
    }

    @Test
    fun doesNotLockAtExactlyTheIdleDeadline() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(idleTimeoutMillis = 60_000))
        autoLock.onUserInteraction()

        clock.now += 60_000  // 剛好等於門檻，不是超過
        autoLock.tick()

        assertEquals(0, locks())
    }

    @Test
    fun doesNotLockAtExactlyTheGraceDeadline() {
        val (autoLock, clock, locks) = subject(AutoLockPolicy(backgroundGraceMillis = 30_000))
        autoLock.onEnterBackground()

        clock.now += 30_000  // 剛好等於寬限期，不是超過
        autoLock.tick()

        assertEquals(0, locks())
    }

    @Test
    fun defaultPolicyMatchesSpec() {
        val policy = AutoLockPolicy()
        assertEquals(true, policy.lockOnBackground)
        assertEquals(30_000L, policy.backgroundGraceMillis)
        assertEquals(5 * 60 * 1000L, policy.idleTimeoutMillis)
    }
}
