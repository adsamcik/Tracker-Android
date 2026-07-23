package com.adsamcik.tracker.app.settings.tracebox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.tracebox.TraceboxAvailability
import com.adsamcik.tracker.app.tracebox.TraceboxExportState
import com.adsamcik.tracker.app.tracebox.TraceboxUiState

@Composable
fun TraceboxSettingsScreen(
    viewModel: TraceboxSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val approvalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.acceptApprovalResult(result.data)
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.let(viewModel::save)
    }
    TraceboxSettingsContent(
        state = state,
        onEnabledChange = viewModel::setEnabled,
        onPrepare = viewModel::prepareStandardPackage,
        onReview = {
            viewModel.approvalIntent()?.let(approvalLauncher::launch)
        },
        onShare = {
            viewModel.shareIntent()?.let(context::startActivity)
        },
        onSave = {
            viewModel.createSaveIntent()?.let(saveLauncher::launch)
        },
        onCancel = viewModel::cancelPackage,
        onDelete = viewModel::deleteAllTraceboxData,
    )
}

@Composable
internal fun TraceboxSettingsContent(
    state: TraceboxUiState,
    onEnabledChange: (Boolean) -> Unit,
    onPrepare: () -> Unit,
    onReview: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val available = state.availability != TraceboxAvailability.UNAVAILABLE
    val canPrepare = state.enabled &&
        state.availability in setOf(TraceboxAvailability.READY, TraceboxAvailability.DEGRADED)
    val canReview = state.exportState == TraceboxExportState.PREVIEW_READY
    val canExport = state.exportState in setOf(TraceboxExportState.APPROVED, TraceboxExportState.SAVED)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 16.dp + bottomPadding),
    ) {
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_title),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SwitchSettingsItem(
                    title = stringResource(R.string.settings_tracebox_enable_title),
                    subtitle = stringResource(
                        when {
                            !available -> R.string.settings_tracebox_unavailable
                            state.enabled -> R.string.settings_tracebox_enabled
                            else -> R.string.settings_tracebox_disabled
                        },
                    ),
                    icon = Icons.Default.BugReport,
                    checked = state.enabled,
                    enabled = available,
                    onCheckedChange = onEnabledChange,
                )
            }
        }
        item {
            TraceboxExplanation(
                stringResource(R.string.settings_tracebox_privacy_title),
                stringResource(R.string.settings_tracebox_privacy_body),
            )
        }
        item {
            TraceboxExplanation(
                stringResource(R.string.settings_tracebox_capture_title),
                stringResource(R.string.settings_tracebox_capture_body),
            )
        }
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_export_title),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SettingsItem(
                    title = stringResource(R.string.settings_tracebox_prepare_title),
                    subtitle = stringResource(R.string.settings_tracebox_prepare_body),
                    icon = Icons.Default.FileDownload,
                    enabled = canPrepare,
                    onClick = onPrepare,
                )
                if (state.preview != null) {
                    SettingsItem(
                        title = stringResource(R.string.settings_tracebox_review_title),
                        subtitle = stringResource(
                            R.string.settings_tracebox_review_body,
                            state.preview.includedValues,
                            state.preview.includedBytes,
                            state.preview.sourceProcesses,
                        ),
                        icon = Icons.Default.BugReport,
                        enabled = canReview,
                        onClick = onReview,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_tracebox_digest,
                            state.preview.digestSha256.joinToString("") { "%02x".format(it) },
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SettingsItem(
                    title = stringResource(R.string.settings_tracebox_share_title),
                    subtitle = stringResource(R.string.settings_tracebox_share_body),
                    icon = Icons.Default.Share,
                    enabled = canExport,
                    onClick = onShare,
                )
                SettingsItem(
                    title = stringResource(R.string.settings_tracebox_save_title),
                    subtitle = stringResource(R.string.settings_tracebox_save_body),
                    icon = Icons.Default.FileDownload,
                    enabled = canExport,
                    onClick = onSave,
                )
                SettingsItem(
                    title = stringResource(R.string.settings_tracebox_cancel_title),
                    subtitle = stringResource(R.string.settings_tracebox_cancel_body),
                    icon = Icons.Default.Delete,
                    enabled = state.exportState != TraceboxExportState.IDLE,
                    onClick = onCancel,
                )
            }
        }
        item {
            SettingsGroupCard(
                title = stringResource(R.string.settings_tracebox_delete_group_title),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                SettingsItem(
                    title = stringResource(R.string.settings_tracebox_delete_title),
                    subtitle = stringResource(R.string.settings_tracebox_delete_body),
                    icon = Icons.Default.Delete,
                    enabled = available,
                    onClick = onDelete,
                )
            }
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
