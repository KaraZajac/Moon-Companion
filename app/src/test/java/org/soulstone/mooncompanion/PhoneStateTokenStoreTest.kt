package org.soulstone.mooncompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the token-reuse contract that makes re-pairing automatic: a
 * PairRequest from an already-bonded peer must resolve to the same auth
 * token across sessions, and different peers must get different tokens.
 */
class PhoneStateTokenStoreTest {

    @Test fun `stores token on first pair and reuses it next time`() {
        val store = InMemoryBondedTokenStore()
        val state = PhoneState()
        val addr = "AA:BB:CC:DD:EE:FF"

        val first = state.authTokenFor(addr, store)
        assertTrue(state.paired)
        assertEquals(16, first.size)
        assertEquals(first.toList(), store.get(addr)!!.toList())

        // Fresh state simulates a reconnect after service restart.
        val state2 = PhoneState()
        val second = state2.authTokenFor(addr, store)
        assertEquals(first.toList(), second.toList())
        assertEquals(first.toList(), state2.authToken.toList())
    }

    @Test fun `different addresses get different tokens`() {
        val store = InMemoryBondedTokenStore()
        val state = PhoneState()
        val tokenA = state.authTokenFor("AA:AA:AA:AA:AA:AA", store)
        val tokenB = state.authTokenFor("BB:BB:BB:BB:BB:BB", store)
        assertNotEquals(tokenA.toList(), tokenB.toList())
    }

    @Test fun `address lookup is case-insensitive`() {
        val store = InMemoryBondedTokenStore()
        val state = PhoneState()
        val upper = state.authTokenFor("AA:BB:CC:DD:EE:FF", store)
        val lower = state.authTokenFor("aa:bb:cc:dd:ee:ff", store)
        assertEquals(upper.toList(), lower.toList())
    }

    @Test fun `remove forgets the token`() {
        val store = InMemoryBondedTokenStore()
        val state = PhoneState()
        val addr = "AA:BB:CC:DD:EE:FF"
        val first = state.authTokenFor(addr, store)
        store.remove(addr)
        assertNull(store.get(addr))
        val second = PhoneState().authTokenFor(addr, store)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test fun `resetPairing clears instance state but not the store`() {
        val store = InMemoryBondedTokenStore()
        val state = PhoneState()
        val addr = "AA:BB:CC:DD:EE:FF"
        state.authTokenFor(addr, store)
        state.resetPairing()
        assertFalse(state.paired)
        assertEquals(0, state.authToken.size)
        // Persisted token survives a soft reset — only BOND_NONE should
        // evict it, which happens through the store's remove() path.
        assertEquals(16, store.get(addr)!!.size)
    }
}
