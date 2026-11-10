package ru.uvw.dshnc.api

import android.content.Context

/**
 * Allowed directories: what the phone owner gave to the node.
 *
 * The list lives in the app settings and the very same one is handed to the model via
 * `files.roots`, and later to the desktop client: it will be able to show the same
 * boundaries instead of guessing them.
 */
object Roots {
    private const val KEY = "roots"

    /** A sensible default: our own sandbox and the shared directories of the phone. */
    fun defaults(context: Context): List<String> = listOf(
        context.filesDir.path,
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
    )

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences("dshnc-roots", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, null) ?: return defaults(context)
        // an empty set means "not configured yet", not "nothing is allowed"
        return if (saved.isEmpty()) defaults(context) else saved.sorted()
    }

    fun save(context: Context, roots: List<String>) {
        context.getSharedPreferences("dshnc-roots", Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, roots.toSet()).apply()
    }
}
