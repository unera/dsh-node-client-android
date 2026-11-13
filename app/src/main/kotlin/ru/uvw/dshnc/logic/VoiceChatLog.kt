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
    )

    /** What a write did, so the screen knows how much to redraw. */
    enum class Change { ADDED, REPLACED, IGNORED }

    private val items = ArrayList<Entry>()

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
    fun put (id: String, role: VoiceChat.Role, text: String): Change {
        if (role == VoiceChat.Role.USER) {
            val index = items.indexOfLast { it.id == id && it.placeholder }
            if (index >= 0) {
                items[index] = items[index].copy(text = text, placeholder = false)
                return Change.REPLACED
            }
        }
        // the same line twice is the same line: a retried command or a replayed event must not
        // turn one remark into two
        val duplicate = items.any { it.id == id && it.role == role && it.text == text }
        if (duplicate) return Change.IGNORED
        items.add(Entry(id, role, text, placeholder = false))
        trim()
        return Change.ADDED
    }

    /** A line the screen writes itself: a refusal, a note, never part of the exchange. */
    fun system (text: String, id: String = "") {
        items.add(Entry(id, VoiceChat.Role.SYSTEM, text))
        trim()
    }

    /** Drop every line: a re-paired node starts a new conversation. */
    fun clear () {
        items.clear()
    }

    /** Forget the oldest lines: a car ride is long, the phone's memory is not. */
    private fun trim () {
        while (items.size > limit) items.removeAt(0)
    }
}
