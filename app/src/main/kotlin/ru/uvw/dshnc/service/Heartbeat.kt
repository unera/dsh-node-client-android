package ru.uvw.dshnc.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.uvw.dshnc.logic.Revival
import ru.uvw.dshnc.store.TokenStore

/**
 * The node's heartbeat: an alarm that gets through both Doze and "freeze while idle".
 *
 * Ordinary timers inside the process are useless: if the system has frozen the app,
 * they never fire. `setExactAndAllowWhileIdle` wakes the process even in sleep mode
 * (no more than once per ~9 minutes), the service checks the link and reconnects. That
 * way a command does not wait for the person to open the app.
 */
class Heartbeat : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = TokenStore(context)
        // do not wake up while paused: the person turned the node off themselves.
        // Nor while displaced: a newer instance of this node holds the channel, and waking
        // up would push it out — the two instances would ping-pong every ten minutes, each
        // reviving the one the other displaced. Only a person clears that flag.
        if (Revival.allowed(store.enabled, store.token != null, store.replaced)) NodeService.start(context)
        schedule(context)
    }

    companion object {
        private const val INTERVAL_MS = 10 * 60 * 1000L

        fun schedule(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, Heartbeat::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + INTERVAL_MS
            // an exact alarm is not always permitted: if it is not, we set an inexact
            // one, it wakes up too, just not to the second
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }
}
