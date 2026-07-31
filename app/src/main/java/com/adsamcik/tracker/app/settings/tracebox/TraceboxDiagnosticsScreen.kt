package com.adsamcik.tracker.app.settings.tracebox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsState
import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHealth

@Composable
fun TraceboxDiagnosticsScreen(
    viewModel: TraceboxDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    val reviewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onApprovalResult(result.resultCode, result.data)
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onSaveDestination(result.data?.data)
    }
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onShareReturned()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TraceboxUiEffect.Review -> reviewLauncher.launch(effect.intent)
                is TraceboxUiEffect.Save -> saveLauncher.launch(effect.intent)
                is TraceboxUiEffect.Share -> {
                    shareLauncher.launch(effect.intent)
                    viewModel.onShareChooserOpened()
                }
            }
        }
    }

    TraceboxDiagnosticsContent(
        state = state,
        message = message,
        onEnabledChange = viewModel::setEnabled,
        onReviewPackage = viewModel::preparePackage,
        onSavePackage = viewModel::requestSaveDestination,
        onSharePackage = viewModel::sharePackage,
        onDeleteAll = { showDeleteConfirmation = true },
    )

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.settings_tracebox_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_tracebox_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteAllData()
                    },
                ) {
                    Text(stringResource(R.string.settings_tracebox_delete_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun TraceboxDiagnosticsContent(
    state: TraceboxDiagnosticsState,
    message: TraceboxUiMessage?,
    onEnabledChange: (Boolean) -> Unit,
    onReviewPackage: () -> Unit,
    onSavePackage: () -> Unit,
    onSharePackage: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val controlsEnabled =
        !state.operationInProgress && state.health != TraceboxHealth.CLOSED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 16.dp + navigationBarPadding),
    ) {
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_status_header),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_tracebox_toggle_title),
                    subtitle = stringResource(
                        if (state.enabled) {
                            R.string.settings_tracebox_toggle_summary_on
                        } else {
                            R.string.settings_tracebox_toggle_summary_off
                        },
                    ),
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = controlsEnabled,
                )
                TraceboxStatusLine(
                    label = stringResource(R.string.settings_tracebox_readiness_label),
                    value = readinessText(state.readiness),
                )
                TraceboxStatusLine(
                    label = stringResource(R.string.settings_tracebox_health_label),
                    value = healthText(state.health),
                )
            }
        }

        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_package_header),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tracebox_package_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onReviewPackage,
                        enabled = controlsEnabled && state.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_tracebox_package_review_action))
                    }
                    OutlinedButton(
                        onClick = onSavePackage,
                        enabled = controlsEnabled && state.packageReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_tracebox_package_save_action))
                    }
                    OutlinedButton(
                        onClick = onSharePackage,
                        enabled = controlsEnabled && state.packageReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_tracebox_package_share_action))
                    }
                    Text(
                        text = stringResource(R.string.settings_tracebox_share_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_data_header),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tracebox_data_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onDeleteAll,
                        enabled = controlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_tracebox_delete_action))
                    }
                }
            }
        }

        if (message != null) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.settings_tracebox_result_header),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        text = messageText(message),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    }
}

@Composable
private fun TraceboxStatusLine(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun readinessText(readiness: Readiness): String = stringResource(
    when (readiness) {
        Readiness.VOLATILE_CAPTURE -> R.string.settings_tracebox_readiness_volatile
        Readiness.DURABLE -> R.string.settings_tracebox_readiness_durable
        Readiness.DEGRADED -> R.string.settings_tracebox_readiness_degraded
        Readiness.CLOSED -> R.string.settings_tracebox_readiness_closed
    },
)

@Composable
private fun healthText(health: TraceboxHealth): String = stringResource(
    when (health) {
        TraceboxHealth.DISABLED -> R.string.settings_tracebox_health_disabled
        TraceboxHealth.INITIALIZING -> R.string.settings_tracebox_health_initializing
        TraceboxHealth.READY -> R.string.settings_tracebox_health_ready
        TraceboxHealth.DEGRADED -> R.string.settings_tracebox_health_degraded
        TraceboxHealth.DELETING -> R.string.settings_tracebox_health_deleting
        TraceboxHealth.CLOSED -> R.string.settings_tracebox_health_closed
    },
)

@Composable
private fun messageText(message: TraceboxUiMessage): String = when (message) {
    TraceboxUiMessage.DiagnosticsEnabled ->
        stringResource(R.string.settings_tracebox_result_enabled)

    TraceboxUiMessage.DiagnosticsDisabled ->
        stringResource(R.string.settings_tracebox_result_disabled)

    TraceboxUiMessage.PolicyRestricted ->
        stringResource(R.string.settings_tracebox_result_policy_restricted)

    TraceboxUiMessage.PolicyPartial ->
        stringResource(R.string.settings_tracebox_result_policy_partial)

    TraceboxUiMessage.OperationFailed ->
        stringResource(R.string.settings_tracebox_result_failed)

    TraceboxUiMessage.DeleteComplete ->
        stringResource(R.string.settings_tracebox_result_delete_complete)

    TraceboxUiMessage.DeletePending ->
        stringResource(R.string.settings_tracebox_result_delete_pending)

    TraceboxUiMessage.DeleteRejected ->
        stringResource(R.string.settings_tracebox_result_delete_rejected)

    is TraceboxUiMessage.ReviewReady ->
        stringResource(
            R.string.settings_tracebox_result_review_ready,
            message.valueCount,
            message.bytes,
        )

    TraceboxUiMessage.PackageCreated ->
        stringResource(R.string.settings_tracebox_result_package_created)

    TraceboxUiMessage.ApprovalCancelled ->
        stringResource(R.string.settings_tracebox_result_approval_cancelled)

    TraceboxUiMessage.PackageRejected ->
        stringResource(R.string.settings_tracebox_result_package_rejected)

    TraceboxUiMessage.PackageNotReady ->
        stringResource(R.string.settings_tracebox_result_package_not_ready)

    is TraceboxUiMessage.SaveComplete ->
        stringResource(R.string.settings_tracebox_result_save_complete, message.bytes)

    is TraceboxUiMessage.SavePartial ->
        stringResource(
            if (message.cancelled) {
                R.string.settings_tracebox_result_save_partial_cancelled
            } else {
                R.string.settings_tracebox_result_save_partial
            },
            message.bytes,
        )

    TraceboxUiMessage.SaveFailed ->
        stringResource(R.string.settings_tracebox_result_save_failed)

    TraceboxUiMessage.SaveCancelled ->
        stringResource(R.string.settings_tracebox_result_save_cancelled)

    TraceboxUiMessage.ShareChooserOpened ->
        stringResource(R.string.settings_tracebox_result_share_opened)

    TraceboxUiMessage.ShareDeliveryUnknown ->
        stringResource(R.string.settings_tracebox_result_share_unknown)
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
        Text(
            text = body,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
