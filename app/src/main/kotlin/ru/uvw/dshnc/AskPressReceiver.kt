package ru.uvw.dshnc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.uvw.dshnc.api.DialogHub

/**
 * The notification fallback answers without opening anything.
 *
 * This receiver turns a press on a notification button into the same answer a press in the
 * window gives. A tap on the notification itself opens the window instead — the question
 * outlives both, so neither removing the notification nor closing the window answers it.
 */
class AskPressReceiver : BroadcastReceiver() {
    override fun onReceive (context: Context, intent: Intent) {
        val seq = intent.getLongExtra(EXTRA_SEQ, -1L)
        if (seq < 0) return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (index < 0) return
        DialogHub.press(seq, index)
    }

    companion object {
        const val EXTRA_SEQ = "seq"
        const val EXTRA_INDEX = "index"
    }
}
