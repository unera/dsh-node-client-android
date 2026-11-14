package ru.uvw.dshnc.logic

/**
 * The overflow menu of the voice screen.
 *
 * The two habits of the button used to live on the screen as squares floating over it; in a car
 * they were in the way and half of them was outside the circle anyway. They are chosen from the
 * menu now, together with the one action that has to be reachable somewhere: starting a new
 * conversation.
 *
 * The list is plain data so that the order, the captions and the mark of the current habit can be
 * tested without a screen.
 */
object VoiceMenu {
    /** One row of the menu; a separator is a row with no action. */
    data class Item (
        val id: String,
        val title: String,
        /** The small grey line under the title, for the two modes. */
        val subtitle: String?,
        /** A checkmark next to the habit or the form that is set right now. */
        val checked: Boolean,
        /** A divider between groups: nothing to tap, nothing to read. */
        val separator: Boolean = false,
    )

    const val NEW_SESSION = "new-session"
    const val MODE_HOLD = "mode-hold"
    const val MODE_TAP = "mode-tap"
    const val SEPARATOR = "separator"

    const val ANSWER_SHORT = "answer-short"
    const val ANSWER_LONG = "answer-long"
    const val ANSWER_BOTH = "answer-both"

    /**
     * The rows, in the order they are shown.
     *
     * @param mode the habit that is set right now.
     * @returns three rows: the new conversation, then the two habits with their explanations.
     */
    fun items (mode: VoiceMode, answerMode: AnswerMode = AnswerMode.LONG): List<Item> = listOf(
        Item(
            id = NEW_SESSION,
            title = "Новая сессия",
            subtitle = null,
            checked = false,
        ),
        Item(
            id = MODE_HOLD,
            title = "Держите и говорите",
            subtitle = "Говорите, удерживая кнопку с микрофоном нажатой.",
            checked = mode == VoiceMode.PTT,
        ),
        Item(
            id = MODE_TAP,
            title = "Тапните для записи",
            subtitle = "Первый тап — включает запись, второй — завершает и отправляет голосовое сообщение.",
            checked = mode == VoiceMode.TOGGLE,
        ),
        Item(id = SEPARATOR, title = "Показывать ответы", subtitle = null, checked = false, separator = true),
        Item(ANSWER_SHORT, AnswerMode.SHORT.title, null, answerMode == AnswerMode.SHORT),
        Item(ANSWER_LONG, AnswerMode.LONG.title, null, answerMode == AnswerMode.LONG),
        Item(ANSWER_BOTH, AnswerMode.BOTH.title, null, answerMode == AnswerMode.BOTH),
    )

    /** Which form an item selects, or null when the item is not about a form. */
    fun answerModeOf (id: String): AnswerMode? = when (id) {
        ANSWER_SHORT -> AnswerMode.SHORT
        ANSWER_LONG -> AnswerMode.LONG
        ANSWER_BOTH -> AnswerMode.BOTH
        else -> null
    }

    /** Which habit an item selects, or null when the item is not about a habit. */
    fun modeOf (id: String): VoiceMode? = when (id) {
        MODE_HOLD -> VoiceMode.PTT
        MODE_TAP -> VoiceMode.TOGGLE
        else -> null
    }
}

/**
 * The clock on the record button: elapsed against the ceiling.
 *
 * Shown as `0:07 / 0:30` — minutes and seconds, no leading zero on minutes, two digits on seconds.
 * A person glancing down must see both numbers at once: how long they have been talking and how
 * long they may still talk.
 */
object VoiceTimer {
    /** A take stops by itself here, so a stuck button cannot hold the microphone for long. */
    const val LIMIT_SECONDS = 30

    /** `0:07 / 0:30` for seven seconds of a thirty-second ceiling. */
    fun label (elapsedSeconds: Double, limitSeconds: Int = LIMIT_SECONDS): String {
        val limit = limitSeconds.coerceAtLeast(1)
        val elapsed = when {
            !elapsedSeconds.isFinite() || elapsedSeconds <= 0 -> 0
            elapsedSeconds > limit -> limit
            else -> elapsedSeconds.toInt()
        }
        return "${clock(elapsed)} / ${clock(limit)}"
    }

    private fun clock (totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
