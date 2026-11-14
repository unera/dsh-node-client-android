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

    /** Where one square mode button sits, in pixels, relative to the stage. */
    data class Box (val left: Int, val top: Int, val size: Int)

    /**
     * The two mode buttons above the circle, symmetric about its centre.
     *
     * Both stand on the same line and hover over the circle's left and right edges, not over its
     * centre: a thumb reaching for the habit should not cover the button that talks. The bottom
     * edge of each square overlaps the top of the circle by [overlapPx], so the pair reads as
     * attached to it.
     *
     * @param width stage width in pixels.
     * @param diameter the circle's diameter in pixels.
     * @param size side of one square button in pixels.
     * @param overlapPx how far the squares hang over the circle.
     * @returns the left («держать») and the right («переключение») boxes.
     */
    fun modeButtonBoxes (width: Int, diameter: Int, size: Int, overlapPx: Int): List<Box> {
        val centre = width / 2
        val half = size / 2
        val left = Box(left = centre - diameter / 2 - half, top = overlapPx, size = size)
        val right = Box(left = centre + diameter / 2 - half, top = overlapPx, size = size)
        return listOf(left, right)
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

    /** The mode buttons are inert while a take runs: a mis-tap must not change the habit midway. */
    fun modeButtonsEnabled (recording: Boolean): Boolean = !recording

    /** Their alpha, so the person sees they are inert: 40% while recording, full otherwise. */
    fun modeButtonsAlpha (recording: Boolean): Float = if (recording) 0.4f else 1f

    private fun format (seconds: Double): String {
        val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
        return String.format(java.util.Locale.US, "%.1f", safe)
    }
}
