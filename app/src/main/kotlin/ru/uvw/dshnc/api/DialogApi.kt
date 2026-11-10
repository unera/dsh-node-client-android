package ru.uvw.dshnc.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.logic.AskCheck
import ru.uvw.dshnc.logic.AskLogic
import ru.uvw.dshnc.logic.RawButton

/**
 * Asking the person a question.
 *
 * The device shows a title, a scrollable text and up to three buttons; the answer is the
 * `action` of the pressed button, or `cancel` when the question was dismissed. Nothing of
 * the wording lives here: the text comes from the caller, so the phone never speaks for
 * the person it asks.
 *
 * The answer does not come from `handle`: a human needs time, and the loop that picks up
 * commands must stay free — otherwise the server's own `#INTERNAL.CANCEL` never arrives and
 * the question cannot be cancelled at all. So `handle` opens the question and returns
 * `pending`, and the answer is sent by the thread that waits for it.
 */
class DialogApi (private val context: Context) {
    fun schema (): JSONObject = JSONObject()
        .put("name", "dialog")
        .put("version", NodeApi.VERSION)
        .put("methods", JSONArray().put(JSONObject()
            .put("method", "ask")
            .put("description", "Ask the person a question with up to three buttons; returns the chosen action, or `cancel` when the command was cancelled or its timeout expired")
            .put("args", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string"))
                    .put("type", JSONObject()
                        .put("type", "string")
                        .put("description", "only \"text\" for now"))
                    .put("buttons", JSONObject()
                        .put("type", "array")
                        .put("description", "1..3 items: {action, title, icon, style}; action and title are required"))
                    .put("context", JSONObject()
                        .put("type", "object")
                        .put("description", "echoed back untouched"))
                    .put("style", JSONObject()
                        .put("type", "string")
                        .put("description", "normal|warning|danger"))
                    .put("timeout", JSONObject()
                        .put("type", "number")
                        .put("description", "seconds to wait for the person; the command's own timeout is used when absent")))
                .put("required", JSONArray().put("content").put("buttons")))
            .put("returns", "object")))

    /**
     * Open the question.
     *
     * The outcome is either a refusal (`bad_args`) or the marker `pending`: the answer will be
     * sent by [wait], whenever the person gives it.
     */
    fun handle (method: String, args: JSONObject, seq: Long, commandSeconds: Double, submit: (JSONObject) -> Unit): JSONObject {
        if (method != "ask") return err("unknown_method", "no such method: $method")
        val check = AskLogic.check(
            type = text(args, "type"),
            content = text(args, "content"),
            title = text(args, "title"),
            buttons = buttons(args.optJSONArray("buttons")),
            style = text(args, "style"),
        )
        if (check is AskCheck.Refused) return err(check.code, check.message)
        val request = (check as AskCheck.Ready).request
        val echo = args.optJSONObject("context") ?: JSONObject()
        val timeout = AskLogic.timeoutMs(
            argsMs = number(args, "timeoutMs"),
            argsSeconds = number(args, "timeout"),
            commandSeconds = commandSeconds,
        )
        // whether a second question on top of the first makes sense is the caller's
        // business: the phone shows what it is given
        val open = DialogHub.begin(context, seq, request, echo)
        Thread({ wait(open, timeout, submit) }, "dshnc-dialog").start()
        return JSONObject().put("status", PENDING)
    }

    /**
     * Wait for the answer and send it.
     *
     * Runs on its own thread: the command loop is free to take the next batch meanwhile, and
     * that is what makes `#INTERNAL.CANCEL` reachable while a person is thinking.
     */
    private fun wait (open: AskPending, timeout: Long, submit: (JSONObject) -> Unit) {
        // whatever happens, the question is answered: an unanswered command would keep the
        // server waiting for nothing
        val chosen = runCatching { open.await(timeout) }.getOrNull()
        val action = AskLogic.resolveAction(open.request.buttons, chosen)
        runCatching { DialogHub.shutdown(context, open) }
        val answer = JSONObject()
            .put("status", "ok")
            .put("payload", JSONObject().put("action", action).put("context", open.context))
        runCatching { submit(answer) }
            .onFailure { error ->
                // the server may have given up on the command while the person was thinking:
                // that is its right, and the phone has nothing to repair
                runCatching {
                    ru.uvw.dshnc.store.TokenStore(context)
                        .addEvent("dialog", "result: ${error.message ?: error.javaClass.simpleName}")
                }
            }
    }

    /**
     * Service commands.
     *
     * `#INTERNAL.CANCEL` with the number of the open question dismisses it and lets the
     * waiting thread send `cancel` through the ordinary result path. Null means the command
     * is not this API's business — a running shell command, for example — and the shell
     * registry answers it.
     */
    fun control (method: String, args: JSONObject): JSONObject? = when (method) {
        "#INTERNAL.CANCEL" -> {
            val seq = args.optLong("seq", -1L)
            if (DialogHub.dismiss(seq)) {
                JSONObject().put("status", "ok").put("payload", JSONObject().put("cancelled", true))
            } else {
                null
            }
        }
        else -> null
    }

    /** The buttons as they arrived: the check decides which of them are usable. */
    private fun buttons (array: JSONArray?): List<RawButton> {
        if (array == null) return emptyList()
        val list = ArrayList<RawButton>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: JSONObject()
            list.add(RawButton(
                action = text(item, "action"),
                title = text(item, "title"),
                icon = text(item, "icon"),
                style = text(item, "style"),
            ))
        }
        return list
    }

    /** A string argument, or null when it is absent or not a string. */
    private fun text (args: JSONObject, name: String): String? =
        if (args.has(name) && !args.isNull(name)) args.opt(name) as? String else null

    /** A numeric argument, or null when it is absent or not a number. */
    private fun number (args: JSONObject, name: String): Double? {
        if (!args.has(name) || args.isNull(name)) return null
        val value = args.opt(name)
        return if (value is Number) value.toDouble() else null
    }

    private fun err (code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)

    companion object {
        /** "The answer is not ready: it will be sent later" — the loop must not report it. */
        const val PENDING = "pending"
    }
}
