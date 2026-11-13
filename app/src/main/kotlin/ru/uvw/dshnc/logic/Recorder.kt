package ru.uvw.dshnc.logic

import java.io.ByteArrayOutputStream
import java.io.File
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Recording into a WAV the host can recognize.
 *
 * `AudioRecord` is used rather than `MediaRecorder` on purpose: the latter writes compressed
 * containers (AAC/AMR/Opus) and the host wants 16 kHz mono PCM. Raw samples are collected in
 * memory — at this format a two-minute note is under four megabytes — and the container is
 * assembled on stop, so a cancelled recording leaves no half file behind.
 *
 * Callbacks are invoked on the recording thread; the screen is responsible for hopping to the
 * main thread before touching views.
 */
class Recorder (private val cacheDir: File) {
    /** What a finished recording looks like to its callers. */
    data class Result(val file: File, val seconds: Double, val bytes: Long)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val pcm = ByteArrayOutputStream()

    /** Live input level, 0..1 — what the screen draws while the person speaks. */
    @Volatile var level: Float = 0f
        private set

    /** Level changes, on the recording thread. */
    @Volatile var onLevel: ((Float) -> Unit)? = null

    /** Stop by itself at this many seconds: a stuck button must not fill the phone. */
    @Volatile var maxSeconds: Double = 120.0

    private var startedAt = 0L

    val isRecording: Boolean get() = running.get()

    /**
     * Open the microphone and start collecting samples.
     *
     * @returns null when recording started, or the reason it could not.
     */
    @Synchronized
    fun start (): String? {
        if (running.get()) return "запись уже идёт"
        val min = AudioRecord.getMinBufferSize(
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return "микрофон не поддерживает 16 кГц моно PCM16"
        val buffer = min.coerceAtLeast(WavFile.SAMPLE_RATE / 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavFile.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer,
            )
        } catch (error: Throwable) {
            return "микрофон недоступен: ${error.message ?: error.javaClass.simpleName}"
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return "микрофон занят или недоступен"
        }
        pcm.reset()
        record = recorder
        level = 0f
        startedAt = System.currentTimeMillis()
        running.set(true)
        try {
            recorder.startRecording()
        } catch (error: Throwable) {
            running.set(false)
            runCatching { recorder.release() }
            record = null
            return "не удалось начать запись: ${error.message ?: error.javaClass.simpleName}"
        }
        val loop = Thread({ read(recorder) }, "dshnc-voice-record").apply { isDaemon = true }
        thread = loop
        loop.start()
        return null
    }

    /** Read samples until stopped, reporting the level on the way. */
    private fun read (recorder: AudioRecord) {
        val chunk = ByteArray(WavFile.SAMPLE_RATE / 10 * 2) // a tenth of a second
        while (running.get()) {
            val read = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (error: Throwable) {
                -1
            }
            if (read <= 0) continue
            synchronized(pcm) { pcm.write(chunk, 0, read) }
            level = rms(chunk, read)
            onLevel?.invoke(level)
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
            if (elapsed >= maxSeconds) {
                // the ceiling is the recorder's own business: the screen may be asleep
                running.set(false)
            }
        }
    }

    /** Peak-normalized RMS of one chunk: a quiet room lands near zero, speech above a third. */
    private fun rms (bytes: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)).toShort()
            val value = sample / 32768.0
            sum += value * value
            count += 1
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        // speech sits around 0.05–0.3 of full scale; ten times that fills the meter
        return (rms * 10.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Stop and write the file.
     *
     * @returns the finished recording, or null when there was nothing to write.
     */
    @Synchronized
    fun stop (): Result? {
        if (!running.get() && thread == null) return null
        running.set(false)
        runCatching { thread?.join(1_500) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        level = 0f
        onLevel?.invoke(0f)
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        pcm.reset()
        if (bytes.isEmpty() || bytes.size < WavFile.BYTES_PER_SECOND / 10) return null
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.wav")
        return try {
            file.outputStream().use { out ->
                out.write(WavFile.header(bytes.size))
                out.write(bytes)
            }
            Result(file, WavFile.seconds(bytes.size), file.length())
        } catch (error: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    /** Throw the recording away: no file, no result. */
    @Synchronized
    fun cancel () {
        running.set(false)
        runCatching { thread?.join(1_500) }
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        synchronized(pcm) { pcm.reset() }
        level = 0f
        onLevel?.invoke(0f)
    }
}
