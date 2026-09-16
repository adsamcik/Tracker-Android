package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
	DisposableEffect(viewModel) {
		onDispose(viewModel::close)
	}
	val state by viewModel.state.collectAsStateWithLifecycle()
	var showMenu by remember { mutableStateOf(false) }
	Scaffold(
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
								TripDetailUnavailableActions()
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
						intent = requireNotNull(entry.intent),
					)
					is SourceAwareHistoryPageEntry.WifiOnly -> TripDetailWifiOverview(
						history = entry.history,
						intent = requireNotNull(entry.intent),
					)
					is SourceAwareHistoryPageEntry.CellOnly -> TripDetailCellOverview(
						history = entry.history,
						intent = requireNotNull(entry.intent),
					)
					is SourceAwareHistoryPageEntry.Physical,
					is SourceAwareHistoryPageEntry.StepsOnly,
					is SourceAwareHistoryPageEntry.ImportedSteps,
					is SourceAwareHistoryPageEntry.PressureOnly -> SourceHistoryUnavailable(
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
