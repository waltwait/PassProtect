package com.hintvault.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    @Test
    fun startsLocked() {
        assertEquals(LockState.Locked, VaultSession().state.value)
    }

    @Test
    fun unlockMovesToUnlocked() {
        val session = VaultSession()
        session.unlock(ByteArray(32) { 7 })
        assertEquals(LockState.Unlocked, session.state.value)
    }

    @Test
    fun withKeyExposesTheKeyWhileUnlocked() {
        val session = VaultSession()
        val key = ByteArray(32) { 7 }
        session.unlock(key.copyOf())

        val seen = session.withKey { it.copyOf() }
        assertArrayEquals(key, seen)
    }

    @Test
    fun lockZeroesTheKeyInMemory() {
        val session = VaultSession()
        val key = ByteArray(32) { 7 }
        session.unlock(key)   // session 接管這個陣列

        session.lock()

        // 規格 §4.5：上鎖時清除記憶體中的 vault key
        assertArrayEquals(ByteArray(32), key)
    }

    @Test
    fun lockMovesBackToLocked() {
        val session = VaultSession()
        session.unlock(ByteArray(32))
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }

    @Test
    fun withKeyThrowsWhenLocked() {
        var threw = false
        try {
            VaultSession().withKey { it.size }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unlockingTwiceZeroesTheOldKey() {
        val session = VaultSession()
        val first = ByteArray(32) { 1 }
        session.unlock(first)
        session.unlock(ByteArray(32) { 2 })

        assertArrayEquals(ByteArray(32), first)
    }

    @Test
    fun lockIsIdempotent() {
        val session = VaultSession()
        session.unlock(ByteArray(32))
        session.lock()
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }
}
