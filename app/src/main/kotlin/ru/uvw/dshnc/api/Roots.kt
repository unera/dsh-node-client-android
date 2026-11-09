package ru.uvw.dshnc.api

import android.content.Context

/**
 * Разрешённые каталоги: что владелец телефона отдал узлу.
 *
 * Список живёт в настройках приложения и один и тот же отдаётся модели через
 * `files.roots`, а на будущее — десктопному клиенту: он сможет показывать те же
 * границы, не угадывая их.
 */
object Roots {
    private const val KEY = "roots"

    /** Разумное умолчание: своя песочница и общие каталоги телефона. */
    fun defaults(context: Context): List<String> = listOf(
        context.filesDir.path,
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
    )

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences("dshnc-roots", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, null) ?: return defaults(context)
        // пустой набор — это не «ничего не разрешено», а «ещё не настраивали»
        return if (saved.isEmpty()) defaults(context) else saved.sorted()
    }

    fun save(context: Context, roots: List<String>) {
        context.getSharedPreferences("dshnc-roots", Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, roots.toSet()).apply()
    }
}
