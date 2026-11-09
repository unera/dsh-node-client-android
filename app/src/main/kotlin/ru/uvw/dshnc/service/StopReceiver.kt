package ru.uvw.dshnc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.store.TokenStore

/**
 * Кнопка «Выключить» прямо в уведомлении — как «Остановить» у v2rayTun.
 *
 * Выключение здесь такое же, как тумблер на главном экране: состояние
 * запоминается, будильник узел больше не будит, сети нет вовсе.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = TokenStore(context)
        store.enabled = false
        NodeService.stop(context)
        // уведомление исчезнет вместе со службой — это и есть подтверждение
    }
}
