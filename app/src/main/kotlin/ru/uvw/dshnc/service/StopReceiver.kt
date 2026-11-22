package ru.uvw.dshnc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.store.TokenStore

/**
 * The "Turn off" button right in the notification — just like "Stop" in v2rayTun.
 *
 * Turning off is the same here as the toggle on the main screen: the state is
 * remembered, the alarm no longer wakes the node, there is no network at all.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = TokenStore(context)
        store.enabled = false
        store.addEvent("шторка", "выключено")
        NodeService.stop(context)
        // the service's own row goes away with the service; in its place stands the "off" row,
        // which is the way back on without opening the app
        Shade.showOff(context)
    }
}
