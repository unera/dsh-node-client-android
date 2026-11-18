package ru.uvw.dshnc.logic

/**
 * One request to open something on the phone, expressed the way Android itself expresses it.
 *
 * The point of the abstraction is that nothing here knows any application by name: "open the
 * navigator to an address" is `VIEW geo:0,0?q=…`, "call" is `VIEW tel:…`, "show a page" is
 * `VIEW https://…`. Which app answers is the system's business, and `query()` is how the person
 * (and the model) finds out what would answer.
 *
 * Kept pure — no Intent, no Context — so that the rules are testable: what counts as a valid
 * request, which actions are allowed at all, and which extras survive the generalisation.
 */
data class IntentRequest (
    val action: String,
    val data: String? = null,
    val type: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val extras: Map<String, Any> = emptyMap(),
    /** How the intent travels: an activity to the screen, a broadcast, or a service. */
    val kind: Kind = Kind.ACTIVITY,
) {
    /** The three ways an app is woken. Each has its own rules about who must be named. */
    enum class Kind { ACTIVITY, BROADCAST, SERVICE }

    companion object {
        /** The actions a remote request may name. Anything else is somebody's own business. */
        val ACTIONS = setOf(
            "android.intent.action.VIEW",
            "android.intent.action.SEND",
            "android.intent.action.SENDTO",
            "android.intent.action.DIAL",
            "android.intent.action.CALL",
            "android.intent.action.MAIN",
        )

        /**
         * URI schemes allowed in `data` without naming an app.
         *
         * A scheme nobody listed is still allowed when the caller pins a package: every app with
         * a deep link has a scheme of its own (`yandexnavi:`, `tg:`, `spotify:`), and refusing
         * them all would mean the feature works only for the four universal ones. Pinning the
         * target is what keeps the generalisation honest — the caller says who should answer.
         */
        val SCHEMES = setOf("geo", "tel", "https", "http", "mailto", "sms", "market", "magnet")

        /** The action a bare URL or a `verb` suffix means when nothing is said. */
        const val VIEW = "android.intent.action.VIEW"

        /**
         * Read one request out of the arguments of a method call.
         *
         * @param args - `{action?, data?, type?, package?, class?, extras?}`; `action` defaults to
         *   `VIEW` when `data` is given, because "open this" is what a caller almost always means.
         * @returns the request, ready to be turned into an Intent.
         * @throws IllegalArgumentException with a sentence a person can act on.
         */
        fun parse (args: Map<String, Any?>, kind: Kind = Kind.ACTIVITY): IntentRequest {
            val rawAction = (args["action"] as? String)?.trim().orEmpty()
            val data = (args["data"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val action = (if (rawAction.isEmpty()) (if (data != null) VIEW else "") else rawAction)
                .let { if (it.isEmpty()) throw IllegalArgumentException("нужен action или data") else it }
            // A broadcast or a service has no screen to fall back on: the system cannot resolve
            // "whoever listens", and an implicit command is a message to the whole system.
            if (kind != Kind.ACTIVITY && ((args["package"] as? String)?.trim().isNullOrEmpty())) {
                throw IllegalArgumentException("для $action нужно назвать package: команду шлём конкретному приложению")
            }
            // A command names its own action — `com.wireguard.android.action.SET_TUNNEL_UP` and the
            // like — so the standard list cannot govern it. What governs it is the package: a
            // command goes to a named app or nowhere.
            if (kind == Kind.ACTIVITY && !ACTIONS.contains(action)) {
                throw IllegalArgumentException("действие не разрешено: $action")
            }
            if (action == VIEW || action.endsWith(".SENDTO") || action.endsWith(".DIAL")) {
                if (data == null) throw IllegalArgumentException("для $action нужен data: адрес или ссылка")
            }
            if (data != null) {
                val scheme = data.substringBefore(':', "").lowercase()
                if (scheme.isEmpty()) throw IllegalArgumentException("в data нет схемы: $data")
                val pinned = (args["package"] as? String)?.trim()?.isNotEmpty() == true
                if (!SCHEMES.contains(scheme) && !pinned) {
                    throw IllegalArgumentException("схема не разрешена без указания приложения: $scheme")
                }
            }
            val extras = LinkedHashMap<String, Any>()
            (args["extras"] as? Map<*, *>)?.forEach { (key, value) ->
                val name = (key as? String)?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                // Only the types that travel through a Bundle without a class on the other side:
                // a Parcelable cannot be described in JSON, and pretending otherwise would fail
                // at the moment of the launch, not at the moment of the request.
                when (value) {
                    is String, is Boolean, is Int, is Long, is Double -> extras[name] = value
                    is Number -> extras[name] = value.toDouble()
                    // A nested object becomes a Bundle — some plugin protocols (Tasker/Locale) carry
                    // their settings as one Bundle extra and nothing else.
                    is Map<*, *> -> extras[name] = bundleOf(value, name)
                    else -> throw IllegalArgumentException("дополнительный параметр $name должен быть строкой, числом или объектом")
                }
            }
            return IntentRequest(
                kind = kind,
                action = action,
                data = data,
                type = (args["type"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                packageName = (args["package"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                className = (args["class"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                extras = extras,
            )
        }

        /**
         * A nested map as a bundle, checked key by key.
         *
         * @param value - the parsed JSON object.
         * @param path - its name, for the sentence a refusal has to say.
         * @returns a map the API layer turns into a Bundle.
         */
        private fun bundleOf (value: Map<*, *>, path: String): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            for ((key, nested) in value) {
                val name = (key as? String)?.trim().orEmpty()
                if (name.isEmpty()) continue
                when (nested) {
                    is String, is Boolean, is Int, is Long, is Double -> out[name] = nested
                    is Number -> out[name] = nested.toDouble()
                    is Map<*, *> -> out[name] = bundleOf(nested, "$path.$name")
                    else -> throw IllegalArgumentException("поле $path.$name: только строка, число или объект")
                }
            }
            return out
        }

        /** A request that opens a URL — the only shape most calls need. */
        fun open (url: String): IntentRequest = parse(mapOf("data" to url))
    }
}
