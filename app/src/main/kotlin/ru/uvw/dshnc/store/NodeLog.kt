package ru.uvw.dshnc.store

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The node's own log on disk — the only witness when the process is gone.
 *
 * The sixty short events in `TokenStore` answer "what happened" only while the app is
 * alive to be asked. The question that actually needs an answer is the other one: why
 * did the process stop answering for hours. So the log lives in a file, with the full
 * date and time, is appended to from every place that decides a connection, and is read
 * back through `session.debug_info` — no shell and no root needed.
 *
 * It is not a debugging aid for the protocol: unknown frames and the like are already
 * covered by the events. This is for the environment around the socket: alarms, service
 * starts, network state, and the exceptions Android throws when it refuses to let a
 * background service start at all.
 */
object NodeLog {
    private const val FILE = "node.log"

    /** When the file grows past this, the older half is dropped: a phone is not a server. */
    private const val LIMIT = 200 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)

    /** Append one line. Never throws: a log that breaks the node is worse than no log. */
    @Synchronized
    fun write(context: Context, text: String) {
        append(file(context), text)
    }

    /**
     * Append one line to a file, rotating when the file outgrows `limit`.
     *
     * Separate from the context so that the file rules — the cap, the format, the choice of
     * what to throw away — can be checked in a unit test on a temporary file instead of on a
     * phone.
     */
    @Synchronized
    fun append (target: File, text: String, limit: Int = LIMIT) {
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() > limit) trim(target)
            target.appendText("${stamp.format(Date())}  $text\n")
        }
    }

    /** The last [lines] lines of a file, oldest first. */
    @Synchronized
    fun tail (target: File, lines: Int): List<String> {
        val list = runCatching { target.readLines() }.getOrElse { emptyList() }
        return if (list.size <= lines) list else list.takeLast(lines)
    }

    /** The last [lines] lines, oldest first. */
    fun read(context: Context, lines: Int = 100): List<String> = tail(file(context), lines)

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }

    private fun trim(target: File) {
        target.writeText(rotated(target.readLines()).joinToString("\n", postfix = "\n"))
    }

    /**
     * What survives a trim: the newer half.
     *
     * Kept pure and apart from the file so that the rule can be checked without a phone:
     * the interesting part is not the write but the choice of what to drop.
     */
    fun rotated(lines: List<String>): List<String> = lines.drop(lines.size / 2)

    /**
     * What the network looks like right now: "wifi/validated", "cell/not-validated",
     * "none". A close without this line reads as a bug in the node, while half of the
     * closes are simply "the radio was gone".
     */
    fun network(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return@runCatching "none"
        val caps = manager.getNetworkCapabilities(active) ?: return@runCatching "unknown"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val validated = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not-validated"
        "$kind/$validated"
    }.getOrElse { "probe-failed: ${it.javaClass.simpleName}" }
}
