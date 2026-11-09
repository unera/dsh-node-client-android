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
        // номер нажатия присваиваем один раз: повтор уходит с тем же номером,
        // и сервер понимает, что это не второе нажатие, а та же отправка
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
