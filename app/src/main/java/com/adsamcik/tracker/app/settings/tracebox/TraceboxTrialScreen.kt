package com.adsamcik.tracker.app.settings.tracebox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem

@Composable
fun TraceboxTrialScreen(
    viewModel: TraceboxTrialViewModel = hiltViewModel(),
    isAvailable: Boolean = BuildConfig.TRACEBOX_TRIAL_AVAILABLE,
) {
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    TraceboxTrialContent(
        isEnabled = isEnabled,
        isAvailable = isAvailable,
        onEnabledChange = viewModel::setEnabled,
    )
}

@Composable
internal fun TraceboxTrialContent(
    isEnabled: Boolean,
    isAvailable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 16.dp + navigationBarPadding),
    ) {
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_control_header),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_tracebox_toggle_title),
                    subtitle = stringResource(
                        if (!isAvailable) {
                            R.string.settings_tracebox_toggle_summary_unavailable
                        } else if (isEnabled) {
                            R.string.settings_tracebox_toggle_summary_on
                        } else {
                            R.string.settings_tracebox_toggle_summary_off
                        },
                    ),
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = isAvailable,
                )
            }
        }

        item {
            TraceboxExplanation(
                title = stringResource(R.string.settings_tracebox_records_header),
                body = stringResource(R.string.settings_tracebox_records_body),
            )
        }

        item {
            TraceboxExplanation(
                title = stringResource(R.string.settings_tracebox_privacy_header),
                body = stringResource(R.string.settings_tracebox_privacy_body),
            )
        }

        item {
            TraceboxExplanation(
                title = stringResource(R.string.settings_tracebox_limits_header),
                body = stringResource(R.string.settings_tracebox_limits_body),
            )
        }
    }
}

@Composable
private fun TraceboxExplanation(
    title: String,
    body: String,
) {
    SettingsGroupCard(
        title = title,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
