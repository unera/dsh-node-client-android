package ru.uvw.dshnc.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import ru.uvw.dshnc.NotificationActionReceiver

/**
 * Уведомления с кнопками.
 *
 * Узел показывает заголовок, текст и до трёх кнопок. Нажатие отправляется на
 * сервер как событие с номером уведомления и номером кнопки: что это значит и
 * в какую сессию попадёт — решает сервер, устройство смысла не знает.
 */
class NotificationsApi (private val context: Context) {
    fun schema (): JSONObject = JSONObject()
        .put("name", "notifications")
        .put("version", NodeApi.VERSION)
        .put("methods", JSONArray()
            .put(JSONObject()
                .put("method", "post")
                .put("description", "Show a notification with buttons; a press returns as a device event")
                .put("args", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("id", JSONObject().put("type", "string"))
                        .put("title", JSONObject().put("type", "string"))
                        .put("text", JSONObject().put("type", "string"))
                        .put("buttons", JSONObject().put("type", "array")))
                    .put("required", JSONArray().put("id")))
                .put("returns", "object")))

    fun handle (method: String, args: JSONObject): JSONObject {
        if (method != "post") {
            return JSONObject().put("status", "error").put("message", "unknown method $method")
        }
        val id = args.optString("id")
        val title = args.optString("title").ifBlank { "dsh-nodes" }
        val text = args.optString("text")
        val buttons = args.optJSONArray("buttons") ?: JSONArray()

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // каждая кнопка — отдельный приёмник: своё действие должно сработать
        // даже если приложение закрыто
        for (index in 0 until minOf(buttons.length(), 3)) {
            val button = buttons.optJSONObject(index) ?: continue
            val buttonId = button.optString("id")
            val label = button.optString("label")
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .putExtra("notification", id)
                .putExtra("button", buttonId)
            val pending = PendingIntent.getBroadcast(
                context,
                (id + buttonId).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, pending)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "dsh-nodes", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        manager.notify(id.hashCode(), builder.build())
        return JSONObject().put("status", "ok").put("payload", JSONObject().put("shown", true).put("id", id))
    }

    companion object {
        private const val CHANNEL = "actions"
    }
}
