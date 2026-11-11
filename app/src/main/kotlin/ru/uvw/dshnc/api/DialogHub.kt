package ru.uvw.dshnc.api

import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ru.uvw.dshnc.AskActivity
import ru.uvw.dshnc.AskPressReceiver
import ru.uvw.dshnc.DialogListActivity
import ru.uvw.dshnc.PopupPermission
import ru.uvw.dshnc.R
import ru.uvw.dshnc.logic.AskLogic
import ru.uvw.dshnc.logic.AskRequest
import ru.uvw.dshnc.logic.DialogStyle

/**
 * One open question: who waits for it and how it ends.
 *
 * The wait is a latch, not a loop with a flag: the answer comes from another thread — the
 * window, a notification button, the server's cancel — and exactly one of those wins.
 */
class AskPending (val seq: Long, val request: AskRequest, val context: JSONObject) {
    private val latch = CountDownLatch(1)
    private val settled = AtomicBoolean(false)
    @Volatile private var chosen: Int? = null

    /** A button press, 0..2. False means the question is already answered. */
    fun press (index: Int): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        chosen = index
        latch.countDown()
        return true
    }

    /**
     * The question is cancelled from the outside: the server's `#INTERNAL.CANCEL`.
     *
     * Closing the window is not this: a hidden window leaves the question open and the
     * command waiting, so only the server and the command's own timeout end a question.
     */
    fun dismiss (): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        chosen = null
        latch.countDown()
        return true
    }

    /**
     * Wait for the answer, at most [ms].
     *
     * Null means nobody answered in time — and that answer is `cancel`, the same as any
     * other dismissal.
     */
    fun await (ms: Long): Int? {
        latch.await(ms, TimeUnit.MILLISECONDS)
        return chosen
    }
}

/**
 * The open questions of the process, by command number.
 *
 * Android has no "dialog service": the window belongs to an activity, the notification to
 * the notification manager, and both have to lead to one answer. That is why the state
 * lives in this object and not in the caller: the window, a notification button and the
 * server's cancel all talk to the same pending question.
 *
 * How many questions may be open at once is deliberately not decided here: a second `ask`
 * simply opens a second one. Whether that makes sense for the person is the caller's
 * business, not the phone's.
 */
object DialogHub {
    private const val CHANNEL = "dialogs"

    private val open = ConcurrentHashMap<Long, AskPending>()
    /** How to take each window down; set by the activity while it is visible. */
    private val closers = ConcurrentHashMap<Long, () -> Unit>()

    /** The open question with this command number, or null. */
    fun current (seq: Long): AskPending? = open[seq]

    /** Every open question, oldest first — what the list screen shows. */
    fun active (): List<AskPending> = open.values.sortedBy { it.seq }

    /** Told when the registry changes, so the list screen follows it. */
    @Volatile private var listRefresh: (() -> Unit)? = null

    fun attachList (refresh: () -> Unit) {
        listRefresh = refresh
    }

    fun detachList () {
        listRefresh = null
    }

    /** Register a question and show it: a notification always, a window when it can appear. */
    fun begin (context: Context, seq: Long, request: AskRequest, echo: JSONObject): AskPending {
        val created = AskPending(seq, request, echo)
        open[seq] = created
        present(context, created)
        return created
    }

    /** The window came up for this question. */
    fun attach (seq: Long, close: () -> Unit) {
        closers[seq] = close
    }

    fun detach (seq: Long) {
        closers.remove(seq)
    }

    /** A button press from the window or from a notification action. */
    fun press (seq: Long, index: Int): Boolean = open[seq]?.press(index) ?: false

    /**
     * Cancel the question with this number — the server's `#INTERNAL.CANCEL`.
     *
     * Hiding a window is not this: the same call is what a timeout ends with, and both are
     * the only two ways a question answers `cancel`.
     */
    fun dismiss (seq: Long): Boolean = open[seq]?.dismiss() ?: false

