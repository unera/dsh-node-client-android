package ru.uvw.dshnc.logic

/**
 * Whether a question may pop up over the screen — the one rule the settings row and the
 * notification builder both ask.
 *
 * Up to Android 13 a full screen intent worked without asking anyone. Since Android 14 the
 * system decides, and the person grants it in a separate setting: without it a question still
 * arrives — as a notification in the shade — but it no longer pops up, and that looked like a
 * lottery: sometimes yes, sometimes no.
 *
 * Kept pure and free of Android so that the rule is covered by a unit test.
 */
object Popups {
    /** The first Android version that asks the person instead of allowing it outright. */
    const val FIRST_SDK = 34

    /** `sdk` — the platform version; `granted` — the system says the permission is on. */
    fun allowed(sdk: Int, granted: Boolean): Boolean = sdk < FIRST_SDK || granted
}
