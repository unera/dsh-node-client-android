package ru.uvw.dshnc.logic

/**
 * Is this refusal the server saying "your token is dead"?
 *
 * Looks trivial and is not: a proxy, a captive portal or a crashed server answer 401 too,
 * sometimes with an HTML page instead of our JSON. Wiping the token because of that would
 * cost a node that was never revoked, and the desktop client therefore checks the body, not
 * the status alone (`bad_response` instead of `unauthorized`, api.mjs).
 *
 * Here the body check is implicit: `code` is only ever filled from our own JSON, so a 401
 * without a parseable body — an HTML page, or the empty answer to a socket upgrade — comes
 * with `code = null` and must not be read as a revocation.
 *
 * Kept pure and free of Android so that the rule is covered by a unit test.
 */
object AuthFailure {
    /** The server's own word for "this token does not match any node". */
    const val UNAUTHORIZED = "unauthorized"

    fun isRevoked (status: Int, code: String?): Boolean = status == 401 && code == UNAUTHORIZED
}
