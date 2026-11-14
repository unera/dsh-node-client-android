package ru.uvw.dshnc.logic

import java.util.concurrent.LinkedBlockingQueue

/**
 * One mouth, one sentence at a time.
 *
 * Speech is a queue rather than a call for a reason a person notices immediately: a new line must
 * never cut the previous one in half. So the worker takes one task, finishes it, and only then
 * looks at the next — no `flush`, no interruption, ever.
 *
 * The work itself is injected: speaking text and playing a downloaded file are Android's business,
 * and keeping them outside makes the ordering testable without a device.
 */
class VoiceSpeaker (
    private val speakText: (String) -> Unit,
    private val playAudio: (audio: String, seq: Long) -> Unit,
    private val log: (String, String) -> Unit = { _, _ -> },
) {
    /** One thing to say: a text through the engine, or a file from the host. */
    data class Task (
        val id: String,
        val text: String,
        val audio: String? = null,
        val seq: Long = 0L,
    )

    private val queue = LinkedBlockingQueue<Task>()
    @Volatile private var running = true

    private val worker = Thread({ loop() }, "dshnc-voice-speech").apply {
        isDaemon = true
        start()
    }

    /** Put one task at the end of the line: it waits for everything before it. */
    fun submit (task: Task) {
        if (!running) return
        queue.add(task)
    }

    /** How many tasks are waiting, not counting the one being said right now. */
    fun pending (): Int = queue.size

    private fun loop () {
        while (running) {
            val task = try {
                queue.take()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                log("speech start", if (task.audio != null) "audio ${task.audio}" else "text ${task.text.length} симв")
                if (task.audio != null) playAudio(task.audio, task.seq) else speakText(task.text)
                log("speech done", task.id)
            } catch (error: Throwable) {
                // one failed sentence must not silence the rest of the conversation
                log("speech fail", "${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** Stop the worker: pending sentences are dropped, the current one is left to finish. */
    fun shutdown () {
        running = false
        queue.clear()
        worker.interrupt()
    }
}
