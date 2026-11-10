package ru.uvw.dshnc.logic

/**
 * The `dialog.ask` contract, apart from Android.
 *
 * Everything that decides *what* the dialog is lives here: the argument check, the style
 * mapping, the action a press resolves to and the pause a person is given. The screen and
 * the notification are only ways to show the result of these functions, which is why the
 * whole file can be tested without an Android runtime.
 */
enum class DialogStyle (val wire: String) {
    NORMAL("normal"),
    WARNING("warning"),
    DANGER("danger"),
}

/** A button that passed the check: the action is what the server gets back. */
data class AskButton (
    val action: String,
    val title: String,
    val icon: String,
    val style: DialogStyle,
)

/** A button exactly as it arrived: every field may be missing, empty or of the wrong type. */
data class RawButton (
    val action: String? = null,
    val title: String? = null,
    val icon: String? = null,
    val style: String? = null,
)

/** A checked request: what to show, and nothing that still needs deciding. */
data class AskRequest (
    val title: String,
    val content: String,
    val buttons: List<AskButton>,
    val style: DialogStyle,
)

/** The result of the check: either a request to show or a refusal with a machine code. */
sealed class AskCheck {
    data class Ready (val request: AskRequest) : AskCheck()
    data class Refused (val code: String, val message: String) : AskCheck()
}

object AskLogic {
    /** What a dismissed dialog answers, and what a press resolves to when there is no press. */
    const val CANCEL_ACTION = "cancel"

    const val MAX_BUTTONS = 3

    /**
     * The look named by the caller, or `normal`.
     *
     * An unknown style is cosmetics and never a reason to refuse: refusing would leave the
     * model guessing which spelling this build likes.
     */
    fun style (value: String?): DialogStyle = when (value?.trim()?.lowercase()) {
        DialogStyle.WARNING.wire -> DialogStyle.WARNING
        DialogStyle.DANGER.wire -> DialogStyle.DANGER
        else -> DialogStyle.NORMAL
    }

    /**
     * The arguments check.
     *
     * `type` is the only field that is refused for being unknown: an absent one means `text`,
     * because that is the only type this build can show and the caller has nothing to choose.
     */
    fun check (
        type: String?,
        content: String?,
        title: String?,
        buttons: List<RawButton>,
        style: String?,
    ): AskCheck {
        val kind = type?.trim()?.lowercase().orEmpty()
        if (kind.isNotEmpty() && kind != "text") {
            return AskCheck.Refused("bad_args", "unsupported dialog type: $kind, only \"text\" is available")
        }
        val text = content.orEmpty()
        if (text.isBlank()) return AskCheck.Refused("bad_args", "content is required")
        if (buttons.isEmpty()) return AskCheck.Refused("bad_args", "at least one button is required")
        if (buttons.size > MAX_BUTTONS) {
            return AskCheck.Refused("bad_args", "at most $MAX_BUTTONS buttons are allowed, got ${buttons.size}")
        }
        val built = ArrayList<AskButton>(buttons.size)
        val seen = HashSet<String>()
        for (raw in buttons) {
            val action = raw.action?.trim().orEmpty()
            if (action.isEmpty()) return AskCheck.Refused("bad_args", "every button needs an action")
            val label = raw.title?.trim().orEmpty()
            if (label.isEmpty()) return AskCheck.Refused("bad_args", "every button needs a title (action: $action)")
            if (!seen.add(action)) return AskCheck.Refused("bad_args", "duplicate button action: $action")
            built.add(AskButton(action, label, raw.icon?.trim().orEmpty(), style(raw.style)))
        }
        return AskCheck.Ready(AskRequest(title?.trim().orEmpty(), text, built, style(style)))
    }

    /**
     * What the answer says.
     *
     * A press is an index into the checked buttons; anything else — a dismissal, a timeout,
     * an index that does not exist — is `cancel`. Out-of-range cannot normally happen, but
     * the answer must be a stated one either way.
     */
    fun resolveAction (buttons: List<AskButton>, index: Int?): String =
        if (index == null || index < 0 || index >= buttons.size) CANCEL_ACTION else buttons[index].action

    /**
     * How long the question waits.
     *
     * The question has no pause of its own: it lives exactly as long as the command it
     * belongs to, like every other method. The order is the common one — `timeoutMs`, then
     * `timeout` inside the arguments, then the pause the server set for the command and, if
     * nobody named anything at all, the shared default. When that runs out the question is
     * dismissed and the answer is `cancel`.
     */
    fun timeoutMs (argsMs: Double?, argsSeconds: Double?, commandSeconds: Double?): Long =
        Timeouts.resolveMs(argsMs, argsSeconds, commandSeconds)?.toLong() ?: Timeouts.DEFAULT_MS.toLong()
}
