package com.adsamcik.tracker.app.debug

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.debug.DummyDataSeeder
import kotlinx.coroutines.launch

/**
 * Debug-only composable that provides a button to seed dummy tracking data.
 * This file exists only in the debug source set; the release variant provides a no-op stub.
 */
@Composable
fun SeedDataSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var seedStatus by remember { mutableStateOf<String?>(null) }
    var isSeeding by remember { mutableStateOf(false) }

    Button(
        onClick = {
            isSeeding = true
            seedStatus = null
            scope.launch {
                val result = DummyDataSeeder.seed(ctx)
                seedStatus = if (result.inserted) "✅ Seeded 3 sessions" else "❌ ${result.reason}"
                isSeeding = false
            }
        },
        enabled = !isSeeding,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("debug_seed_data_button")
    ) {
        if (isSeeding) {
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp).height(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text("Seed Dummy Data (3 NYC sessions)")
    }
    if (seedStatus != null) {
        Text(
            text = seedStatus!!,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
}
