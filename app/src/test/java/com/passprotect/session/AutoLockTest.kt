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
    fun locksImmediatelyOnBackgroundByDefault() {
        val (autoLock, _, locks) = subject()
        autoLock.onEnterBackground()
        assertEquals(1, locks())
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
        assertEquals(0L, policy.backgroundGraceMillis)
        assertEquals(5 * 60 * 1000L, policy.idleTimeoutMillis)
    }
}
