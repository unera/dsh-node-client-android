package ru.uvw.dshnc

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.service.StopReceiver

/**
 * What a person sees in the notification shade.
 *
 * The tap used to do nothing at all, and the state word was written in five places. Both are
 * now checked here: the text is exactly what the caller decided (the decision itself lives in
 * `ChannelState`), the row opens the app and the button stops the node.
 */
@RunWith(RobolectricTestRunner::class)
class NodeServiceNotificationTest {
    private fun service () = Robolectric.buildService(NodeService::class.java).create().get()

    @Test
    fun `the state word is the one that was decided`() {
        val service = service()
        val notification = service.notification("Соединено")
        assertEquals("Соединено", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun `a tap on the row opens the app window`() {
        val service = service()
        val notification = service.notification("Соединено")
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `the row stays and carries exactly one way out`() {
        val service = service()
        val notification = service.notification("Соединено")
        assertEquals(true, notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification.actions.size)
        assertEquals(service.getString(R.string.notif_stop), notification.actions[0].title.toString())
        // the button must go to the receiver that turns the node off, not back into the app
        val stopIntent = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(StopReceiver::class.java.name, stopIntent.component?.className)
    }
}
