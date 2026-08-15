package com.hintvault.session

import com.hintvault.crypto.KdfParams
import com.hintvault.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    private fun vault() = Vault.create(KdfParams.FLOOR)

    @Test
    fun startsLocked() {
        assertEquals(LockState.Locked, VaultSession().state.value)
    }

    @Test
    fun unlockMovesToUnlocked() {
        val session = VaultSession()
        session.unlock(vault())
        assertEquals(LockState.Unlocked, session.state.value)
    }

    @Test
    fun withVaultExposesTheSameVaultInstance() {
        val session = VaultSession()
        val v = vault()
        session.unlock(v)

        val seen = session.withVault { it }
        assertSame(v, seen)
    }

    @Test
    fun lockMovesBackToLocked() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }

    @Test
    fun withVaultThrowsWhenLocked() {
        var threw = false
        try {
            VaultSession().withVault { it }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun withVaultThrowsAfterLock() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()

        var threw = false
        try {
            session.withVault { it }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unlockingTwiceReplacesThePreviousVault() {
        val session = VaultSession()
        session.unlock(vault())
        val second = vault()
        session.unlock(second)

        assertSame(second, session.withVault { it })
    }

    @Test
    fun lockIsIdempotent() {
        val session = VaultSession()
        session.unlock(vault())
        session.lock()
        session.lock()
        assertEquals(LockState.Locked, session.state.value)
    }
}
