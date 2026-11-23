package ru.uvw.dshnc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.store.TokenStore

/**
 * Brings the node up after the phone boots, when the person asked for it.
 *
 * Two conditions, both of them the person's: the switch "запускать при старте" is on, and the
 * node itself is on. The second one is not decoration — off means "no network activity at all",
 * and a reboot must not be a way to start something that was deliberately stopped.
 *
 * Android may refuse a foreground service started from the background (since Android 12 the
 * allowed types at boot are a short list). That refusal is written to the node log by
 * `NodeService.start`; the alarm watchdog brings the node up later anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive (context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = TokenStore(context)
        if (!store.autostart || !store.enabled || store.token == null) return
        store.addEvent("загрузка", "запуск при старте")
        NodeService.start(context)
    }
}
