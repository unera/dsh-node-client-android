package ru.uvw.dshnc.logic

/**
 * The WAV container a recording travels in.
 *
 * The host accepts one format for speech: 16 kHz, mono, 16-bit PCM. A recorder that produced
 * anything else would be refused on the other side, so the format lives here as constants and
 * the header is built by hand — `MediaRecorder` writes compressed containers, and a decoder on
 * the host is not something worth adding for a voice note.
 */
object WavFile {
    /** What the host's recognizers accept, and what the microphone is asked for. */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16

    /** Bytes one second of this format takes: the recorder uses it for its ceiling. */
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS / 8

    /**
     * A canonical 44-byte RIFF/WAVE header.
     *
     * @param dataBytes how many PCM bytes follow the header.
     * @returns the header, ready to be written before the samples.
     */
    fun header(
        dataBytes: Int,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bits: Int = BITS,
    ): ByteArray {
        require(dataBytes >= 0) { "dataBytes must not be negative" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(bits == 8 || bits == 16 || bits == 24 || bits == 32) { "unsupported bit depth: $bits" }
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(44)
        fun ascii(offset: Int, value: String) {
            value.forEachIndexed { index, character -> out[offset + index] = character.code.toByte() }
        }
        fun le32(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun le16(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16) // PCM chunk size
        le16(20, 1) // PCM
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, bits)
        ascii(36, "data")
        le32(40, dataBytes)
        return out
    }

    /**
     * How long a recording of this size lasts.
     *
     * @param dataBytes PCM bytes without the header.
     * @returns seconds as the host will measure them.
     */
    fun seconds(dataBytes: Int): Double = dataBytes.toDouble() / BYTES_PER_SECOND
}
