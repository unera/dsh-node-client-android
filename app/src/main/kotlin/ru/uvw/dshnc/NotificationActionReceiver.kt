package ru.uvw.dshnc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.store.TokenStore

/**
 * Нажатие кнопки в уведомлении.
 *
 * Устройство сообщает только два номера: уведомление и кнопку. Текст, смысл и
 * сессия живут на сервере — иначе клиент мог бы говорить за человека.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive (context: Context, intent: Intent) {
        val notification = intent.getStringExtra("notification") ?: return
        val button = intent.getStringExtra("button") ?: return
        val store = TokenStore(context)
        val base = store.base ?: return
        val token = store.token ?: return
        // убираем уведомление сразу: второй раз эта кнопка всё равно не сработает
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(notification.hashCode())
        }
        val pending = goAsync()
        Thread {
            try {
                NodeApi(base).sendEvent(
                    token,
                    org.json.JSONObject()
                        .put("notification", notification)
                        .put("button", button)
                        .put("text", "нажата кнопка")
                        .put("session", ""),
                )
                store.addEvent("notify-ответ", "$notification/$button")
            } catch (error: Throwable) {
                store.addEvent("notify-ошибка", error.message ?: error.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
