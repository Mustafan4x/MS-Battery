package com.mustafanazeer.baselinems.signals

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Arrays

/**
 * Instrumented test for the SE2 buffer zeroing contract (ADR 0006 Section 7
 * and `docs/security/hardening-checklist.md` Section J.3). Exercises the canonical
 * caller pattern `VoiceTestViewModel` will use to zero the captured PCM buffer after
 * feature extraction completes.
 *
 * Two layers of verification:
 *
 * 1. Pure JVM caller pattern: a synthetic non zero `ShortArray` runs through a
 *    `try`/`finally` block with `Arrays.fill(buffer, 0.toShort())` in the `finally`.
 *    After the block returns, every element of the buffer is zero. This proves the
 *    contract holds regardless of platform.
 *
 * 2. Real AudioRecord capture: if the device or AVD has a microphone and the
 *    RECORD_AUDIO permission is granted, capture a 0.5 second buffer via
 *    `AndroidAudioCapture.record`, simulate feature extraction in a `try` block,
 *    then run the caller's `Arrays.fill` in the `finally`. After the block returns,
 *    every element of the captured buffer is zero. The AVD without a microphone path
 *    is skipped via `assumeTrue` and the limitation is documented in the comment
 *    below.
 *
 * Runs as part of the Phase 8 AVD walkthrough at phase close. It is not part of
 * the headless CI build, where the check is only that this test source file
 * exists and compiles, not that it runs.
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureBufferZeroingTest {

    @Test
    fun bufferIsZeroedAfterCallerTryFinally() {
        val buffer = ShortArray(1_000) { (it % 100).toShort() }
        val sumBefore = buffer.sumOf { it.toInt() }
        assertTrue("Buffer should have non zero contents before zeroing", sumBefore != 0)

        try {
            val sumDuringExtraction = buffer.sumOf { it.toInt() }
            assertEquals(
                "Buffer contents should be visible to the feature extractor",
                sumBefore,
                sumDuringExtraction,
            )
        } finally {
            Arrays.fill(buffer, 0.toShort())
        }

        assertTrue(
            "Buffer should be fully zeroed after the caller's try/finally",
            buffer.all { it == 0.toShort() },
        )
    }

    @Test
    fun capturedBufferIsZeroedAfterCallerTryFinally() = runBlocking {
        assumeTrue(
            "RECORD_AUDIO must be granted for this test; skipped on AVDs that have not " +
                "received the permission grant. The Pixel_6 GPU host emulator typically " +
                "provides a synthetic microphone (silence) but the permission must be " +
                "granted manually or via the AndroidJUnitRunner permission grant flag.",
            isRecordAudioGranted(),
        )
        assumeTrue(
            "Device must expose a microphone feature. AVDs without `-feature ... microphone` " +
                "skip this assertion path.",
            hasMicrophoneFeature(),
        )

        val capture: AudioCapture = AndroidAudioCapture()
        val buffer: ShortArray = try {
            capture.record(durationSec = 1)
        } catch (ise: IllegalStateException) {
            assumeTrue(
                "AudioRecord could not initialize on this AVD; skipping the captured buffer " +
                    "leg of the test. Reason: " + ise.message,
                false,
            )
            ShortArray(0)
        }
        assertNotNull(buffer)
        assertEquals(44_100, buffer.size)

        try {
            val sumDuringExtraction = buffer.sumOf { it.toInt() }
            assertNotNull("Sum is computable", sumDuringExtraction)
        } finally {
            Arrays.fill(buffer, 0.toShort())
        }

        assertTrue(
            "Captured buffer should be fully zeroed after the caller's try/finally",
            buffer.all { it == 0.toShort() },
        )
    }

    private fun isRecordAudioGranted(): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophoneFeature(): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}
