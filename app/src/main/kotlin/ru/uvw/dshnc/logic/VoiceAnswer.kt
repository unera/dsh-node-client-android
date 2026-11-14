package ru.uvw.dshnc.logic

/**
 * What an answer looks like on the screen.
 *
 * The host sends two versions of one answer: `text`, the long form meant for reading, and `speak`,
 * the short one meant for the ear. People differ — in a car the short form is enough, at a desk the
 * long one is what you want, and sometimes both, with a line between them.
 */
enum class AnswerMode (val wire: String, val title: String) {
    SHORT("short", "Только короткую форму"),
    LONG("long", "Только длинную форму"),
    BOTH("both", "Всё, с чертой между ними");

    companion object {
        /** The stored choice, or the long form — which is what the screen showed before. */
        fun of (value: String?): AnswerMode =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: LONG
    }
}

/** One piece of an answer: a paragraph to render, or the line between the two forms. */
data class AnswerPart (val kind: Kind, val text: String = "") {
    enum class Kind { TEXT, DIVIDER }
}

/**
 * How the two forms of an answer are put together.
 *
 * The rules are here, not in the view, so that "all three modes" is one tested function rather
 * than three branches inside a layout.
 */
object VoiceAnswer {
    /**
     * Compose the visible answer.
     *
     * @param text the long form as the host sent it.
     * @param speak the short form, when the host sent one.
     * @param mode what the person asked to see.
     * @returns the pieces to draw, oldest first; never empty unless both forms were empty.
     */
    fun compose (text: String, speak: String?, mode: AnswerMode): List<AnswerPart> {
        val long = text.trim()
        val short = speak?.trim().orEmpty()
        val both = short.isNotEmpty() && long.isNotEmpty() && short != long
        return when (mode) {
            AnswerMode.SHORT -> listOfNotNull(
                (short.ifEmpty { long }).takeIf { it.isNotEmpty() }?.let { AnswerPart(AnswerPart.Kind.TEXT, it) },
            )
            AnswerMode.LONG -> listOfNotNull(
                (long.ifEmpty { short }).takeIf { it.isNotEmpty() }?.let { AnswerPart(AnswerPart.Kind.TEXT, it) },
            )
            AnswerMode.BOTH -> when {
                // the two forms are the same sentence: one paragraph, no line
                !both -> listOfNotNull((long.ifEmpty { short }).takeIf { it.isNotEmpty() }?.let { AnswerPart(AnswerPart.Kind.TEXT, it) })
                else -> listOf(
                    AnswerPart(AnswerPart.Kind.TEXT, short),
                    AnswerPart(AnswerPart.Kind.DIVIDER),
                    AnswerPart(AnswerPart.Kind.TEXT, long),
                )
            }
        }
    }

    /** The text a speech engine would read for this answer: the short form when there is one. */
    fun speech (text: String, speak: String?): String = speak?.takeIf { it.isNotBlank() }?.trim() ?: text.trim()
}
