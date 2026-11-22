package ru.uvw.dshnc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.uvw.dshnc.MainActivity
import ru.uvw.dshnc.R
import ru.uvw.dshnc.logic.ServerName
import ru.uvw.dshnc.store.TokenStore

/**
 * The row in the shade for a node that is turned off.
 *
 * The service's own notification dies with the service, so pressing "Выключить" used to leave
 * either nothing at all or a stale row: the text of a cancelled loop ("Нет связи: Job was
 * cancelled") over a button that only ever switches off. A person who turned the node off by
 * accident had to open the app to get it back.
 *
 * This row is the way back: it says the node is off and offers "Включить". It belongs to the
 * process, not to the service — that is why it lives here and not in `NodeService`.
 */
object Shade {
    /** A number of its own: the service's row and this one must be able to exist apart. */
    private const val OFF_ID = 2

    private const val CHANNEL = "node"

    /** Show "off" with the way back on. Call after the service has been stopped. */
    fun showOff (context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val title = ServerName.host(TokenStore(context).base) ?: context.getString(R.string.app_name)
            val start = PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, StartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                context,
                4,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notif_off))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                // not ongoing: "off" is a state, not a held connection, and it may be swiped away
                .setOngoing(false)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.notif_start),
                        start,
                    ).build(),
                )
                .build()
            manager.notify(OFF_ID, notification)
        }
    }

    /** The node is on again: the "off" row has done its job. */
    fun dismissOff (context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(OFF_ID)
        }
    }
}
