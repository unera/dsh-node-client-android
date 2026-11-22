package ru.uvw.dshnc

import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.uvw.dshnc.service.NodeService
import ru.uvw.dshnc.service.Shade
import ru.uvw.dshnc.service.StartReceiver
import ru.uvw.dshnc.service.StopReceiver
import ru.uvw.dshnc.store.TokenStore

/**
 * Turning the node off from the shade, and back on from the shade.
 *
 * Pressing "Выключить" used to cancel the loop, and the cancellation was caught as a failure:
 * the row stayed with "Нет связи: Job was cancelled" and its only button switched off again.
 * The way back on existed only inside the app. Both halves are checked here — what the pressed
 * button leaves behind, and what the row of a switched-off node offers.
 */
@RunWith(RobolectricTestRunner::class)
class ShadeOffTest {
    private fun app () = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun manager () = app().getSystemService(android.app.NotificationManager::class.java)

    @Test
    fun `the title is the server address, not the device name`() {
        val store = TokenStore(app())
        store.base = "https://dsh.uvw.ru/api/dsh-nodes"
        store.name = "Телефон"
        val service = Robolectric.buildService(NodeService::class.java).create().get()
        val notification = service.notification("Соединено")
        assertEquals("dsh.uvw.ru", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `without an address the title falls back to the app name`() {
        val store = TokenStore(app())
        store.base = null
        val service = Robolectric.buildService(NodeService::class.java).create().get()
        val notification = service.notification("Соединено")
        assertEquals(service.getString(R.string.app_name), notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `turning off leaves a row that says so and offers turning on`() {
        val store = TokenStore(app())
        store.base = "https://dsh.uvw.ru/"
        store.enabled = true

        StopReceiver().onReceive(app(), Intent())

        assertFalse(store.enabled)
        val shown = shadowOf(manager()).allNotifications
        assertEquals(1, shown.size)
        val row = shown.first()
        assertEquals("dsh.uvw.ru", row.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(app().getString(R.string.notif_off), row.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(1, row.actions.size)
        assertEquals(app().getString(R.string.notif_start), row.actions[0].title.toString())
        val intent = shadowOf(row.actions[0].actionIntent).savedIntent
        assertEquals(StartReceiver::class.java.name, intent.component?.className)
        // "off" is a state, not a held connection: it may be swiped away
        assertEquals(0, row.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun `the way back on writes the flag, clears the displaced mark and drops the row`() {
        val store = TokenStore(app())
        store.enabled = false
        store.replaced = true
        Shade.showOff(app())
        assertNotNull(shadowOf(manager()).getNotification(2))

        StartReceiver().onReceive(app(), Intent())

        assertTrue(store.enabled)
        assertFalse(store.replaced)
        assertNull(shadowOf(manager()).getNotification(2))
    }
}
