package ru.uvw.dshnc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.store.TokenStore

/**
 * The "Включить" button in the shade: the way back on without opening the app.
 *
 * Turning on is the same as the toggle on the main screen: the flag is written first, then the
 * service is started. A tap on a notification is a person's action, which is also what allows a
 * foreground service to be started from there — a timer would not be allowed to.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive (context: Context, intent: Intent) {
        val store = TokenStore(context)
        store.enabled = true
        // a person turning the node on is intent: the "displaced by a newer instance" mark
        // belongs to a previous life and would otherwise keep this instance silent
        store.replaced = false
        store.addEvent("шторка", "включено")
        NodeService.start(context)
        Shade.dismissOff(context)
    }
}