    /**
     * Close the question and take both surfaces down.
     *
     * Called once the answer is settled: the window is finished, the notification is
     * removed, the number is free.
     */
    fun shutdown (context: Context, pending: AskPending) {
        open.remove(pending.seq, pending)
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId(pending.seq))
        }
        val close = closers.remove(pending.seq)
        runCatching { close?.invoke() }
        // the list follows the registry: an answered question leaves it at once
        runCatching { listRefresh?.invoke() }
    }

    /**
     * How to show the question: a notification always, a window when it can appear.
     *
     * The notification is not a fallback but the permanent address of the question: closing
     * the window hides it without answering anything, and the person reopens the very same
     * question from the shade. Starting an activity from the background is refused since
     * Android 10, and the refusal is silent — no exception, no window — so the notification
     * carries the question either way and the command keeps waiting.
     */
    private fun present (context: Context, pending: AskPending) {
        runCatching { notify(context, pending) }
        if (open.size > 1) {
            // More than one question: windows on top of each other would hide one of them,
            // so the phone shows the list instead.
            closers.keys.filter { it != pending.seq }.forEach { seq -> runCatching { closers[seq]?.invoke() } }
            runCatching {
                context.startActivity(
                    Intent(context, DialogListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        runCatching {
            context.startActivity(intent(context, pending.seq).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The heads-up notification: a tap opens the same window, the buttons answer directly. */
    private fun notify (context: Context, pending: AskPending) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.dialog_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(context, notificationId(pending.seq), intent(context, pending.seq), flags)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(pending.request.title.ifBlank { context.getString(R.string.dialog_default_title) })
            .setContentText(pending.request.content)
            // the shade has no background to paint: the style tints the icon and the title,
            // which is all the platform offers
            .setColor(styleColor(pending.request.style))
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.request.content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // the question itself is the point: on a lock screen it must be readable, not
            // hidden behind "content hidden" — the phone is locked, the person is the owner
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // the question is alive until the server cancels it or its command times out:
            // removing the notification would only hide the way back to it
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent)
        // a full-screen intent only when the phone is in the person's hands: on the lock
        // screen a question must stay a notification, otherwise a pocket answers for them
        val keyguard = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (!keyguard && fullScreenAllowed(context)) builder.setFullScreenIntent(openIntent, true)
        pending.request.buttons.forEachIndexed { index, button ->
            val label = if (button.icon.isBlank()) button.title else "${button.icon} ${button.title}"
            val press = PendingIntent.getBroadcast(
                context,
                "${pending.seq}:$index".hashCode(),
                pressIntent(context, pending.seq, index),
                flags,
            )
            builder.addAction(0, label, press)
        }
        manager.notify(notificationId(pending.seq), builder.build())
    }

    /** One notification per question: two open questions must not overwrite each other. */
    private fun notificationId (seq: Long): Int = (seq and 0xFFFF_FFFFL).toInt()

    private fun intent (context: Context, seq: Long): Intent =
        Intent(context, AskActivity::class.java)
            .putExtra(AskActivity.EXTRA_SEQ, seq)

    private fun pressIntent (context: Context, seq: Long, index: Int): Intent =
        Intent(context, AskPressReceiver::class.java)
            .putExtra(AskPressReceiver.EXTRA_SEQ, seq)
            .putExtra(AskPressReceiver.EXTRA_INDEX, index)

    /**
     * A full screen intent needs the permission, and since Android 14 the system decides
     * whether this app still has it. The same answer the settings row shows comes from here.
     */
    private fun fullScreenAllowed (context: Context): Boolean = PopupPermission.allowed(context)

    /**
     * The overall look as a colour: grey, blue or red.
     *
     * The window fills its whole card with it; the notification has no background to paint,
     * so there it tints the icon and the title — that is the only thing the platform offers.
     */
    fun styleColor (style: DialogStyle): Int = when (style) {
        // the card is deliberately dark: the buttons on it are light, and white text on a
        // light card would be unreadable
        DialogStyle.WARNING -> 0xFF1E3A8A.toInt()
        DialogStyle.DANGER -> 0xFF7F1D1D.toInt()
        DialogStyle.NORMAL -> 0xFF1F2937.toInt()
    }
}
