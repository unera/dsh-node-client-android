package ru.uvw.dshnc.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A newer release of this app, looked up on GitHub and put on the phone.
 *
 * The node is installed by hand from an APK, so "check for updates" has to end in the same place
 * a person would: a downloaded file offered to the system installer. Nothing is installed from
 * here — the installer shows its own dialog, and that dialog is the permission.
 */
object Updater {
    /** Releases of this very app. */
    const val REPO = "unera/dsh-node-client-android"

    const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** How often to look, in hours. Zero means never. */
    enum class Interval(val key: String, val label: String, val hours: Long) {
        NEVER("never", "никогда", 0),
        HOURLY("hourly", "ежечасно", 1),
        DAILY("daily", "ежедневно", 24),
        WEEKLY("weekly", "еженедельно", 24 * 7),
        ;

        companion object {
            val DEFAULT = DAILY
            fun of(key: String?): Interval = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    data class Release(val version: String, val url: String, val notes: String)

    /** What one complete check ended with: a sentence for the screen and, if any, the file. */
    data class Outcome(val text: String, val version: String? = null, val file: File? = null)

    /** The version of the running app, as the person sees it in the menu. */
    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("0")

    /**
     * The newest release, if GitHub answers with one.
     *
     * A release without an `.apk` asset is not an update this app can install, so it is skipped
     * rather than reported and then failing on a download.
     */
    fun latest(timeoutMs: Int = 12_000): Release {
        val body = getText(API, timeoutMs)
        val json = JSONObject(body)
        val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        require(version.isNotBlank()) { "в ответе GitHub нет номера версии" }
        val assets = json.optJSONArray("assets") ?: throw IOException("у релиза нет файлов")
        var url = ""
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            url = asset.optString("browser_download_url")
            if (url.isNotBlank()) break
        }
        require(url.isNotBlank()) { "в релизе $version нет APK" }
        return Release(version, url, json.optString("name"))
    }

    /**
     * Is `candidate` newer than `current`?
     *
     * Number by number, not as strings: "0.10.100" is newer than "0.10.86", and a plain string
     * comparison says the opposite. A missing part counts as zero, so 0.10 and 0.10.0 agree.
     */
    fun newer(candidate: String, current: String): Boolean {
        val left = numbers(candidate)
        val right = numbers(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** Ask GitHub, compare, download when newer. */
    fun checkAndDownload(context: Context): Outcome {
        val current = currentVersion(context)
        val release = try {
            latest()
        } catch (error: Throwable) {
            return Outcome("проверка не удалась: " + (error.message ?: "GitHub не ответил"))
        }
        if (!newer(release.version, current)) return Outcome("обновлений нет: $current — последняя")
        val file = try {
            download(context, release)
        } catch (error: Throwable) {
            return Outcome(
                "есть ${release.version}, но скачать не вышло: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
        return Outcome("скачано ${release.version}", release.version, file)
    }

    /**
     * Download the APK into the app's own storage.
     *
     * Written next to its final name and renamed only when complete: a download cut short by a
     * dying network would otherwise leave an APK that looks ready and fails in the installer —
     * the worst moment to learn about it.
     */
    fun download(context: Context, release: Release): File {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "dshnc-${release.version}.apk")
        if (target.length() > 15_000_000) return target
        val part = File(dir, "${target.name}.part")
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dshnc/${currentVersion(context)}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub ответил кодом $code")
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            // This APK is about 10 MB; a download of a few kilobytes "succeeded" only formally.
            if (part.length() < 1_000_000) {
                throw IOException("скачалось ${part.length()} байт — это не целый APK")
            }
            if (expected > 0 && part.length() != expected) {
                throw IOException("скачалось ${part.length()} из $expected байт")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("не удалось переименовать ${part.name}")
            return target
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    /** May this app hand an APK to the installer at all? */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the person allows this app to install packages. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Hand the downloaded file to the system installer.
     *
     * `file://` has been refused since Android 7, so the file goes through the provider declared
     * in the manifest — and the read grant has to be explicit, because the installer runs as
     * another process.
     */
    fun install(context: Context, file: File, providerAuthority: String) {
        require(file.exists()) { "файла обновления больше нет: ${file.name}" }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, providerAuthority, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dshnc")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub ответил кодом $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }
}
