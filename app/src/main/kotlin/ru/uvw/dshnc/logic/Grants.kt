package ru.uvw.dshnc.logic

/**
 * What the node is allowed to do for whoever holds its token.
 *
 * Not the same thing as an Android permission: the system decides whether the app *may* open
 * the camera, and this decides whether the node *will* answer such a request at all. Two
 * different persons are behind them — the owner of the phone and the owner of the node — so the
 * two live in two places and on two screens.
 *
 * Kept pure: the mapping from a method to the grant it needs is the whole rule, and a rule that
 * can be tested without a device is worth testing.
 */
object Grants {
    /** The switches a person sees on the "Разрешения" screen. */
    enum class Grant (val key: String, val title: String) {
        SHELL("shell", "Команды оболочки"),
        ALARMS("alarms", "Будильники"),
        INTENTS("intents", "Запуск приложений"),
        COMMANDS("commands", "Команды приложениям"),
        FILES("files", "Доступ к файлам"),
        DEBUG("debug", "Отладочные сведения"),
        VOICE("voice", "Голос"),
        CAMERA("camera", "Камера"),
        NOTIFY("notify", "Уведомления и вопросы"),
        ;

        companion object {
            fun of (key: String): Grant? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * The grant a method needs, or null when the method is always allowed.
     *
     * `device_status` and `device_info` are on the always side on purpose: knowing the battery
     * level is not an action on the phone, and a node that cannot say whether it is alive is not
     * a node.
     */
    fun needed (method: String): Grant? = when {
        // `pipeToDevice`/`pipeFromDevice` run a command through `sh -c` exactly like `exec`:
        // a switch that stops one and lets the other through is not a switch.
        method == "exec" || method == "pipeToDevice" || method == "pipeFromDevice" -> Grant.SHELL
        method.startsWith("alarm_") -> Grant.ALARMS
        // Opening a screen and sending a command to an app are different promises: the first is
        // visible, the second happens silently, so it gets its own switch.
        method == "intent_broadcast" || method == "intent_service" -> Grant.COMMANDS
        method.startsWith("intent_") -> Grant.INTENTS
        method == "camera_snap" || method == "photos_latest" -> Grant.CAMERA
        method == "read" || method == "write" || method == "edit" || method == "mkdir" ||
            method == "copyToDevice" || method == "copyFromDevice" || method == "list" ||
            method == "stat" || method == "sha256" || method == "roots" -> Grant.FILES
        method == "debug_info" -> Grant.DEBUG
        method == "apps" -> Grant.DEBUG
        method == "post" || method == "ask" -> Grant.NOTIFY
        method == "say" || method == "stop" || method == "chat" || method == "activity" ||
            method == "answer" || method == "play" || method == "record" || method == "caps" -> Grant.VOICE
        else -> null
    }

    /**
     * Whether this call may run.
     *
     * @param method - the method the host asked for.
     * @param allowed - the switches as the person left them.
     * @returns true when the call is allowed or needs no grant at all.
     */
    fun allows (method: String, allowed: (Grant) -> Boolean): Boolean {
        val grant = needed(method) ?: return true
        return allowed(grant)
    }

    /** The sentence a refused call answers with: the person has to know where the switch is. */
    fun refusal (grant: Grant): String =
        "выключено в настройках узла: «${grant.title}» — включите на экране «Разрешения» в приложении"
}
