package ru.uvw.dshnc

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.uvw.dshnc.logic.VoiceFiles
import ru.uvw.dshnc.logic.VoiceOutbox
import ru.uvw.dshnc.logic.VoiceUpload
import java.io.File
import java.nio.file.Files

/**
 * One press, one request: what goes on the wire and how the answer is read.
 *
 * The take is a device event, not an answer to a command, so there is no `seq` here and no
 * destination path: the server picks the path, and a retry of the same press must stay the same
 * turn — which is what the press identity in the query is for.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceUploadTest {

    @Test
    fun `the address carries the kind, the format and the press identity` () {
        val url = VoiceUpload.url("https://dsh.example/", "abc-123")
        assertEquals("https://dsh.example/v1/upload-file?type=voice&filetype=wav&id=abc-123&synth=device", url)
        // a base without a slash must not produce a double slash
        assertEquals(
            "https://dsh.example/v1/upload-file?type=voice&filetype=wav&id=x&synth=device",
            VoiceUpload.url("https://dsh.example", "x"),
        )
    }

    @Test
    fun `an identity with awkward characters survives the query` () {
        val url = VoiceUpload.url("https://dsh.example", "a b/c")
        assertTrue(url.endsWith("id=a+b%2Fc&synth=device"))
        // no seq anywhere: the route is an event, not a command result
        assertFalse(url.contains("seq="))
    }

    @Test
    fun `the duration goes as one decimal place` () {
        assertEquals(mapOf("x-seconds" to "4.8"), VoiceUpload.headers(4.83))
        assertEquals(mapOf("x-seconds" to "0.0"), VoiceUpload.headers(Double.NaN))
        assertEquals(mapOf("x-seconds" to "12.0"), VoiceUpload.headers(12.0))
    }


    @Test
    fun `all three marks travel as whole milliseconds` () {
        val headers = VoiceUpload.headers(
            4.8,
            VoiceUpload.Times(recordStart = 1_794_593_000_123, recordStop = 1_794_593_005_789, sentAt = 1_794_593_005_812),
        )
        assertEquals("1 794 593 000 123".replace(" ", ""), headers["x-record-start"])
        assertEquals("1794593005789", headers["x-record-stop"])
        assertEquals("1794593005812", headers["x-sent-at"])
        assertEquals("4.8", headers["x-seconds"])
        // digits only: no separators, no exponent, no decimal point
        for (name in listOf("x-record-start", "x-record-stop", "x-sent-at")) {
            assertTrue(name, headers.getValue(name).all { it.isDigit() })
        }
    }

    @Test
    fun `an unknown mark is not sent at all, never as zero` () {
        val onlyStop = VoiceUpload.headers(1.0, VoiceUpload.Times(recordStop = 1_794_593_005_789))
        assertEquals(setOf("x-seconds", "x-record-stop"), onlyStop.keys)
        assertFalse(onlyStop.containsKey("x-record-start"))
        assertFalse(onlyStop.containsKey("x-sent-at"))

        val none = VoiceUpload.headers(2.5)
        assertEquals(setOf("x-seconds"), none.keys)
        assertEquals("2.5", none["x-seconds"])
    }

    @Test
    fun `a zero mark is the caller's business and would be refused there` () {
        // the screen passes null for "not known" (a press that never opened the microphone);
        // this test pins the contract that a value, once given, is sent verbatim
        val headers = VoiceUpload.headers(0.0, VoiceUpload.Times(recordStart = 0))
        assertEquals("0", headers["x-record-start"])
        assertEquals("0.0", headers["x-seconds"])
    }

    @Test
    fun `the epoch formatter keeps digits and nothing else` () {
        assertEquals("1794593000123", VoiceUpload.epoch(1_794_593_000_123))
        assertEquals("0", VoiceUpload.epoch(0))
    }


    @Test
    fun `the synthesis mode always travels, and only two values mean anything` () {
        assertEquals("device", VoiceUpload.synthOf(null))
        assertEquals("device", VoiceUpload.synthOf(""))
        assertEquals("device", VoiceUpload.synthOf("nonsense"))
        assertEquals("host", VoiceUpload.synthOf("host"))
        assertEquals("host", VoiceUpload.synthOf(" HOST "))
        assertTrue(VoiceUpload.url("https://dsh.example", "x", "host").endsWith("&synth=host"))
        assertTrue(VoiceUpload.url("https://dsh.example", "x").endsWith("&synth=device"))
    }

    @Test
    fun `an accepted take brings back the host's facts` () {
        val result = VoiceUpload.interpret(
            202,
            JSONObject()
                .put("accepted", true)
                .put("id", "abc")
                .put("path", "/var/lib/dsh/voice/abc.wav")
                .put("bytes", 154_286)
                .put("seconds", 4.8)
                .toString(),
        )
        assertTrue(result is VoiceUpload.Result.Accepted)
        result as VoiceUpload.Result.Accepted
        assertEquals("/var/lib/dsh/voice/abc.wav", result.path)
        assertEquals(154_286L, result.bytes)
        assertEquals(4.8, result.seconds!!, 0.0001)
    }

    @Test
    fun `an empty 2xx is still acceptance` () {
        assertTrue(VoiceUpload.interpret(202, "") is VoiceUpload.Result.Accepted)
        val bare = VoiceUpload.interpret(200, null) as VoiceUpload.Result.Accepted
        assertNull(bare.path)
        assertNull(bare.bytes)
    }

    @Test
    fun `a refusal keeps the server's words` () {
        val result = VoiceUpload.interpret(413, "{\"code\":\"too_large\",\"message\":\"файл слишком большой\"}")
        assertTrue(result is VoiceUpload.Result.Failed)
        assertEquals("файл слишком большой (too_large)", (result as VoiceUpload.Result.Failed).reason)
    }

    @Test
    fun `a refusal with an unreadable body still says something` () {
        val result = VoiceUpload.interpret(502, "<html>bad gateway</html>")
        assertEquals("HTTP 502", (result as VoiceUpload.Result.Failed).reason)
    }

    @Test
    fun `a take that did not land stays on disk and in the outbox` () {
        // the screen parks the file for the older route instead of deleting it
        val root = Files.createTempDirectory("dshnc-voice-root").toFile()
        val privateDir = Files.createTempDirectory("dshnc-voice-private").toFile()
        val take = File(privateDir, "voice-1.wav").apply {
            writeBytes(ByteArray(4096) { 1 })
        }
        try {
            val directory = VoiceFiles.directory(listOf(root.absolutePath), privateDir)
            val target = File(directory, take.name)
            take.copyTo(target, overwrite = true)
            take.delete()
            VoiceOutbox.offer(VoiceOutbox.Recording(target, 2.5, "ptt"))
            val parked = VoiceOutbox.takeNow()
            assertNotNull(parked)
            assertTrue(parked!!.file.isFile)
            assertTrue(parked.file.absolutePath.startsWith(root.absolutePath))
        } finally {
            VoiceOutbox.clear()
            root.deleteRecursively()
            privateDir.deleteRecursively()
        }
    }

    @Test
    fun `the request is a POST with the token, the length and the duration` () {
        // the body type and the headers the transport will put on the wire
        val file = Files.createTempFile("dshnc-take", ".wav").toFile().apply {
            writeBytes(ByteArray(3200) { 0 })
        }
        try {
            val request = Request.Builder()
                .url(VoiceUpload.url("https://dsh.example", "id-1"))
                .header("authorization", "Bearer token")
                .apply { VoiceUpload.headers(3.2).forEach { (k, v) -> header(k, v) } }
                .post(file.asRequestBody("audio/wav".toMediaType()))
                .build()
            assertEquals("POST", request.method)
            assertEquals("Bearer token", request.header("authorization"))
            assertEquals("3.2", request.header("x-seconds"))
            assertTrue(request.url.toString().contains("type=voice"))
            assertEquals(file.length(), request.body!!.contentLength())
        } finally {
            file.delete()
        }
    }
}
