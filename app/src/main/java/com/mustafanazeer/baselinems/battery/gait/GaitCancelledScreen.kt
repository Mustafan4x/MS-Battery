package com.mustafanazeer.baselinems.battery.gait

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cancelled screen for the gait test. Mirrors the Phase 6 `VisionCancelledScreen` and Phase 8
 * `VoiceCancelledScreen` shape. Renders no per feature line; per the Phase 6
 * accessibility review (Finding A) the cancelled screen must not say "you walked N
 * steps" or any other count statement.
 */
@Composable
fun GaitCancelledScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Test cancelled",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(text = "Test cancelled. Nothing was saved.")
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Back to home")
        }
    }
}
