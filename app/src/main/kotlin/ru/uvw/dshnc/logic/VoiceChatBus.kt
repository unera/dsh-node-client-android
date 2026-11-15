package ru.uvw.dshnc.logic

/**
 * The one conversation the screen and the command loop share.
 *
 * `voice.chat` runs in the node service, the lines are drawn by the activity, and both live in the
 * same process — but not in the same lifetime: a line may arrive while the screen is closed, and a
 * screen may open after the answer was already shown once. So the log is the truth, the listener is
 * only a nudge, and opening the screen redraws from [snapshot].
 */
object VoiceChatBus {
    private val lock = Object()
    private val log = VoiceChatLog()

    /** Called after any change; the activity hops to the main thread itself. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun snapshot (): List<VoiceChatLog.Entry> = synchronized(lock) { log.snapshot() }

    /** A take was recorded: a placeholder line for its exchange id. */
    fun recording (id: String, text: String) {
        synchronized(lock) { log.recording(id, text) }
        onChanged?.invoke()
    }

    /** A line that arrived from the host: stored without waking the screen, which drains its own queue. */
    fun putQuiet (
        id: String,
        role: VoiceChat.Role,
        text: String,
        who: VoiceChat.Whom? = null,
        parts: List<AnswerPart> = emptyList(),
        degraded: VoiceDegraded.Report? = null,
    ): VoiceChatLog.Change = synchronized(lock) { log.put(id, role, text, who, parts, degraded) }

    /** A line from the host: the transcript of a take, or the answer. */
    fun put (id: String, role: VoiceChat.Role, text: String, who: VoiceChat.Whom? = null): VoiceChatLog.Change {
        val change = synchronized(lock) { log.put(id, role, text, who) }
        if (change != VoiceChatLog.Change.IGNORED) onChanged?.invoke()
        return change
    }

    /** Count one step of the work; the number appears under the answer that follows. */
    fun countStep (id: String) {
        synchronized(lock) { log.countStep(id) }
    }

    /** A caption under the person's line, until the transcript replaces it. */
    fun note (id: String, text: String): Boolean = synchronized(lock) { log.note(id, text) }

    /** What came of a bug report: shown instead of the line's button. */
    fun reportResult (id: String, text: String): Boolean = synchronized(lock) { log.reportResult(id, text) }

    /** A remark the screen makes about itself. */
    fun system (text: String, id: String = "", resend: Boolean = false) {
        synchronized(lock) { log.system(text, id, resend) }
        onChanged?.invoke()
    }

    /** The one line about a failed attempt: replaced, never appended. */
    fun outcome (text: String, resend: Boolean) {
        synchronized(lock) { log.outcome(text, resend) }
        onChanged?.invoke()
    }

    /** The outcome of a repeat, in the same line that offered it. */
    fun resendText (text: String): Boolean = synchronized(lock) { log.resendText(text) }

    /** The take is accepted: no more offers to resend. */
    fun dropResend (): Boolean = synchronized(lock) { log.dropResend() }

    /** Forget everything: used by tests and by a node that is re-paired. */
    fun clear () {
        synchronized(lock) { log.clear() }
        onChanged?.invoke()
    }
}
