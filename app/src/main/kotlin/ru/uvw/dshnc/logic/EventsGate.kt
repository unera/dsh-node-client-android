package ru.uvw.dshnc.logic

import ru.uvw.dshnc.api.ApiException

/**
 * The host's answer when a device event is not allowed for this node.
 *
 * Since `dsh-nodes` 0.4.0 a node has an owner-controlled switch for device events:
 * `notify-button`, `notify-session`, `new-session`, `voice-warmup` and `bug-report` are refused
 * with `403 events_disabled` until the owner turns them on in the panel. Voice recordings and
 * commands keep working, so the app keeps working too — the only symptom is that a press or a
 * wake-up does nothing, which from the phone is indistinguishable from a slow host.
 *
 * That is why this exists: a refusal a person can act on has to be said out loud somewhere,
 * and "silence on purpose" was the right policy only while every refusal was transient.
 */
object EventsGate {
    /** The machine-readable code `dsh-nodes` sends with that 403. */
    const val DISABLED_CODE = "events_disabled"

    /** The sentence shown to a person when this is the refusal, in the app's own words. */
    const val DISABLED_TEXT = "события с устройства выключены в панели узла — включите «Events allowed»"

    /**
     * A sentence for the person, or null when the failure is not about the switch.
     *
     * @param error whatever the call threw.
     * @returns the explanation to show, or null when the caller should stay silent.
     */
    fun explain (error: Throwable?): String? {
        val refusal = error as? ApiException ?: return null
        return if (refusal.code == DISABLED_CODE) DISABLED_TEXT else null
    }

    /** Whether this failure is the owner's switch and not a dead network. */
    fun isDisabled (error: Throwable?): Boolean = explain(error) != null
}
