package ru.uvw.dshnc.api

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.VoiceOutbox
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Speech out loud, by the engine the phone already has.
 *
 * The engine is the system one (`android.speech.tts.TextToSpeech`): whatever language packs the
 * person installed, whatever voice they chose in the system settings. Nothing is downloaded here
 * and no server is involved, so this works offline and costs nothing — the price is that the
 * quality is the phone's, not ours.
 *
 * Two facts shape the code. First, the engine is asynchronous: it is created, then it says
 * whether it is ready, then it speaks, then it calls back. Second, `say` waits for the speech to
 * end, and that can take a minute — so it does not run inside the command loop. Like `dialog`, it
 * answers `pending` at once and reports the real outcome from its own thread, which keeps
 * `#INTERNAL.CANCEL` reachable while the phone is talking.
 */
class VoiceApi (private val context: Context) {
    private enum class State { NEW, READY, FAILED }

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var state = State.NEW
    private var failure: String? = null
    /** Utterance id to the command it belongs to: the cancel path finds speech by number. */
    private val speaking = ConcurrentHashMap<String, Long>()
    private val utteranceCounter = AtomicInteger(0)

    /** Commands waiting for a take right now: a cancel finds them by number. */
    private val recording = ConcurrentHashMap.newKeySet<Long>()

    /**
     * How a finished take reaches the server.
     *
     * The transfer itself belongs to the command loop: `/v1/upload-file` is accepted only inside
     * a command the server issued, and that command's number is the ticket. The loop owns the
     * token and the transfer client, so it hands this lambda in before dispatching a batch.
     */
    @Volatile var uploader: ((seq: Long, file: java.io.File, hostPath: String, commandSeconds: Double) -> JSONObject)? = null

    fun schema (): JSONObject = JSONObject()
        .put("name", "voice")
        .put("version", NodeApi.VERSION)
        .put("methods", JSONArray()
            .put(method("caps", "What speech this device can produce: engine, languages, voices",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("say", "Speak the text out loud and wait until it finishes",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("text", JSONObject().put("type", "string"))
                        .put("language", JSONObject().put("type", "string"))
                        .put("flush", JSONObject().put("type", "boolean"))
                        .put("timeout", JSONObject().put("type", "number")))
                    .put("required", JSONArray().put("text"))))
            .put(method("stop", "Stop speaking right now",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method(
                "record",
                "Take one recording from the microphone, upload it to hostPath and report it",
                recordArgs(),
            )))


    /** The `record` arguments, written apart so that the schema stays readable. */
    private fun recordArgs (): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("hostPath", JSONObject()
                .put("type", "string")
                .put("description", "Where the WAV lands on the server"))
            .put("mode", JSONObject()
                .put("type", "string")
                .put("description", "ptt (hold to talk) or toggle (press to start, press to stop)"))
            .put("maxSeconds", JSONObject().put("type", "number"))
            .put("timeout", JSONObject().put("type", "number")))
        .put("required", JSONArray().put("hostPath"))

    private fun method (name: String, description: String, args: JSONObject): JSONObject = JSONObject()
        .put("method", name)
        .put("description", description)
        .put("args", args)
        .put("returns", "object")

    /**
     * Run one voice command.
     *
     * `caps` and `stop` answer immediately. `say` answers [PENDING] and submits its outcome later
     * through [submit] — the same seam the dialog uses.
     */
    fun handle (
        method: String,
        args: JSONObject,
        seq: Long,
        commandSeconds: Double,
        submit: (JSONObject) -> Unit,
    ): JSONObject = when (method) {
        "caps" -> caps()
        "say" -> say(args, seq, commandSeconds, submit)
        "record" -> record(args, seq, commandSeconds, submit)
        "stop" -> stop()
        else -> err("unknown_method", "no such method: $method")
    }

