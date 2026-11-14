package ru.uvw.dshnc.logic

/**
 * How a line of the voice conversation is painted.
 *
 * A conversation read at a glance is easier than a wall of aligned text: the person's remark is a
 * blue bubble on the right, the answer a dark one on the left. Nothing here knows about views —
 * the screen asks and applies, the tests check the answers.
 */
object VoiceBubble {
    /** The person's own words. */
    const val USER_BACKGROUND = "#2563eb"
    const val USER_TEXT = "#ffffff"

    /** The answer. */
    const val ASSISTANT_BACKGROUND = "#1f2937"
    const val ASSISTANT_TEXT = "#e5e7eb"

    /** A fenced block inside a bubble: darker than the bubble so it does not merge with it. */
    const val CODE_BACKGROUND = "#111827"

    /** Service notes are not remarks: no bubble at all. */
    const val SYSTEM_TEXT = "#6b7280"

    const val RADIUS_DP = 16
    const val PADDING_H_DP = 12
    const val PADDING_V_DP = 8

    /** A bubble never takes the whole width: the gap on the side is what makes the side visible. */
    const val MAX_WIDTH_FRACTION = 0.85

    /** Between two bubbles of the same exchange. */
    const val GAP_DP = 8

    /** Between two exchanges: a wider pause, so the pairs are visible. */
    const val EXCHANGE_GAP_DP = 16

    /** The bubble colour for a role, or null when the line has no bubble. */
    fun background (role: VoiceChat.Role): String? = when (role) {
        VoiceChat.Role.USER -> USER_BACKGROUND
        VoiceChat.Role.ASSISTANT -> ASSISTANT_BACKGROUND
        VoiceChat.Role.SYSTEM -> null
    }

    /** The text colour inside that bubble. */
    fun textColor (role: VoiceChat.Role): String = when (role) {
        VoiceChat.Role.USER -> USER_TEXT
        VoiceChat.Role.ASSISTANT -> ASSISTANT_TEXT
        VoiceChat.Role.SYSTEM -> SYSTEM_TEXT
    }

    /** Which side the bubble sticks to: the person on the right, the answer on the left. */
    fun alignment (role: VoiceChat.Role): String = when (role) {
        VoiceChat.Role.USER -> "end"
        VoiceChat.Role.ASSISTANT -> "start"
        VoiceChat.Role.SYSTEM -> "start"
    }

    /** The widest a bubble may be, in pixels, for a screen of this width. */
    fun maxWidthPx (screenWidthPx: Int): Int = (screenWidthPx * MAX_WIDTH_FRACTION).toInt()

    /**
     * The space above a line.
     *
     * @param previousId the exchange of the line before, or null at the top of the conversation.
     * @param id this line's exchange.
     * @returns a wider gap when the exchange changes, a small one inside a pair.
     */
    fun gapDp (previousId: String?, id: String): Int =
        if (previousId != null && previousId == id) GAP_DP else EXCHANGE_GAP_DP
}
