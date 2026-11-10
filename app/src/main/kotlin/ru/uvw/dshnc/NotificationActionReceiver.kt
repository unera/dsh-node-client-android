package ru.uvw.dshnc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.store.TokenStore

/**
 * A button press in a notification.
 *
 * The device reports only two numbers: the notification and the button. The text, the
 * meaning and the session live on the server — otherwise the client could speak for
 * the person.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive (context: Context, intent: Intent) {
        val notification = intent.getStringExtra("notification") ?: return
        val button = intent.getStringExtra("button") ?: return
        val store = TokenStore(context)
        val base = store.base ?: return
        val token = store.token ?: return
        // drop the notification right away: this button will not fire a second time anyway
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(notification.hashCode())
        }
        // the press number is assigned once: a retry goes out with the same number,
        // and the server understands it is not a second press but the same send
        val press = store.nextPress()
        val body = org.json.JSONObject()
            .put("notification", notification)
            .put("button", button)
            .put("id", "p$press")
            .put("text", "")
        val pending = goAsync()
        Thread {
            var sent = false
            val delays = longArrayOf(0, 2_000, 5_000)
            for ((attempt, delay) in delays.withIndex()) {
                if (delay > 0) Thread.sleep(delay)
                try {
                    NodeApi(base).sendEvent(token, body)
                    store.addEvent("notify-ответ", "$notification/$button p$press, попытка ${attempt + 1}")
                    sent = true
                    break
                } catch (error: Throwable) {
                    store.addEvent("notify-ошибка", "p$press: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            if (!sent) store.addEvent("notify-потеря", "$notification/$button p$press")
            pending.finish()
        }.start()
    }
}
