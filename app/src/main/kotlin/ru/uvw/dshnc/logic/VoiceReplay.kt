package ru.uvw.dshnc.logic

import java.io.File

/**
 * The last thing the phone said, kept so it can be said again.
 *
 * A voice answer is heard once, in a car, at the wrong moment — «повтори» is the most natural thing
 * to want. Synthesized voices come as a file, device voices as a sentence, and either way exactly
 * one of them is worth keeping: the latest. Keeping every answer would fill the cache with the whole
 * conversation, so a new answer deletes the previous file before it takes its place.
 *
 * Nothing here plays anything: it holds the phrase, the screen asks for it.
 */
object VoiceReplay {
    /** One remembered phrase: the words, or the file that holds them. */
    data class Last (val speak: String?, val file: File?)

    /** The file a host-synthesized answer is kept in: one name, replaced on every new answer. */
    const val FILE_NAME = "last-answer.ogg"

    private val lock = Object()
    private var last: Last? = null

    /** Remember a sentence spoken by the device engine. */
    fun rememberSpeech (speak: String?) {
        val text = speak?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) { last = Last(text, null) }
    }

    /**
     * Remember a downloaded answer, replacing whatever was kept before.
     *
     * The file is copied into [directory] under one fixed name, so the cache holds exactly one
     * answer and the old one is gone the moment a new one arrives.
     *
     * @param source the freshly downloaded file.
     * @param directory the app's cache directory.
     * @returns the kept file, or null when the source could not be read.
     */
    fun rememberFile (source: File, directory: File): File? {
        if (!source.isFile) return null
        val target = File(directory, FILE_NAME)
        return try {
            directory.mkdirs()
            if (target.exists()) target.delete()
            source.copyTo(target, overwrite = true)
            synchronized(lock) { last = Last(null, target) }
            target
        } catch (error: Throwable) {
            null
        }
    }

    /** What can be repeated right now, if anything. */
    fun snapshot (): Last? = synchronized(lock) { last }

    /** Is there something to repeat? */
    fun available (): Boolean = snapshot() != null

    /** Forget the phrase: a new conversation starts without a «повтори». */
    fun clear () {
        synchronized(lock) {
            last?.file?.let { runCatching { it.delete() } }
            last = null
        }
    }
}
