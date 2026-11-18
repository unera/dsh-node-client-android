package ru.uvw.dshnc

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.api.DeviceApi

/**
 * The dispatcher and the API must agree about who answers what.
 *
 * Three methods reached the files dispatcher and answered `path_denied` for a method that has no
 * path. A list plus `handles()` fixed the routing; this test makes sure the list, the schema and
 * the `when` in `handle` all say the same thing — a method added to one place and forgotten in
 * another fails here instead of on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceRoutingTest {
    private fun api (): DeviceApi = DeviceApi(ApplicationProvider.getApplicationContext())

    @Test
    fun `every method in the list has a branch in handle`() {
        // An empty argument set is enough: a branch that exists refuses the arguments, and a
        // branch that does not exist answers unknown_method. That is the difference we test.
        for (method in DeviceApi.METHODS) {
            val answer = api().handle(method, JSONObject())
            val code = answer.optString("code")
            assertTrue("метод $method не обрабатывается: $answer", code != "unknown_method")
        }
    }

    @Test
    fun `every method in the list is documented in the schema`() {
        val schema = api().schema()
        val methods = schema.optJSONArray("methods")
        val documented = (0 until (methods?.length() ?: 0))
            .map { methods?.optJSONObject(it)?.optString("method").orEmpty() }
            .toSet()
        for (method in DeviceApi.METHODS) {
            assertTrue("метод $method не описан в schema", method in documented)
        }
    }

    @Test
    fun `nothing outside the list is handled`() {
        val answer = api().handle("no_such_method", JSONObject())
        assertTrue(answer.optString("code") == "unknown_method")
    }
}
