package ru.uvw.dshnc.logic

import java.io.File

/**
 * How the one big button behaves.
 *
 * `ptt` is a radio: the microphone is open while the finger is on the button. `toggle` is a
 * switch: the first press starts, the second stops — easier in gloves and in a car. The choice
 * is the person's, so it is stored with the rest of the node state.
 */
enum class VoiceMode (val wire: String, val title: String) {
    PTT("ptt", "держать"),
    TOGGLE("toggle", "переключение");

    companion object {
        /** Read a mode from an argument or a stored setting; null when the word means nothing. */
        fun parse (value: String?): VoiceMode? {
            val word = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == word || it.name.lowercase() == word }
        }

        /** The stored choice, or the radio habit as the default. */
        fun of (value: String?): VoiceMode = parse(value) ?: PTT
    }
}

/**
 * The place where the screen and the host's command meet.
 *
 * The microphone belongs to the phone, but the host pays for the transfer with a command number:
 * `/v1/upload-file` is accepted only inside a command it issued (that number is the ticket). So a
 * recording taken while no command is waiting has nowhere to go yet — it is parked here, and the
 * next `voice.record` command takes it at once instead of waiting for another take.
 *
 * Two sides, one lock: the screen offers, the command thread takes. Everything else is status
 * for the screen to draw.
 */
object VoiceOutbox {
    /** One finished take, still on disk. */
    data class Recording(val file: File, val seconds: Double, val mode: String)

    /** What the screen may want to redraw. */
    enum class Event { RECORDED, TAKEN, WAITING }

    private val lock = Object()
    private var recorded: Recording? = null
    private var waiting = 0
    private var abandoned = false

    /** How long one take may be, in seconds: the command sets it, the screen reads it. */
    @Volatile var maxSeconds: Double = 120.0

    /** Set by the screen while it is on top; called outside the lock. */
    @Volatile var onEvent: ((Event, Recording?) -> Unit)? = null

    /** A command is now listening: `voice.record` is outstanding. */
    fun beginWait () {
        synchronized(lock) { waiting += 1 }
        onEvent?.invoke(Event.WAITING, null)
    }

    /** The command is done listening, whatever the outcome. */
    fun endWait () {
        synchronized(lock) { if (waiting > 0) waiting -= 1 }
        onEvent?.invoke(Event.WAITING, null)
    }

    /** Is a host command waiting for a take right now? */
    fun isWaiting (): Boolean = synchronized(lock) { waiting > 0 }

    /** Park a take and wake whoever is waiting for it. */
    fun offer (recording: Recording) {
        synchronized(lock) {
            // a newer take replaces an older unsent one: the person said the new thing on purpose
            recorded?.file?.delete()
            recorded = recording
            lock.notifyAll()
        }
        onEvent?.invoke(Event.RECORDED, recording)
    }

    /** Take the parked take without waiting, if there is one. */
    fun takeNow (): Recording? = synchronized(lock) {
        val take = recorded
        recorded = null
        take
    }

    /**
     * Wait for a take, up to a deadline.
     *
     * @param timeoutMs how long to wait; the command's own pause decides this.
     * @returns the take, or null when the deadline passed with nothing recorded.
     */
    fun await (timeoutMs: Long): Recording? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                val take = recorded
                if (take != null) {
                    recorded = null
                    onEvent?.invoke(Event.TAKEN, take)
                    return take
                }
                val left = deadline - System.currentTimeMillis()
                if (abandoned) {
                    abandoned = false
                    return null
                }
                if (left <= 0) return null
                try {
                    lock.wait(left)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** Stop every waiting command: the host cancelled, nothing is expected any more. */
    fun abandon () {
        synchronized(lock) {
            abandoned = true
            lock.notifyAll()
        }
    }

    /** Drop the parked take: it is not going to be sent. */
    fun clear () {        synchronized(lock) {
            recorded?.file?.delete()
            recorded = null
        }
    }
}
