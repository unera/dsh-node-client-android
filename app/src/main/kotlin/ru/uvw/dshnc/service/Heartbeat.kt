package ru.uvw.dshnc.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ru.uvw.dshnc.store.TokenStore

/**
 * Сердцебиение узла: будильник, который пробивает и Doze, и «заморозку при
 * простое».
 *
 * Обычные таймеры внутри процесса бесполезны: если система заморозила
 * приложение, они не срабатывают. `setExactAndAllowWhileIdle` будит процесс
 * даже в спящем режиме (не чаще раза в ~9 минут), служба проверяет связь и
 * переподключается. Так команда не ждёт, пока человек откроет приложение.
 */
class Heartbeat : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = TokenStore(context)
        // на паузе не будим: человек сам выключил узел
        if (store.token != null && store.enabled) NodeService.start(context)
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
            // точный будильник разрешён не всегда: если нет — ставим неточный,
            // он тоже просыпается, просто не секунда в секунду
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }
}
