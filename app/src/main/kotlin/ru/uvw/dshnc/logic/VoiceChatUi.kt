package ru.uvw.dshnc.logic

/**
 * The small decisions the voice screen makes about itself.
 *
 * Kept out of the activity so that they can be tested without a device: which hint belongs to a
 * mode, what the hint becomes while recording, and — the one that matters for a hand in a car —
 * that choosing a mode is never the same gesture as starting a take.
 */
class VoiceChatUi (initial: VoiceMode = VoiceMode.PTT) {
    /** The mode the screen is in: the button's habit, not a recording state. */
    var mode: VoiceMode = initial
        private set

    /** Set by the screen: it only redraws, it never touches the microphone. */
    @Volatile var onModeChanged: ((VoiceMode) -> Unit)? = null

    /**
     * Choose a mode.
     *
     * Deliberately does one thing: a person who taps «переключение» wants the other habit, not a
     * recording. The microphone is started by the big button alone.
     *
     * @returns true when the mode actually changed.
     */
    fun selectMode (next: VoiceMode): Boolean {
        if (next == mode) return false
        mode = next
        onModeChanged?.invoke(next)
        return true
    }

    /**
     * What stands above the big button.
     *
     * Always visible, so the person does not have to remember which habit is set; while a take is
     * running it becomes the clock, because then there is only one thing worth knowing.
     */
    fun hint (recording: Boolean, seconds: Double = 0.0): String = when {
        recording -> "слушаю · ${format(seconds)} с"
        mode == VoiceMode.PTT -> "Держите и говорите"
        else -> "Нажмите — говорите — нажмите"
    }

    private fun format (seconds: Double): String {
        val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        return String.format(java.util.Locale.US, "%.1f", safe)
    }
}
