package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailState
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailUnavailableReason
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailViewModel
import com.adsamcik.tracker.statistics.presenter.SourceHistoryActionResult
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHistoryDetailRoute(
	onBack: () -> Unit,
	viewModel: SourceHistoryDetailViewModel = hiltViewModel(),
) {
	val leaveDetail = {
		viewModel.close()
		onBack()
	}
	BackHandler(onBack = leaveDetail)
	val state by viewModel.state.collectAsStateWithLifecycle()
	val busy by viewModel.actionBusy.collectAsStateWithLifecycle()
	val result by viewModel.actionResult.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	val context = LocalContext.current
	val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), viewModel::exportTo)
	LaunchedEffect(result) {
		if (result == SourceHistoryActionResult.DELETED) leaveDetail()
		else result?.let { snackbar.showSnackbar(context.getString(when (it) {
			SourceHistoryActionResult.EXPORTED -> R.string.organization_export_saved
			SourceHistoryActionResult.CHANGED -> R.string.organization_stale
			SourceHistoryActionResult.UNAVAILABLE -> R.string.history_value_unavailable
			else -> R.string.source_history_action_failed
		})) }
	}
	var showMenu by remember { mutableStateOf(false) }
	var confirmDelete by remember { mutableStateOf(false) }
	Scaffold(
		snackbarHost = { SnackbarHost(snackbar) },
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.source_history_detail_title)) },
				navigationIcon = {
					IconButton(onClick = leaveDetail) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.action_navigate_back),
						)
					}
				},
				actions = {
					if (state is SourceHistoryDetailState.Loaded) {
						Box {
							IconButton(onClick = { showMenu = true }) {
								Icon(
									Icons.Filled.MoreVert,
									contentDescription = stringResource(
										R.string.trip_detail_more_options,
									),
								)
							}
							DropdownMenu(
								expanded = showMenu,
								onDismissRequest = { showMenu = false },
							) {
								DropdownMenuItem(text = { Text(stringResource(R.string.organization_export)) }, enabled = !busy,
									onClick = { showMenu = false
										if (viewModel.prepareExport()) {
											val source = (state as? SourceHistoryDetailState.Loaded)?.selection?.entry?.source
											if (source != null) save.launch(sourceHistoryFileName(source))
										}
									})
								DropdownMenuItem(text = { Text(stringResource(R.string.trip_detail_delete)) }, enabled = !busy,
									onClick = { showMenu = false; confirmDelete = true })
							}
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { padding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding),
		) {
			when (val current = state) {
				SourceHistoryDetailState.Loading -> Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
				is SourceHistoryDetailState.Loaded -> when (val entry = current.selection.entry) {
					is SourceAwareHistoryPageEntry.ActivityOnly -> TripDetailActivityOverview(
						activity = entry.history,
					)
					is SourceAwareHistoryPageEntry.WifiOnly -> TripDetailWifiOverview(
						history = entry.history,
					)
					is SourceAwareHistoryPageEntry.CellOnly -> TripDetailCellOverview(
						history = entry.history,
					)
					is SourceAwareHistoryPageEntry.StepsOnly,
					is SourceAwareHistoryPageEntry.PressureOnly -> SourceOnlySavedReadingsContent(current)
					is SourceAwareHistoryPageEntry.Physical,
					is SourceAwareHistoryPageEntry.ImportedSteps -> SourceHistoryUnavailable(
						reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
						source = entry.source,
						canRetry = false,
						onRetry = viewModel::retry,
					)
				}
				is SourceHistoryDetailState.Unavailable -> SourceHistoryUnavailable(
					reason = current.reason,
					source = current.source,
					canRetry = current.canRetry,
					onRetry = viewModel::retry,
				)
			}
		}
	}
	if (confirmDelete) AlertDialog(onDismissRequest = { if (!busy) confirmDelete = false },
		title = { Text(stringResource(R.string.source_history_delete_title)) },
		text = { Text(stringResource(R.string.source_history_delete_message)) },
		confirmButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false; viewModel.delete() }) {
			Text(stringResource(R.string.trip_detail_delete))
		} }, dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) {
			Text(stringResource(R.string.organization_cancel))
		} })
}

private fun sourceHistoryFileName(source: HistorySource): String = when (source) {
	HistorySource.STEPS -> "steps.trackersteps"
	HistorySource.PRESSURE -> "pressure.trackerpressure"
	HistorySource.ACTIVITY -> "activity.trackeractivity"
	HistorySource.WIFI -> "wifi.trackerwifi"
	HistorySource.CELL -> "cell.trackercell"
	HistorySource.LOCATION -> "location.trackerlocation"
}

@Composable
private fun SourceHistoryUnavailable(
	reason: SourceHistoryDetailUnavailableReason,
	source: HistorySource?,
	canRetry: Boolean,
	onRetry: () -> Unit,
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(32.dp),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			EmptyStateCard(
				icon = Icons.Filled.ErrorOutline,
				title = stringResource(R.string.source_history_detail_unavailable),
				subtitle = sourceHistoryUnavailableMessage(reason, source),
			)
			if (canRetry) {
				Spacer(Modifier.height(16.dp))
				Button(onClick = onRetry) {
					Text(stringResource(R.string.trip_detail_retry))
				}
			}
		}
	}
}

@Composable
private fun sourceHistoryUnavailableMessage(
	reason: SourceHistoryDetailUnavailableReason,
	source: HistorySource?,
): String {
	val reasonText = stringResource(
		when (reason) {
			SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED ->
				R.string.source_history_detail_expired
			SourceHistoryDetailUnavailableReason.NOT_FOUND ->
				R.string.source_history_detail_not_found
			SourceHistoryDetailUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ->
				R.string.source_history_detail_budget
			SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE ->
				R.string.source_history_detail_integrity
			SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE ->
				R.string.source_history_detail_snapshot_unavailable
			SourceHistoryDetailUnavailableReason.SELECTION_CHANGED ->
				R.string.source_history_detail_changed
			SourceHistoryDetailUnavailableReason.RETRYABLE_FAILURE ->
				R.string.source_history_detail_retryable
		},
	)
	val sourceText = source?.let {
		stringResource(
			when (it) {
				HistorySource.LOCATION -> R.string.trip_detail_source_location
				HistorySource.WIFI -> R.string.trip_detail_source_wifi
				HistorySource.CELL -> R.string.trip_detail_source_cell
				HistorySource.ACTIVITY -> R.string.trip_detail_source_activity
				HistorySource.STEPS -> R.string.trip_detail_source_steps
				HistorySource.PRESSURE -> R.string.trip_detail_source_pressure
			},
		)
	}
	return sourceText?.let {
		stringResource(R.string.source_history_detail_failure_for_source, it, reasonText)
	} ?: reasonText
}
