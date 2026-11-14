package ru.uvw.dshnc.logic

import java.io.File

/**
 * A take that did not reach the host, kept so it can be sent again without repeating the words.
 *
 * The screen that recorded it may be closed and reopened by then — a phone under a bad signal is
 * exactly where that happens — so the file, its length and the exchange id live here, in the
 * process, and not in a view. The id is the point: a first attempt that actually arrived makes the
 * host drop the repeat instead of turning one remark into two.
 */
object VoiceParked {
    /** One take waiting for a second chance. */
    data class Take (val file: File, val seconds: Double, val id: String, val mode: String)

    private val lock = Object()
    private var take: Take? = null
    private var sending = false

    /** Keep the take for a repeat; replaces any earlier one, since only one can be pending. */
    fun remember (file: File, seconds: Double, id: String, mode: String) {
        synchronized(lock) {
            take?.file?.let { if (it.absolutePath != file.absolutePath) runCatching { it.delete() } }
            take = Take(file, seconds, id, mode)
            sending = false
        }
    }

    /** What is waiting, if anything. */
    fun snapshot (): Take? = synchronized(lock) { take }

    /** Is a repeat on its way right now? The link stays inert while it is. */
    fun isSending (): Boolean = synchronized(lock) { sending }

    /**
     * Claim the right to send.
     *
     * @returns false when a repeat is already in flight: a second tap must not start a second
     * attempt with the same id.
     */
    fun beginSend (): Boolean = synchronized(lock) {
        if (sending || take == null) return false
        sending = true
        true
    }

    /** The attempt is over, whatever its outcome. */
    fun endSend () {
        synchronized(lock) { sending = false }
    }

    /** The take reached the host: drop the file and forget it. */
    fun clear () {
        synchronized(lock) {
            take?.file?.let { runCatching { it.delete() } }
            take = null
            sending = false
        }
    }
}
