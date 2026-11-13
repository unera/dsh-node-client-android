package ru.uvw.dshnc.api

import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.VoiceWant

/**
 * Telling the host that a person is about to speak.
 *
 * One HTTP request on its own thread, because the press happens on the main thread and a network
 * wait there would freeze the button that was just touched. The result never decides whether the
 * recording starts — it only decides what the conversation list says afterwards.
 */
class VoiceEvents (private val base: String, private val token: String) {

    /**
     * Send the intent and read the answer.
     *
     * @returns acceptance, or the reason it did not land; never throws.
     */
    fun announce (mode: VoiceMode): VoiceWant.Result = try {
        val answer = NodeApi(base).sendEvent(token, VoiceWant.body(mode))
        VoiceWant.interpret(202, answer.toString())
    } catch (error: ApiException) {
        // the refusal already carries the server's own words and its status
        VoiceWant.interpret(error.status, null).let { first ->
            if (first is VoiceWant.Result.Failed && error.message.isNotBlank()) {
                VoiceWant.Result.Failed(error.message + (error.code?.let { " ($it)" } ?: ""))
            } else {
                first
            }
        }
    } catch (error: Throwable) {
        VoiceWant.Result.Failed(error.message ?: error.javaClass.simpleName)
    }
}
