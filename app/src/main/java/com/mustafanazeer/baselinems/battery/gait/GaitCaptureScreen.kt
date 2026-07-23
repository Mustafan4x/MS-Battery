package com.mustafanazeer.baselinems.battery.gait

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Capture screen for the gait test. Shows a circular progress indicator wrapping a "Walking"
 * label, the elapsed and total seconds, and a Cancel button. The user is not expected to be
 * looking at this screen during the walk (the phone is in a front pocket), so the visual is
 * deliberately minimal: no animated map, no decorative motion.
 *
 * In pocket cancel is provided by a 1.5 second long press on volume down (Phase 11,
 * per the Phase 4 accessibility review, Finding 1). The on screen Cancel button stays so users who pull
 * the phone out can still reach a familiar control.
 */
private const val TOTAL_DURATION_MILLIS = 30_000
private const val HOLD_PROGRESS_TICK_MS: Long = 30L

@Composable
fun GaitCaptureScreen(
    state: GaitTestState.Capturing,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSeconds = TOTAL_DURATION_MILLIS / 1000
    val elapsedSeconds = (state.progressMillis / 1000).coerceIn(0, totalSeconds)
    val progress = (state.progressMillis.toFloat() / TOTAL_DURATION_MILLIS).coerceIn(0f, 1f)

    val bus = GaitVolumeCancelBus
    val holdStartedAt by bus.holdStartedAt.collectAsState()
    val cancelRequested by bus.cancelRequested.collectAsState()
    val initialCancelCount = remember { cancelRequested }

    LaunchedEffect(Unit) { bus.setActive(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { bus.setActive(false) }
    }

    LaunchedEffect(cancelRequested) {
        if (cancelRequested > initialCancelCount) onCancel()
    }

    var holdProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holdStartedAt) {
        val started = holdStartedAt
        if (started == null) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        while (holdStartedAt == started) {
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = (now - started).toFloat()
            holdProgress = (elapsed / GaitVolumeCancelBus.HOLD_THRESHOLD_MS.toFloat())
                .coerceIn(0f, 1f)
            if (holdProgress >= 1f) break
            delay(HOLD_PROGRESS_TICK_MS)
        }
    }

    GaitCaptureLayout(
        progress = progress,
        elapsedSeconds = elapsedSeconds,
        totalSeconds = totalSeconds,
        holdProgress = if (holdStartedAt != null) holdProgress else 0f,
        showHoldHint = holdStartedAt != null,
        onCancel = onCancel,
        modifier = modifier
    )
}

@Composable
internal fun GaitCaptureLayout(
    progress: Float,
    elapsedSeconds: Int,
    totalSeconds: Int,
    holdProgress: Float,
    showHoldHint: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(160.dp),
                strokeWidth = 8.dp
            )
            Text(
                text = "Walking",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Text(
            text = "$elapsedSeconds of $totalSeconds seconds",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        if (showHoldHint) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Hold volume down to cancel",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                LinearProgressIndicator(
                    progress = { holdProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(
                text = "In pocket cancel: hold volume down for 1.5 seconds.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier)
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 64.dp)
        ) {
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
