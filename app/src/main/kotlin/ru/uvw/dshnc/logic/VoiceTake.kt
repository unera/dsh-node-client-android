package ru.uvw.dshnc.logic

/**
 * When a take is not worth sending.
 *
 * A press that lasted a fifth of a second is not a remark, but the recognizer still hears
 * *something*: in a live run a 0.2 s take came back as «良いです。» and became a turn in the
 * conversation. Sending noise costs a round trip and, worse, produces an answer to a question
 * nobody asked — so a take below the threshold is dropped here, before it leaves the phone.
 *
 * The thresholds are constants, not magic numbers at the call site: a microphone on a noisy
 * street and a quiet car do not agree on what "silence" looks like.
 */
object VoiceTake {
    /** Shortest take that can hold a word: below this it is a mistaken tap. */
    const val MIN_SECONDS = 0.4

    /** Anything smaller is a header and a handful of frames: nothing was said. */
    const val MIN_BYTES = 2_000L

    /**
     * Why this take should not be sent.
     *
     * @param seconds how long the take lasted.
     * @param bytes the size of the WAV file, header included.
     * @returns the reason to show the person, or null when the take is worth sending.
     */
    fun rejectReason (seconds: Double, bytes: Long): String? = when {
        !seconds.isFinite() || seconds <= 0.0 -> "слишком коротко, не отправлено"
        seconds < MIN_SECONDS -> "слишком коротко, не отправлено"
        bytes < MIN_BYTES -> "ничего не сказано, не отправлено"
        else -> null
    }
}
