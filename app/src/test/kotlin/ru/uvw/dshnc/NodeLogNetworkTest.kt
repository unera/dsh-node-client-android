package ru.uvw.dshnc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo
import ru.uvw.dshnc.store.NodeLog

/**
 * The log line that was missing when the node went silent: which network was there at the
 * moment of a close. It needs a real `Context`, so it is checked on Robolectric rather than
 * argued about.
 */
@RunWith(RobolectricTestRunner::class)
class NodeLogNetworkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun network (type: Int) = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        type,
        0,
        true,
        NetworkInfo.State.CONNECTED,
    )

    @Test
    fun `a probe that has nothing to ask does not invent a transport`() {
        shadowOf(manager).setActiveNetworkInfo(null)
        val value = NodeLog.network(context)
        // "none" is the answer we want; "probe-failed: …" is the honest answer when the
        // system refuses to say. What must never happen is a made-up "wifi/validated"
        assertTrue("выдумал транспорт: $value", value == "none" || value.startsWith("probe-failed"))
    }

    @Test
    fun `the transport comes from the system, not from a guess`() {
        shadowOf(manager).setActiveNetworkInfo(network(ConnectivityManager.TYPE_WIFI))
        val value = NodeLog.network(context)
        assertEquals("wifi/not-validated", value)
    }

    @Test
    fun `a cellular link is named cellular`() {
        shadowOf(manager).setActiveNetworkInfo(network(ConnectivityManager.TYPE_MOBILE))
        assertEquals("cell/not-validated", NodeLog.network(context))
    }
}
