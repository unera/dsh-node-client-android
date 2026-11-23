package ru.uvw.dshnc

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.logic.Updater
import java.io.File

/**
 * The two dialogs every update path shares: «установить?» and «Android не разрешает».
 *
 * The main screen and the update screen must say the same words and take the same permission way
 * round, or the same file behaves differently depending on where it is installed from.
 */
object UpdateUi {
    /**
     * Ask whether to install a downloaded update.
     *
     * @param onDecline - what to do with «Позже». The main screen keeps the download waiting and
     * asks again on the next visit; the update screen leaves it to the main screen.
     */
    fun offerInstall (
        activity: AppCompatActivity,
        version: String,
        file: File,
        onDecline: () -> Unit = {},
        onStarted: () -> Unit = {},
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Обновление $version уже скачано")
            .setMessage("Установить сейчас?")
            .setPositiveButton("Установить") { _, _ -> install(activity, file, onStarted) }
            .setNegativeButton("Позже") { _, _ -> onDecline() }
            .show()
    }

    /**
     * Hand the file to the system installer — or explain what Android wants first.
     *
     * Since Android 8 installing an APK is a permission of its own, granted on a system screen
     * that this app cannot open by itself. Saying that in words beats a button that does nothing.
     */
    fun install (activity: AppCompatActivity, file: File, onStarted: () -> Unit = {}) {
        if (!Updater.canInstall(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение на установку")
                .setMessage(
                    "Android не разрешает приложениям ставить APK без согласия. Откроется " +
                        "системный экран — включите там «Разрешить установку из этого источника», " +
                        "и вернитесь сюда: обновление уже скачано.",
                )
                .setPositiveButton("Открыть настройки") { _, _ ->
                    runCatching { Updater.openInstallPermission(activity) }
                        .onFailure { toast(activity, "системный экран не открылся: ${it.message}") }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        runCatching { Updater.install(activity, file, "${activity.packageName}.files") }
            .onSuccess { onStarted() }
            .onFailure { toast(activity, "установщик не открылся: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun toast (activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
