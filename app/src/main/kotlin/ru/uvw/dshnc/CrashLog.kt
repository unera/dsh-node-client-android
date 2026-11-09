package ru.uvw.dshnc

import android.content.Context
import java.io.File

/**
 * Журнал падений в файл приложения.
 *
 * На телефоне нет ни консоли, ни adb под рукой, а «снова произошёл сбой» ничего
 * не объясняет. Пишем стек в `crash.txt`: его можно прочитать при следующем
 * запуске и показать на экране.
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