    /**
     * Service commands.
     *
     * A cancel carries the number of the command it cancels: if that command is speaking, the
     * engine is silenced here and the waiting thread reports how it ended. Null means the number
     * belongs to something else and this API stays out of it.
     */
    fun control (method: String, args: JSONObject): JSONObject? = when (method) {
        "#INTERNAL.CANCEL" -> {
            val seq = args.optLong("seq", -1L)
            when {
                speaking.containsValue(seq) -> {
                    runCatching { tts?.stop() }
                    JSONObject().put("status", "ok").put("payload", JSONObject().put("cancelled", true))
                }
                // a recording command is cancelled by waking its thread: it then reports the
                // timeout path, which the server reads as "nothing arrived"
                recording.contains(seq) -> {
                    VoiceOutbox.abandon()
                    JSONObject().put("status", "ok").put("payload", JSONObject().put("cancelled", true))
                }
                else -> null
            }
        }
        else -> null
    }

    /** What this device can say: the engine, its languages and its voices. */
    private fun caps (): JSONObject {
        val engine = engine() ?: return unavailable()
        val languages = JSONArray()
        runCatching { engine.availableLanguages?.map { it.toLanguageTag() }?.sorted() ?: emptyList() }
            .getOrNull()?.forEach { languages.put(it) }
        val voices = JSONArray()
        runCatching { engine.voices?.map { it.name }?.sorted() ?: emptyList() }
            .getOrNull()?.forEach { voices.put(it) }
        return ok(JSONObject()
            .put("tts", true)
            .put("engine", engineName(engine))
            .put("languages", languages)
            .put("voices", voices))
    }

    /**
     * Speak one text and report when the speech is over.
     *
     * `seconds` is measured by the clock, from the moment the engine accepted the request until it
     * called back: the engine does not tell us how long the audio is, and inventing a number from
     * the character count would be worse than measuring.
     */
    private fun say (
        args: JSONObject,
        seq: Long,
        commandSeconds: Double,
        submit: (JSONObject) -> Unit,
    ): JSONObject {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return err("bad_args", "нужен непустой text")
        val engine = engine() ?: return unavailable()
        val language = args.optString("language").ifBlank { DEFAULT_LANGUAGE }
        val locale = Locale.forLanguageTag(language)
        val support = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
            return err(
                "language_unavailable",
                "движок ${engineName(engine)} не умеет $language: " +
                    "поставьте языковой пакет в настройках синтеза речи",
            )
        }
        val flush = args.optBoolean("flush", true)
        val timeoutMs = timeoutMs(args.optDouble("timeout", 0.0), commandSeconds)
        val utteranceId = "dshnc-voice-${seq}-${utteranceCounter.incrementAndGet()}"
        val finished = CountDownLatch(1)
        val outcome = AtomicReference(OUTCOME_NONE)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart (id: String?) {}
            override fun onDone (id: String?) {
                outcome.compareAndSet(OUTCOME_NONE, OUTCOME_DONE)
                finished.countDown()
            }

            @Deprecated("the engine still calls it on older Android versions")
            override fun onError (id: String?) {
                outcome.compareAndSet(OUTCOME_NONE, OUTCOME_FAILED)
                finished.countDown()
            }

            override fun onError (id: String?, errorCode: Int) {
                outcome.compareAndSet(OUTCOME_NONE, OUTCOME_FAILED)
                finished.countDown()
            }

