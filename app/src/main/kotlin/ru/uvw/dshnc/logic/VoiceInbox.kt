package ru.uvw.dshnc.logic

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything the host sends the screen, as a queue.
 *
 * A command must not wait for the screen: drawing a line and starting a sentence are quick, but
 * they belong to the user interface, and the command loop is a shared resource — a stalled draw
 * would hold every later command, exactly the failure the barriers caused. So a handler only
 * appends here and answers at once; the screen drains at its own pace.
 *
 * When the screen is closed nothing is lost: the queue keeps the lines and the next opening draws
 * them in order.
 */
object VoiceInbox {
    /** One thing to show. */
    sealed class Item {
        /** A line of the conversation. */
        data class Chat (
            val id: String,
            val role: VoiceChat.Role,
            val text: String,
            /** Who answers, when the host sent it with the line. */
            val who: VoiceChat.Whom? = null,
            /** The short form of an answer, when the host sent one for the ear. */
            val speak: String? = null,
        ) : Item()

        /** A step of the model's work: shown in one line that replaces the previous one. */
        data class Activity (
            val id: String,
            val n: Long,
            val name: String,
        ) : Item()
    }

    private val queue = ConcurrentLinkedQueue<Item>()

    /** A nudge for the screen; it hops to the main thread itself. */
    @Volatile var onPosted: (() -> Unit)? = null

    /** Put one item in the queue. Never blocks and never draws. */
    fun post (item: Item) {
        queue.add(item)
        onPosted?.invoke()
    }

    /**
     * Take everything that has arrived, in order.
     *
     * @returns the items, oldest first; empty when there is nothing.
     */
    fun drain (): List<Item> {
        val items = ArrayList<Item>()
        while (true) {
            val item = queue.poll() ?: break
            items.add(item)
        }
        return items
    }

    /** How many items are waiting: used by the tests and by the diagnostics. */
    fun size (): Int = queue.size

    /** Drop everything unsullied: a re-paired node starts a new conversation. */
    fun clear () {
        queue.clear()
    }
}
