package org.fossify.gallery.family

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTokensTest {
    private var now = 1_000_000L
    private val tokens = PairingTokens(clock = { now })

    @Test
    fun tokenIs256BitAndStableWhileValid() {
        val t = tokens.currentOrIssue()
        assertEquals(32, t.size)
        now += PairingTokens.TTL_MS - 1
        assertArrayEquals(t, tokens.currentOrIssue())
    }

    @Test
    fun expiredTokenIsRejectedWithoutAttempt() {
        tokens.issue()
        now += PairingTokens.TTL_MS
        var called = false
        assertNull(tokens.consume { called = true; "ok" })
        assertFalse(called)
        assertNull(tokens.current())
    }

    @Test
    fun usedTokenIsRejected() {
        val t = tokens.issue()
        assertEquals("ok", tokens.consume { if (it.contentEquals(t)) "ok" else null })
        var called = false
        assertNull(tokens.consume { called = true; "again" })
        assertFalse(called)
    }

    @Test
    fun failedAttemptKeepsTokenUntilLimit() {
        tokens.issue()
        repeat(PairingTokens.MAX_FAILURES - 1) { assertNull(tokens.consume<String> { null }) }
        assertNotNull(tokens.current())
        assertNull(tokens.consume<String> { null })
        assertNull(tokens.current())
    }

    @Test
    fun newIssueReplacesOldToken() {
        val old = tokens.issue()
        val fresh = tokens.issue()
        assertFalse(old.contentEquals(fresh))
        assertTrue(tokens.consume { if (it.contentEquals(old)) "old" else null } == null)
        assertEquals("fresh", tokens.consume { if (it.contentEquals(fresh)) "fresh" else null })
    }
}
