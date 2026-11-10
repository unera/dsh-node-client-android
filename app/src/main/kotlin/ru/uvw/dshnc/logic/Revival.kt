package ru.uvw.dshnc.logic

/**
 * Whether the node may be brought up at all — the one rule both the alarm and the service ask.
 *
 * Turning the node off and being displaced by a newer instance of the same node look alike from
 * the outside: `enabled` stays on, the token stays valid, and yet there must be no network
 * activity. Without the second case the ten-minute alarm revives a displaced instance, it
 * announces a bigger `client_epoch`, pushes out the instance that replaced it, and that one's
 * own alarm revives it in turn: two instances ping-ponging forever.
 *
 * Kept pure and free of Android so that the rule is covered by a unit test.
 */
object Revival {
    /**
     * `enabled` — the person turned the node on; `hasToken` — it knows who it is;
     * `replaced` — a newer instance owns the channel, so this one stays down until a person
     * says otherwise. A timer is not a person.
     */
    fun allowed(enabled: Boolean, hasToken: Boolean, replaced: Boolean): Boolean =
        enabled && hasToken && !replaced
}
