package com.mustafanazeer.baselinems.signals

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Signals layer contract for the Voice Reading Test (Phase 8). Returns a 16 bit signed PCM
 * buffer of length `sampleRateHz * durationSec` captured from the device microphone.
 *
 * @apiNote Caller is responsible for zeroing the returned ShortArray after feature
 * extraction completes per ADR 0006 Section 7 (requirement SE2). The
 * canonical pattern is a `try`/`finally` block around feature extraction with
 * `java.util.Arrays.fill(buffer, 0.toShort())` in the `finally`. See `VoiceTestViewModel`
 * for the implementation. The wrapper itself does not zero the buffer it returns because
 * the caller owns the buffer's lifetime: the caller passes the buffer to the feature
 * extractor (a separate coroutine), holds the reference through extraction, and is the
 * only path that knows when extraction has completed (success, throw, or cancel).
 */
interface AudioCapture {
    suspend fun record(durationSec: Int): ShortArray
}

/**
 * `AudioRecord` backed `AudioCapture`. Captures 44.1 kHz mono 16 bit PCM via
 * `MediaRecorder.AudioSource.MIC` and returns the full duration as a single `ShortArray`.
 *
 * The 44.1 kHz sample rate is locked by ADR 0006 Section 3: it is the only sampling rate
 * the Android Compatibility Definition Document guarantees across all devices, and the
 * acoustic feature algorithms in `dsp/voice/` (jitter, shimmer, HNR per Boersma 1993)
 * assume a consistent rate across captures. Passing any other rate to the constructor
 * throws `IllegalArgumentException` at construction time.
 *
 * `MediaRecorder.AudioSource.MIC` is chosen over `VOICE_RECOGNITION` per ADR 0006
 * Section 3: `VOICE_RECOGNITION` applies platform level automatic gain control, noise
 * suppression, and echo cancellation on many devices, which alters the per cycle
 * amplitude distribution and the autocorrelation structure that jitter, shimmer, and HNR
 * depend on. `MIC` is the closest the platform exposes to a raw acoustic signal.
 *
 * Lifecycle contract per ADR 0006 Section 3 (requirement SE3): `AudioRecord.stop()`
 * and `AudioRecord.release()` fire synchronously inside a `try`/`finally` pair on every
 * termination path (normal completion, mid capture coroutine cancellation, exception in
 * the read loop). The `release()` call frees the native AudioRecord buffers immediately;
 * nothing is deferred to the garbage collector.
 *
 * No logging contract per ADR 0006 Section 7 (requirement SE4): the implementation
 * never logs a raw sample, the capture buffer, or any per chunk content. The grep gate
 * for `Log\..*\b(buffer|pcm|samples|audio)\b` and `Log\..*\.contentToString\(\)` returns
 * zero matches in this file.
 */
class AndroidAudioCapture internal constructor(
    private val sampleRateHz: Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val audioRecordFactory: AudioRecordFactory,
) : AudioCapture {

    constructor(sampleRateHz: Int = SAMPLE_RATE_HZ) : this(
        sampleRateHz = sampleRateHz,
        ioDispatcher = Dispatchers.IO,
        audioRecordFactory = DefaultAudioRecordFactory,
    )

    init {
        require(sampleRateHz == SAMPLE_RATE_HZ) {
            "AudioCapture requires sampleRateHz=$SAMPLE_RATE_HZ; got $sampleRateHz. " +
                "44.1 kHz is the only rate guaranteed across all Android devices per the CDD."
        }
    }

    override suspend fun record(durationSec: Int): ShortArray {
        require(durationSec > 0) { "durationSec must be positive; got $durationSec" }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes == AudioRecord.ERROR_BAD_VALUE || minBufferBytes == AudioRecord.ERROR) {
            throw IllegalStateException(
                "AudioRecord.getMinBufferSize returned $minBufferBytes; " +
                    "device does not support 44.1 kHz mono 16 bit PCM capture."
            )
        }
        val internalBufferBytes = minBufferBytes * BUFFER_OVERSIZE_FACTOR
        val internalBufferShorts = internalBufferBytes / BYTES_PER_SHORT

        val totalShorts = sampleRateHz * durationSec
        val captureBuffer = ShortArray(totalShorts)

        return withContext(ioDispatcher) {
            val audioRecord = audioRecordFactory.create(
                audioSource = MediaRecorder.AudioSource.MIC,
                sampleRateHz = sampleRateHz,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes = internalBufferBytes,
            )
            try {
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException(
                        "AudioRecord failed to initialize (state=${audioRecord.state})."
                    )
                }
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(
                        "AudioRecord failed to start (recordingState=${audioRecord.recordingState})."
                    )
                }

                var offset = 0
                val chunkSize = internalBufferShorts.coerceAtLeast(MIN_CHUNK_SHORTS)
                while (offset < totalShorts && currentCoroutineContext().isActive) {
                    val remaining = totalShorts - offset
                    val toRead = if (remaining < chunkSize) remaining else chunkSize
                    val read = audioRecord.read(captureBuffer, offset, toRead)
                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR
                    ) {
                        throw IllegalStateException("AudioRecord.read returned error code $read.")
                    }
                    if (read <= 0) break
                    offset += read
                }

                audioRecord.stop()
            } catch (ce: CancellationException) {
                runCatching { audioRecord.stop() }
                throw ce
            } finally {
                audioRecord.release()
            }
            captureBuffer
        }
    }

    /**
     * Indirection over the `AudioRecord` constructor so unit tests can substitute a fake
     * without spinning up the native audio HAL. Production callers receive
     * `DefaultAudioRecordFactory`.
     */
    internal interface AudioRecordFactory {
        fun create(
            audioSource: Int,
            sampleRateHz: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSizeInBytes: Int,
        ): AudioRecord
    }

    internal object DefaultAudioRecordFactory : AudioRecordFactory {
        @Suppress("MissingPermission")
        override fun create(
            audioSource: Int,
            sampleRateHz: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSizeInBytes: Int,
        ): AudioRecord = AudioRecord(
            audioSource,
            sampleRateHz,
            channelConfig,
            audioFormat,
            bufferSizeInBytes,
        )
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 44_100
        private const val BUFFER_OVERSIZE_FACTOR: Int = 2
        private const val BYTES_PER_SHORT: Int = 2
        private const val MIN_CHUNK_SHORTS: Int = 1024
    }
}
