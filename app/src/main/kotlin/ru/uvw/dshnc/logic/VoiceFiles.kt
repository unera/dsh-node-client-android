package ru.uvw.dshnc.logic

import java.io.File

/**
 * Where a take is written, and why there.
 *
 * The file has to be readable by the host afterwards, and the host reads it through
 * `files.copyFromDevice` — that path is checked against the directories the owner allowed. So a
 * recording belongs inside one of those directories, not in the app's private cache: a take in
 * `filesDir` could be copied too, but it would be invisible to the person and would disappear with
 * the app.
 *
 * The first usable root wins; when none of them can be written (storage unmounted, permission
 * revoked) the private directory keeps the feature alive instead of failing the recording.
 */
object VoiceFiles {
    /** The subdirectory inside an allowed root: the same `DSH` the host already knows. */
    const val SUBDIR = "DSH/voice"

    /**
     * Pick the directory this take goes to and make sure it exists.
     *
     * @param roots directories the owner allowed, in their order.
     * @param privateDir the app's own directory, used when nothing else is writable.
     * @returns a writable directory.
     */
    fun directory (roots: List<String>, privateDir: File): File {
        for (root in roots) {
            val base = try {
                File(root).canonicalFile
            } catch (error: Throwable) {
                continue
            }
            if (!base.isDirectory || !base.canWrite()) continue
            val target = File(base, SUBDIR)
            if (target.isDirectory || target.mkdirs()) return target
        }
        val fallback = File(privateDir, "voice")
        fallback.mkdirs()
        return fallback
    }

    /** A fresh name for one take: sorted by time, recognizable in a file manager. */
    fun name (now: Long = System.currentTimeMillis()): String = "voice-$now.wav"
}
