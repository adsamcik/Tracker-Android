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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.debug.DummyDataSeeder
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog
import kotlinx.coroutines.launch
import com.adsamcik.tracker.shared.base.R as BaseR

/**
 * Dev-build composable that provides a button to seed dummy tracking data.
 * The release variants provide no-op stubs.
 */
@Composable
fun SeedDataSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var seedStatus by remember { mutableStateOf<String?>(null) }
    var isSeeding by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }

    fun startSeeding() {
        isSeeding = true
        seedStatus = null
        scope.launch {
            val result = DummyDataSeeder.seed(ctx)
            seedStatus = if (result.inserted) "✅ Seeded 3 sessions" else "❌ ${result.reason}"
            isSeeding = false
        }
    }

    Button(
        onClick = { showConfirmation = true },
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

    ConfirmDialog(
        visible = showConfirmation,
        title = stringResource(R.string.settings_debug_seed_data_confirm_title),
        message = stringResource(R.string.settings_debug_seed_data_confirm_message),
        confirmLabel = stringResource(BaseR.string.generic_yes),
        dismissLabel = stringResource(BaseR.string.generic_no),
        onConfirm = ::startSeeding,
        onDismiss = { showConfirmation = false },
    )
}
