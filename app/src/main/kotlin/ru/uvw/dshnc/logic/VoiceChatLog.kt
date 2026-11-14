package ru.uvw.dshnc.logic

/**
 * The conversation as the screen shows it.
 *
 * Two sources write here: the phone itself (a take, before anyone knows what was said) and the
 * host (`voice.chat`, with the transcript and the answer). They meet on one key — the exchange id
 * minted when the button was pressed — so a take first appears as «вы · 3.2 с», and the transcript
 * that arrives later replaces that line instead of appearing next to it.
 */
object VoiceChat {
    /** Roles a line may have; `system` is the screen's own remarks, never the host's. */
    enum class Role {
        USER, ASSISTANT, SYSTEM;

        companion object {
            /** The wire words: `user` and `assistant`. Anything else is not a role. */
            fun parse (value: String?): Role? = when (value?.trim()?.lowercase()) {
                "user" -> USER
                "assistant" -> ASSISTANT
                else -> null
            }
        }
    }

    /** Longest text the screen keeps of one replique, as agreed with the host. */
    const val MAX_TEXT = 4000

    /**
     * What to say for one line.
     *
     * The host may send a line meant only to be read — a command dump, a table, a path — together
     * with a short sentence meant to be heard. Speaking the display text made the phone recite the
     * dump; the spoken text always wins when it is given.
     */
    fun speechText (text: String, speak: String?): String =
        speak?.takeIf { it.isNotBlank() } ?: text

    /** One run of a displayed line: either prose or a fenced code block. */
    data class Segment (val code: Boolean, val text: String)

    /**
     * Split a line into prose and code.
     *
     * Only fences are understood: the screen is a conversation in a car, not a document viewer.
     * Marker lines disappear, their content is drawn monospaced on a grey field, and everything
     * else stays as it came — line breaks included.
     */
    fun segments (text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<Segment>()
        val prose = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        fun flushProse () {
            if (prose.isNotEmpty()) {
                result.add(Segment(false, prose.toString()))
                prose.setLength(0)
            }
        }
        fun flushCode () {
            if (code.isNotEmpty()) {
                result.add(Segment(true, code.toString().trimEnd('\n')))
                code.setLength(0)
            }
        }
        // a fence may open, close or open-and-close on one line; anything after the marker on the
        // marker's own line is language info and is dropped
        for (line in text.split("\n")) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    flushCode()
                    inCode = false
                } else {
                    flushProse()
                    inCode = true
                }
                continue
            }
            if (inCode) code.append(line).append('\n') else prose.append(line).append('\n')
        }
        if (inCode) flushCode() else flushProse()
        return result.map { if (it.code) it else it.copy(text = it.text.trimEnd('\n')) }
            .filter { it.text.isNotBlank() }
    }

    /** Who answers: a name and, when the host has one, an emoji. */
    data class Whom (val name: String, val icon: String? = null)

    /**
     * The screen title.
     *
     * An empty conversation keeps the plain name of the screen — there is nothing to introduce yet.
     * Once a line exists and the host has said who answers, the header becomes that assistant: an
     * emoji and a name, large, instead of a description of the screen.
     *
     * @param who who answers, when the host has told us.
     * @param hasLines whether the conversation has at least one line.
     */
    fun title (who: Whom?, hasLines: Boolean): String {
        val name = who?.name?.trim().orEmpty()
        if (!hasLines || name.isEmpty()) return "Голосовой чат"
        val icon = who?.icon?.trim().orEmpty()
        return if (icon.isEmpty()) name else "$icon $name"
    }

    /** Cut an overlong text, marking the cut so nobody thinks the sentence ended there. */
    fun clip (text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= MAX_TEXT) trimmed else trimmed.take(MAX_TEXT - 1) + "…"
    }
}

/**
 * The ordered list of lines, with the pairing rule in one place.
 *
 * Not thread-safe on its own: the screen and the command loop both write, so [VoiceChatBus] wraps
 * it in a lock. Keeping the rules here means they are tested without Android.
 */
