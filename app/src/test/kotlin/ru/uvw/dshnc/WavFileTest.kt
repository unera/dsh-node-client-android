package ru.uvw.dshnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.WavFile
import ru.uvw.dshnc.logic.WavFile as Wav

/**
 * The container the host reads: a header built by hand has to say exactly what the bytes are.
 *
 * The sizes are checked field by field rather than "header looks plausible": a wrong
 * `nBlockAlign` is the classic way to make a recognizer refuse a recording that sounds fine.
 */
class WavFileTest {

    private fun le32 (bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le16 (bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun ascii (bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    @Test
    fun `the header names a 16 kHz mono 16-bit pcm stream` () {
        val header = Wav.header(0)
        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16, le32(header, 16))
        assertEquals(1, le16(header, 20)) // PCM
        assertEquals(1, le16(header, 22)) // mono
        assertEquals(16_000, le32(header, 24))
        assertEquals(32_000, le32(header, 28)) // bytes per second
        assertEquals(2, le16(header, 32)) // block align
        assertEquals(16, le16(header, 34)) // bits
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(0, le32(header, 40))
        assertEquals(36, le32(header, 4))
    }

    @Test
    fun `the sizes follow the payload` () {
        val header = Wav.header(32_000)
        assertEquals(32_000, le32(header, 40))
        assertEquals(36 + 32_000, le32(header, 4))
        assertEquals(44 + 32_000, header.size + 32_000)
    }

    @Test
    fun `one second of speech is two bytes per sample` () {
        assertEquals(32_000, WavFile.BYTES_PER_SECOND)
        assertEquals(1.0, WavFile.seconds(32_000), 0.0001)
        assertEquals(0.5, WavFile.seconds(16_000), 0.0001)
    }

    @Test
    fun `a negative payload and a silly depth are refused` () {
        assertTrue(runCatching { Wav.header(-2) }.isFailure)
        assertTrue(runCatching { Wav.header(10, bits = 12) }.isFailure)
        assertTrue(runCatching { Wav.header(10, sampleRate = 0) }.isFailure)
    }

    @Test
    fun `the two habits of the button survive a round trip` () {
        assertEquals(VoiceMode.PTT, VoiceMode.of("ptt"))
        assertEquals(VoiceMode.TOGGLE, VoiceMode.of("toggle"))
        assertEquals(VoiceMode.TOGGLE, VoiceMode.of(" TOGGLE "))
        assertEquals(VoiceMode.PTT, VoiceMode.of(null))
        assertEquals(VoiceMode.PTT, VoiceMode.of("nonsense"))
        assertEquals(VoiceMode.PTT, VoiceMode.parse("nonsense") ?: VoiceMode.PTT)
        assertEquals(null, VoiceMode.parse("nonsense"))
    }
}

/**
 * The take has to land where the host's `files.copyFromDevice` may read it, which is inside one
 * of the directories the owner allowed.
 */
class VoiceFilesTest {
    @Test
    fun `the first writable root wins and gets the DSH subdirectory` () {
        val root = java.nio.file.Files.createTempDirectory("dshnc-root").toFile()
        val other = java.nio.file.Files.createTempDirectory("dshnc-other").toFile()
        val privateDir = java.nio.file.Files.createTempDirectory("dshnc-private").toFile()
        try {
            val directory = ru.uvw.dshnc.logic.VoiceFiles.directory(
                listOf("/nonexistent/path", root.absolutePath, other.absolutePath),
                privateDir,
            )
            assertEquals(java.io.File(root, ru.uvw.dshnc.logic.VoiceFiles.SUBDIR).absolutePath, directory.absolutePath)
            assertTrue(directory.isDirectory)
        } finally {
            root.deleteRecursively(); other.deleteRecursively(); privateDir.deleteRecursively()
        }
    }

    @Test
    fun `no usable root falls back to the private directory` () {
        val privateDir = java.nio.file.Files.createTempDirectory("dshnc-private").toFile()
        try {
            val directory = ru.uvw.dshnc.logic.VoiceFiles.directory(listOf("/nonexistent/one"), privateDir)
            assertEquals(java.io.File(privateDir, "voice").absolutePath, directory.absolutePath)
            assertTrue(directory.isDirectory)
        } finally {
            privateDir.deleteRecursively()
        }
    }

    @Test
    fun `a take is named so that a file manager sorts it by time` () {
        val name = ru.uvw.dshnc.logic.VoiceFiles.name(1_700_000_000_000)
        assertEquals("voice-1700000000000.wav", name)
    }
}
