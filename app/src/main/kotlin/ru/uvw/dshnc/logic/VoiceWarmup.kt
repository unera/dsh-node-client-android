package ru.uvw.dshnc.logic

import org.json.JSONObject

/**
 * Waking the host's synthesizer before it is needed.
 *
 * A Silero model on the host falls asleep after ten minutes of silence and takes about nine seconds
 * to load. Those nine seconds would otherwise land on the first sentence of the conversation, which
 * is exactly the moment a person is waiting. So opening the screen knocks on the host once — in
 * `host` mode only, since the phone's own engine needs no waking.
 *
 * It is a courtesy, not a step: the answer is ignored, a failure is ignored, nothing is written to
 * the conversation and nothing waits for it. And it is throttled, because walking in and out of the
 * screen must not become a stream of wake-ups.
 */
object VoiceWarmup {
    /** A wake-up per half a minute is plenty: the model sleeps for ten. */
    const val INTERVAL_MS = 30_000L

    /** The event type the host understands. */
    const val TYPE = "voice-warmup"

    /** How the event is actually sent; tests replace it with a counter, nobody else does. */
    @Volatile var transport: ((base: String, token: String) -> Unit)? = null

    /**
     * Why the last wake-up was refused, when the reason is one a person can act on.
     *
     * A wake-up stays a courtesy: a dead network is ignored as before. But "the owner turned
     * device events off" would otherwise look exactly like a slow host, so the sentence is
     * kept here and the voice screen shows it. Cleared by the next wake-up that goes through.
     */
    @Volatile var lastRefusal: String? = null
        private set

    private val lock = Object()
    private var lastSentAt = 0L

    /** The body, exactly as it goes on the wire. */
    fun body (): JSONObject = JSONObject().put("type", TYPE)

    /**
     * Send a wake-up if it is due.
     *
     * @param base the host root, when the node is paired.
     * @param token the node token, when it has one.
     * @param synth the chosen synthesis: only `host` has anything to wake.
     * @param now the clock, injectable for the throttle.
     * @returns true when a wake-up was actually started.
     */
    fun fire (base: String?, token: String?, synth: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (synth?.trim()?.lowercase() != "host") return false
        if (base.isNullOrBlank() || token.isNullOrBlank()) return false
        synchronized(lock) {
            if (now - lastSentAt < INTERVAL_MS) return false
            lastSentAt = now
        }
        val sender = transport
        Thread({
            // silence on purpose: a wake-up that failed changes nothing a person should see
            runCatching {
                if (sender != null) sender(base, token) else send(base, token)
            }
        }, "dshnc-voice-warmup").start()
        return true
    }

    private fun send (base: String, token: String) {
        try {
            ru.uvw.dshnc.api.NodeApi(base).sendEvent(token, body())
            lastRefusal = null
        } catch (error: Throwable) {
            // Only the switch is worth a sentence; everything else stays as silent as before.
            val refusal = EventsGate.explain(error) ?: return
            lastRefusal = refusal
        }
    }

    /** When the last wake-up went out: the throttle reads it, tests check it. */
    fun lastSent (): Long = synchronized(lock) { lastSentAt }

    /** Forget the throttle: a new screen lives its own life, the tests need a clean slate. */
    fun reset () {
        synchronized(lock) { lastSentAt = 0L }
    }
}