class VoiceChatLog (private val limit: Int = 200) {
    /** One line: the exchange it belongs to, who said it, and whether it is still a placeholder. */
    data class Entry (
        val id: String,
        val role: VoiceChat.Role,
        val text: String,
        val placeholder: Boolean = false,
        /** A caption under the person's line: how the host took the take. Dropped by a transcript. */
        val note: String? = null,
        /** How many steps the answer took; null when there were none to mention. */
        val steps: Int? = null,
        /** The assistant's name, on the first answer of an exchange only. */
        val who: String? = null,
        /** The pieces to draw: the forms of an answer, with the line between them when both. */
        val parts: List<AnswerPart> = emptyList(),
    )

    /** What a write did, so the screen knows how much to redraw. */
    enum class Change { ADDED, REPLACED, IGNORED }

    private val items = ArrayList<Entry>()
    /** Steps counted per exchange while the answer is being produced: a counter, not a history. */
    private val steps = HashMap<String, Int>()

    fun snapshot (): List<Entry> = items.toList()

    /** Register a take that has no transcript yet: it is shown as a placeholder for its id. */
    fun recording (id: String, text: String): Change {
        items.add(Entry(id, VoiceChat.Role.USER, text, placeholder = true))
        trim()
        return Change.ADDED
    }

    /**
     * Put one line from the host, pairing it with the take when it is the transcript of that take.
     *
     * @param id the exchange the line belongs to.
     * @param role who says it.
     * @param text already clipped text.
     * @returns `REPLACED` when a placeholder became the transcript, `ADDED` otherwise.
     */
    fun put (
        id: String,
        role: VoiceChat.Role,
        text: String,
        who: String? = null,
        parts: List<AnswerPart> = emptyList(),
    ): Change {
        if (role == VoiceChat.Role.USER) {
            val index = items.indexOfLast { it.id == id && it.placeholder }
            if (index >= 0) {
                // the transcript is all the person needs: a caption about the take is stale now
                items[index] = items[index].copy(text = text, placeholder = false, note = null)
                return Change.REPLACED
            }
        }
        // the same line twice is the same line: a retried command or a replayed event must not
        // turn one remark into two
        val duplicate = items.any { it.id == id && it.role == role && it.text == text }
        if (duplicate) return Change.IGNORED
        val count = if (role == VoiceChat.Role.ASSISTANT) steps.remove(id) else null
        // the name is worth saying once per exchange: repeating it before every paragraph is noise
        val name = when {
            role != VoiceChat.Role.ASSISTANT -> null
            who.isNullOrBlank() -> null
            items.any { it.id == id && it.role == VoiceChat.Role.ASSISTANT } -> null
            else -> who.trim()
        }
        items.add(Entry(id, role, text, placeholder = false, steps = count, who = name, parts = parts))
        trim()
        return Change.ADDED
    }

    /** Count one step of the work for this exchange; the number rides on the following answer. */
    fun countStep (id: String) {
        if (id.isEmpty()) return
        steps[id] = (steps[id] ?: 0) + 1
    }

    /**
     * Put a caption under the person's line for this exchange.
     *
     * @returns false when the take is already a transcript: the caption would be noise then.
     */
    fun note (id: String, text: String): Boolean {
        if (id.isEmpty()) return false
        val index = items.indexOfLast { it.id == id && it.role == VoiceChat.Role.USER }
        if (index < 0) return false
        if (!items[index].placeholder) return false
        items[index] = items[index].copy(note = text)
        return true
    }

    /** A line the screen writes itself: a refusal, a note, never part of the exchange. */
    fun system (text: String, id: String = "") {
        items.add(Entry(id, VoiceChat.Role.SYSTEM, text))
        trim()
    }

    /** Drop every line: a re-paired node starts a new conversation. */
    fun clear () {
        items.clear()
        steps.clear()
    }

    /** Forget the oldest lines: a car ride is long, the phone's memory is not. */
    private fun trim () {
        while (items.size > limit) items.removeAt(0)
    }
}
