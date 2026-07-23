package com.mustafanazeer.baselinems.signals

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Writes a 16 bit signed mono PCM buffer to disk as a gzip compressed WAV file (a standard
 * RIFF/WAVE header followed by little endian PCM data, the whole stream then gzipped).
 *
 * This is the ADR 0006 Section 7 opt in retention writer. The destination contract is
 * `${filesDir}/audio_traces/<sessionId>/VOICE.wav.gz`, parallel to the gait
 * `sensor_traces/<sessionId>/GAIT.csv.gz` path that `RawSensorWriter` produces. The writer is
 * pure JVM (no Android imports) so the header layout and the gzip round trip are unit testable
 * on the JVM.
 *
 * No logging contract (ADR 0006 Section 7, requirement SE4): this file never logs a raw
 * sample, the PCM buffer, or any per sample content. It does not import the Android `Log` API.
 */
object VoiceWavWriter {

    private const val BYTES_PER_SAMPLE: Int = 2
    private const val NUM_CHANNELS: Int = 1
    private const val BITS_PER_SAMPLE: Int = 16
    private const val PCM_FORMAT_TAG: Int = 1
    private const val FMT_SUBCHUNK_SIZE: Int = 16
    private const val HEADER_BYTES: Int = 44

    /**
     * Writes [pcm] to [target] as a gzipped WAV. Parent directories are created lazily, mirroring
     * `RawSensorWriter`. On any I/O failure the partially written file is removed and the original
     * exception is rethrown so the caller can degrade to the discard path without leaving a
     * corrupt `.wav.gz` on disk.
     */
    fun writeGzippedWav(pcm: ShortArray, sampleRateHz: Int, target: File) {
        target.parentFile?.mkdirs()
        val fileOut = FileOutputStream(target)
        try {
            BufferedOutputStream(fileOut).use { buffered ->
                GZIPOutputStream(buffered).use { gz ->
                    writeWav(gz, pcm, sampleRateHz)
                }
            }
        } catch (failure: Throwable) {
            runCatching { target.delete() }
            throw failure
        }
    }

    private fun writeWav(out: OutputStream, pcm: ShortArray, sampleRateHz: Int) {
        val dataBytes = pcm.size * BYTES_PER_SAMPLE
        val byteRate = sampleRateHz * NUM_CHANNELS * BYTES_PER_SAMPLE
        val blockAlign = NUM_CHANNELS * BYTES_PER_SAMPLE

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(FMT_SUBCHUNK_SIZE)
        header.putShort(PCM_FORMAT_TAG.toShort())
        header.putShort(NUM_CHANNELS.toShort())
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        if (pcm.isEmpty()) return
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) data.putShort(sample)
        out.write(data.array())
    }
}
