package ru.uvw.dshnc.api

import android.content.Context
import android.os.Bundle
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import ru.uvw.dshnc.logic.VoiceChat
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceChatLog
import ru.uvw.dshnc.store.TokenStore
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

    /** The take or replique being played: one at a time, the newest wins. */
    private val playerLock = Any()
    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    /**
     * Where a file for `play` comes from.
     *
     * The command's own number is the ticket: `/v1/download-file?seq=…` accepts a node's request
     * only inside a command the server issued, so the transfer client is lent in by the loop that
     * owns the token, exactly as the old upload branch did.
     */
    @Volatile var downloader: ((seq: Long, localPath: String, commandSeconds: Double) -> JSONObject)? = null

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
                "chat",
                "Show one line in the voice conversation: the transcript of the take, or the answer",
                chatArgs(),
            ))
            .put(method(
                "play",
                "Download a file from the host by this command's ticket and play it out loud",
                playArgs(),
            ))
            .put(method(
                "record",
                "Take one recording from the microphone and report where the file landed on this device",
                recordArgs(),
            )))


    /** The `chat` arguments: who speaks, what, and which exchange it belongs to. */
    private fun chatArgs (): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("role", JSONObject()
                .put("type", "string")
                .put("description", "user — the transcript of the take; assistant — the answer"))
            .put("text", JSONObject()
                .put("type", "string")
                .put("description", "The line itself; longer than 4000 characters is clipped"))
            .put("id", JSONObject()
                .put("type", "string")
                .put("description", "The exchange id from the upload: it pairs a take with its transcript")))
        .put("required", JSONArray().put("role").put("text"))

    /** The `play` arguments: the file is named by its path on the host. */
    private fun playArgs (): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("path", JSONObject()
                .put("type", "string")
                .put("description", "The file on the server: a voice note or any audio the host keeps"))
            .put("seconds", JSONObject()
                .put("type", "number")
                .put("description", "How long it lasts, when the host already knows")))
        .put("required", JSONArray().put("path"))

    /**
     * The `record` arguments.
     *
     * No destination here: the command writes a file on the phone and returns its path. The host
     * then pulls it with `files.copyFromDevice`, which is the only transfer route the server opens
     * for a node — `/v1/upload-file` accepts `copyFromDevice` and `pipeFromDevice` and nothing
     * else, so a `record` that tried to upload was refused by the server's own ticket check.
     */
    private fun recordArgs (): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("mode", JSONObject()
                .put("type", "string")
                .put("description", "ptt (hold to talk) or toggle (press to start, press to stop)"))
            .put("maxSeconds", JSONObject().put("type", "number"))
            .put("timeout", JSONObject().put("type", "number")))

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
        "chat" -> chat(args)
        "play" -> play(args, seq, commandSeconds)
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

    /** Silence everything that is speaking: the engine and a played replique alike. */
    private fun stop (): JSONObject {
        val engine = tts
        val speaking = engine != null && state == State.READY
        if (speaking) runCatching { engine?.stop() }
        val playing = synchronized(playerLock) { player != null }
        if (playing) stopPlayback()
        return ok(JSONObject().put("stopped", true).put("spoken", speaking).put("playing", playing))
    }

    /**
     * Put one line into the voice conversation.
     *
     * The screen may be closed: the line still lands in the shared log and is drawn when the screen
     * opens. Nothing here waits for the sound — the answer is usually already on its way to the
     * player as a separate `play` command.
     */
    private fun chat (args: JSONObject): JSONObject {
        val events = TokenStore(context)
        val rawRole = args.optString("role")
        val role = VoiceChat.Role.parse(rawRole)
        if (role == null) {
            events.addEvent("chat-отказ", "role=«$rawRole»")
            return err("bad_args", "role бывает user или assistant, а не «$rawRole»")
        }
        val raw = args.optString("text")
        if (raw.isBlank()) {
            events.addEvent("chat-отказ", "$rawRole: пустой текст")
            return err("bad_args", "нужен непустой text")
        }
        val id = args.optString("id").trim()
        val text = VoiceChat.clip(raw)
        val change = VoiceChatBus.put(id, role, text)
        if (change == VoiceChatLog.Change.IGNORED) {
            // the line is already there: saying "shown" again would be a lie about new content
            events.addEvent("chat-повтор", "$rawRole ${text.length} симв, id=$id")
            return err("duplicate", "такая реплика уже показана")
        }
        // temporary diagnostics: the host reads them through session.debug_info when the screen
        // looks empty and the only question is whether the line ever arrived
        events.addEvent(
            "chat " + if (role == VoiceChat.Role.USER) "user" else "assistant",
            "${text.length} симв, id=$id, ${if (change == VoiceChatLog.Change.REPLACED) "замена" else "новая"}",
        )
        return ok(JSONObject().put("shown", true))
    }

    /**
     * Pull one file from the host and start playing it.
     *
     * The answer comes as soon as the sound starts, not when it ends: the host waits for a receipt
     * to know the phone is speaking, and a two-minute replique would hold the command open. The
     * duration the player reports is what the host uses to plan the next step.
     *
     * A second `play` supersedes the first, and a failure to fetch or decode is a plain refusal —
     * the node keeps working either way.
     */
    private fun play (args: JSONObject, seq: Long, commandSeconds: Double): JSONObject {
        val hostPath = args.optString("path").trim()
        if (hostPath.isEmpty()) return err("bad_args", "нужен непустой path: что играть")
        val download = downloader ?: return err(
            "voice_unavailable",
            "узел не подключился к серверу: файл неоткуда взять",
        )
        val target = File(context.cacheDir, "play-$seq.wav")
        val fetched = try {
            download(seq, target.absolutePath, commandSeconds)
        } catch (error: Throwable) {
            runCatching { target.delete() }
            return err("voice_failed", "файл не скачался: ${error.message ?: error.javaClass.simpleName}")
        }
        if (fetched.optString("status") != "ok") {
            runCatching { target.delete() }
            return fetched
        }
        stopPlayback()
        val player = MediaPlayer()
        return try {
            player.setDataSource(target.absolutePath)
            player.prepare()
            val seconds = player.duration / 1000.0
            player.setOnCompletionListener { stopPlayback() }
            player.start()
            synchronized(playerLock) {
                this.player = player
                playingFile = target
            }
            ok(JSONObject()
                .put("playing", true)
                .put("seconds", seconds)
                .put("path", hostPath))
        } catch (error: Throwable) {
            runCatching { player.release() }
            runCatching { target.delete() }
            err("voice_failed", "не удалось воспроизвести: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Stop whatever is playing and drop its file: a played replique is not kept. */
    private fun stopPlayback () {
        synchronized(playerLock) {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            playingFile?.let { file -> runCatching { file.delete() } }
            playingFile = null
        }
    }

    /**
     * Take one recording and report where it landed on the phone.
     *
     * The command answers [PENDING] and reports from its own thread: a person may take a minute to
     * speak, and the command loop must stay free meanwhile. Nothing is uploaded here — the file
     * stays on the phone and the host pulls it with `files.copyFromDevice`, the only route the
     * server opens for a node's own files.
     *
     * A take recorded before this command arrived is parked in [VoiceOutbox] and is reported at
     * once: pressing the button before the host asks is the ordinary case in a car.
     */
    private fun record (
        args: JSONObject,
        seq: Long,
        commandSeconds: Double,
        submit: (JSONObject) -> Unit,
    ): JSONObject {
        val rawMode = args.optString("mode").trim()
        val mode = if (rawMode.isEmpty()) {
            VoiceMode.PTT
        } else {
            VoiceMode.parse(rawMode) ?: return err("bad_args", "mode бывает ptt или toggle, а не $rawMode")
        }
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
                submit(ok(JSONObject()
                    .put("path", take.file.absolutePath)
                    .put("bytes", take.file.length())
                    .put("seconds", take.seconds)
                    .put("mode", take.mode.ifBlank { mode.wire })))
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
        stopPlayback()
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
        /**
         * The special rules this API binds (`announce.applyRules`): none, on purpose.
         *
         * `flushAround` was bound to `record` and `say` for a release, and it made the phone lag
         * by a minute: the barrier the server puts before such a command runs only on an empty
         * pool, and a recording lasts up to forty-five seconds of human time. Everything queued
         * behind it waited — including the next takes — while no session looked busy anywhere.
         *
         * A long, person-paced command must therefore not ask for barriers. The duration of a
         * take is measured on the device and travels in the answer's own `seconds`.
         */
        fun rules (): JSONArray = JSONArray()

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
