package ru.uvw.dshnc

import android.content.Context
import java.io.File

/**
 * Crash log kept in the app's file.
 *
 * There is neither a console nor adb at hand on a phone, and "a failure happened again"
 * explains nothing. We write the stack into `crash.txt`: it can be read on the next
 * launch and shown on the screen.
 */
object CrashLog {
    fun write(context: Context, error: Throwable) {
        try {
            val file = File(context.filesDir, "crash.txt")
            file.writeText(error.stackTraceToString())
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String? = try {
        File(context.filesDir, "crash.txt").takeIf { it.exists() }?.readText()
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, "crash.txt").delete()
        } catch (_: Throwable) {
        }
    }
}
