package ru.uvw.dshnc.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.VoiceSession
import ru.uvw.dshnc.logic.VoiceUpload
import ru.uvw.dshnc.logic.VoiceWant
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sending a take to the host.
 *
 * Two ways out existed and only one of them survives in daily use: the take used to wait for the
 * host to ask for it, which cost round trips and made a person's press depend on a command's
 * timing. Now one request carries the bytes and the host decides where they land.
 *
 * Every call runs on its own thread and never throws: a network failure must not cost the person
 * their recording, and the caller decides what to do with the file afterwards.
 */
class VoiceEvents (private val base: String, private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Send one recording as a device event.
     *
     * @param file the take on this phone.
     * @param seconds how long it lasts, as measured while recording.
     * @param id the press identity: a retry of the same press keeps it.
     * @param times the client-side marks that let the host measure the whole path, not its half.
     * @param synth who voices the answer: the phone's engine or the harness.
     * @returns acceptance with the host's own facts, or the reason it did not land.
     */
    fun sendVoice (
        file: File,
        seconds: Double,
        id: String,
        times: VoiceUpload.Times = VoiceUpload.Times(),
        synth: String = VoiceUpload.SYNTH_DEVICE,
    ): VoiceUpload.Result {
        if (!file.isFile) return VoiceUpload.Result.Failed("записи ${file.name} нет на телефоне")
        val request = try {
            val body = file.asRequestBody("audio/wav".toMediaType())
            Request.Builder()
                .url(VoiceUpload.url(base, id, synth))
                .header("authorization", "Bearer $token")
                .apply { VoiceUpload.headers(seconds, times).forEach { (name, value) -> header(name, value) } }
                .post(body)
                .build()
        } catch (error: Throwable) {
            return VoiceUpload.Result.Failed(error.message ?: error.javaClass.simpleName)
        }
        return try {
            http.newCall(request).execute().use { response ->
                VoiceUpload.interpret(response.code, response.body?.string().orEmpty())
            }
        } catch (error: Throwable) {
            // an unreachable host is not a refusal of the recording: the take stays on the phone
            VoiceUpload.Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Ask the host to start a new conversation.
     *
     * The refusal is passed on in the server's own words: a generic "no answer" once hid a
     * `bad_type` that said exactly what was wrong with the request.
     *
     * @returns acceptance, or the reason it did not land; never throws.
     */
    fun newSession (): VoiceSession.Result = try {
        NodeApi(base).sendEvent(token, VoiceSession.body())
        VoiceSession.Result.Accepted
    } catch (error: ApiException) {
        val words = listOfNotNull(
            error.message.takeIf { it.isNotBlank() },
            error.code?.let { "($it)" },
        ).joinToString(" ")
        VoiceSession.Result.Failed(words.ifBlank { VoiceSession.interpret(error.status, null).let { result ->
            (result as? VoiceSession.Result.Failed)?.reason ?: VoiceSession.NO_ANSWER
        } })
    } catch (error: Throwable) {
        VoiceSession.Result.Failed(error.message ?: VoiceSession.NO_ANSWER)
    }

    /**
     * Tell the host that a person is about to speak.
     *
     * Kept for the older route (`voice.want` → `record`): the screen no longer sends it, but the
     * server still understands it, and the branch that pulls files by path stays alive.
     */
    fun announce (mode: VoiceMode): VoiceWant.Result = try {
        val answer = NodeApi(base).sendEvent(token, VoiceWant.body(mode))
        VoiceWant.interpret(202, answer.toString())
    } catch (error: ApiException) {
        val first = VoiceWant.interpret(error.status, null)
        if (first is VoiceWant.Result.Failed && error.message.isNotBlank()) {
            VoiceWant.Result.Failed(error.message + (error.code?.let { " ($it)" } ?: ""))
        } else {
            first
        }
    } catch (error: Throwable) {
        VoiceWant.Result.Failed(error.message ?: error.javaClass.simpleName)
    }
}
