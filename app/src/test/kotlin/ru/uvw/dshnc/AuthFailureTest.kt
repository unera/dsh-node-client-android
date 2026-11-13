package ru.uvw.dshnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.AuthFailure

class AuthFailureTest {
    @Test
    fun `the server's own word about the token is a revocation`() {
        assertTrue(AuthFailure.isRevoked(401, AuthFailure.UNAUTHORIZED))
    }

    @Test
    fun `a bare 401 without our json is not a revocation`() {
        // an HTML page from a proxy, or the empty answer to a socket upgrade: the status is
        // the same, the meaning is not, and the token must survive it
        assertFalse(AuthFailure.isRevoked(401, null))
        assertFalse(AuthFailure.isRevoked(401, "bad_request"))
    }

    @Test
    fun `our word with another status is not a revocation either`() {
        assertFalse(AuthFailure.isRevoked(403, AuthFailure.UNAUTHORIZED))
        assertFalse(AuthFailure.isRevoked(409, AuthFailure.UNAUTHORIZED))
        assertFalse(AuthFailure.isRevoked(502, AuthFailure.UNAUTHORIZED))
    }
}
