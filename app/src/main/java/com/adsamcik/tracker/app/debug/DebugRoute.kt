package com.adsamcik.tracker.app.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog

/**
 * Compose wrapper for a subset of Debug actions. This is the first step in replacing the old
 * PreferenceFragment-based DebugPage. Additional actions (dummy data seeding, crash tools, etc.)
 * will be migrated incrementally.
 */
@Composable
fun DebugRoute() {
    val ctx = LocalContext.current
    val clearDialog = remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = ctx.getString(R.string.settings_debug_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("debug_route_title")
            )

            Button(
                onClick = { clearDialog.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button }
                    .testTag("debug_clear_preferences_button")
            ) {
                Text(ctx.getString(R.string.settings_clear_preferences_title))
            }
        }
    }

    ConfirmDialog(
        visible = clearDialog.value,
        title = ctx.getString(R.string.settings_clear_preferences_title),
        message = ctx.getString(R.string.settings_clear_preferences_message),
        confirmLabel = ctx.getString(BaseR.string.generic_yes),
        dismissLabel = ctx.getString(BaseR.string.generic_no),
        onConfirm = { clearPreferences(ctx) },
        onDismiss = { clearDialog.value = false },
    )
}

private fun clearPreferences(context: Context) {
    Preferences.getPref(context).edit { clear() }
}