            override fun onStop (id: String?, interrupted: Boolean) {
                outcome.compareAndSet(OUTCOME_NONE, OUTCOME_STOPPED)
                finished.countDown()
            }
        })
        speaking[utteranceId] = seq
        val startedAt = System.currentTimeMillis()
        val queue = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val accepted = runCatching { engine.speak(text, queue, Bundle(), utteranceId) }
            .getOrDefault(TextToSpeech.ERROR)
        if (accepted != TextToSpeech.SUCCESS) {
            speaking.remove(utteranceId)
            return err("voice_failed", "движок отказался говорить (код $accepted)")
        }
        Thread({ waitForSpeech(engine, utteranceId, startedAt, timeoutMs, finished, outcome, submit) },
            "dshnc-voice").start()
        return JSONObject().put("status", PENDING)
    }

    /** Wait on its own thread, then report: the command loop must stay free meanwhile. */
    private fun waitForSpeech (
        engine: TextToSpeech,
        utteranceId: String,
        startedAt: Long,
        timeoutMs: Long,
        finished: CountDownLatch,
        outcome: AtomicReference<String>,
        submit: (JSONObject) -> Unit,
    ) {
        val completed = runCatching { finished.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        val answer = when {
            !completed -> {
                // the engine never called back: silence it, so the next request does not pile up
                runCatching { engine.stop() }
                err("timeout", "движок не закончил речь за ${timeoutMs / 1000} с")
            }
            outcome.get() == OUTCOME_DONE -> ok(JSONObject()
                .put("spoken", true)
                .put("seconds", seconds)
                .put("engine", engineName(engine)))
            outcome.get() == OUTCOME_STOPPED -> err("cancelled", "речь прервана")
            else -> err("voice_failed", "движок не смог произнести текст")
        }
        speaking.remove(utteranceId)
        runCatching { submit(answer) }
    }

    /** Silence the engine now. */
    private fun stop (): JSONObject {
        val engine = tts
        if (engine == null || state != State.READY) {
            return ok(JSONObject().put("stopped", true).put("spoken", false))
        }
        runCatching { engine.stop() }
        return ok(JSONObject().put("stopped", true).put("spoken", true))
    }

    /**
     * Take one recording from the microphone and send it to the server.
     *
     * The command answers [PENDING] at once and reports from its own thread, for the same reason
     * `say` does: a person may take a minute to speak, and the command loop must stay free.
     *
     * A take that was recorded while nothing was waiting is already parked in [VoiceOutbox], and
     * this command picks it up immediately — pressing the button before the host asks is not a
     * lost recording, it is the ordinary case in a car.
     */
    private fun record (
        args: JSONObject,
        seq: Long,
        commandSeconds: Double,
        submit: (JSONObject) -> Unit,
    ): JSONObject {
        val hostPath = args.optString("hostPath").trim()
        if (hostPath.isEmpty()) return err("bad_args", "нужен непустой hostPath: куда положить запись на сервере")
        val rawMode = args.optString("mode").trim()
        val mode = if (rawMode.isEmpty()) {
            VoiceMode.PTT
        } else {
            VoiceMode.parse(rawMode) ?: return err("bad_args", "mode бывает ptt или toggle, а не $rawMode")
        }
        val upload = uploader ?: return err(
            "voice_unavailable",
            "узел не подключился к серверу: запись некуда отправить",
        )
        voiceMaxSeconds = args.optDouble("maxSeconds", 0.0)
            .takeIf { it > 0 }
            ?.coerceIn(1.0, MAX_RECORD_SECONDS)
            ?: DEFAULT_RECORD_SECONDS
        VoiceOutbox.maxSeconds = voiceMaxSeconds
        val timeoutMs = timeoutMs(args.optDouble("timeout", 0.0), commandSeconds)
        recording.add(seq)
        Thread({
            VoiceOutbox.beginWait()
            try {
                val take = VoiceOutbox.await(timeoutMs)
                if (take == null) {
                    submit(err("voice_timeout", "запись не поступила за ${timeoutMs / 1000} с"))
                    return@Thread
                }
                val uploaded = upload(seq, take.file, hostPath, commandSeconds)
                if (uploaded.optString("status") != "ok") {
                    // the bytes are still good: park the take for the next command instead of
                    // asking the person to repeat themselves
                    VoiceOutbox.offer(take)
                    submit(uploaded)
                    return@Thread
                }
                val sent = uploaded.optJSONObject("payload")?.optLong("sent") ?: take.file.length()
                submit(ok(JSONObject()
                    .put("path", hostPath)
                    .put("bytes", sent)
                    .put("seconds", take.seconds)
                    .put("mode", take.mode.ifBlank { mode.wire })))
                runCatching { take.file.delete() }
            } finally {
                recording.remove(seq)
                VoiceOutbox.endWait()
            }
        }, "dshnc-voice-record").start()
        return JSONObject().put("status", PENDING)
    }

    /** How long one take may be, as the last `record` command asked: the screen reads it. */
    var voiceMaxSeconds: Double = DEFAULT_RECORD_SECONDS
        private set

    /** Release the engine: a service that is going away must not leave a speaker busy. */
    fun shutdown () {
        synchronized(lock) {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            state = State.NEW
            failure = null
            speaking.clear()
        }
    }

    /**
     * The engine, created on first use.
     *
     * Creation is asynchronous, so the first request waits for the listener with a short ceiling.
     * A device without a usable engine is not an exception: it is a refusal with a reason, and
     * every later request answers from that remembered reason instead of hanging again.
     */
    private fun engine (): TextToSpeech? {
        synchronized(lock) {
            when (state) {
                State.READY -> return tts
                State.FAILED -> return null
                State.NEW -> {}
            }
            val ready = CountDownLatch(1)
            val status = AtomicInteger(TextToSpeech.ERROR)
            val created = runCatching {
                TextToSpeech(context) { code ->
                    status.set(code)
                    ready.countDown()
                }
            }.getOrNull()
            if (created == null) {
                state = State.FAILED
                failure = "системный синтез речи на этом устройстве недоступен"
                return null
            }
            val started = runCatching { ready.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (started && status.get() == TextToSpeech.SUCCESS) {
                tts = created
                state = State.READY
                return created
            }
            runCatching { created.shutdown() }
            state = State.FAILED
            failure = "системный синтез речи не инициализировался: " +
                "нет движка или не установлен голосовой пакет"
            return null
        }
    }

    /** The engine's own name: the package of the default engine, as the system reports it. */
    private fun engineName (engine: TextToSpeech): String =
        runCatching { engine.defaultEngine ?: engine.engines?.firstOrNull()?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "system"

    private fun unavailable () = err("voice_unavailable", failure ?: "системный синтез речи недоступен")

    /** Seconds to wait for the speech: the argument, then the command's own pause, then the default. */
    private fun timeoutMs (argSeconds: Double, commandSeconds: Double): Long {
        val seconds = when {
            argSeconds > 0 -> argSeconds
            commandSeconds > 0 -> commandSeconds
            else -> DEFAULT_TIMEOUT_SECONDS
        }
        return (seconds * 1000).toLong().coerceAtLeast(1000L)
    }

    private fun ok (payload: JSONObject) = JSONObject().put("status", "ok").put("payload", payload)

    private fun err (code: String, message: String) = JSONObject()
        .put("status", "error").put("code", code).put("message", message)

    companion object {
        /** The marker the command loop knows: this command answers later, from its own thread. */
        const val PENDING = "pending"

        /** Russian by default: the phone is the one that knows better, but this is who asked. */
        const val DEFAULT_LANGUAGE = "ru-RU"

        /** Long enough for a paragraph, short enough not to hold a command slot for minutes. */
        const val DEFAULT_TIMEOUT_SECONDS = 60.0

        /** One take may be long enough for a sentence in a car, and short enough to send. */
        const val DEFAULT_RECORD_SECONDS = 60.0

        /** A ceiling nobody wants to hit by accident: five minutes of speech in one command. */
        const val MAX_RECORD_SECONDS = 300.0

        /** How long the engine may take to say it is ready. */
        private const val INIT_TIMEOUT_MS = 5_000L

        private const val OUTCOME_NONE = "none"
        private const val OUTCOME_DONE = "done"
        private const val OUTCOME_FAILED = "failed"
        private const val OUTCOME_STOPPED = "stopped"
    }
}
