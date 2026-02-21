package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.TripDetailState
import com.adsamcik.tracker.statistics.viewmodel.TripDetailViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Entry composable for trip detail screen.
 * Receives tripId from navigation args.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailRoute(
	tripId: Long,
	onBack: () -> Unit,
	viewModel: TripDetailViewModel = hiltViewModel()
) {
	val state by viewModel.state.collectAsState()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.trip_detail_title)) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.action_navigate_back)
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background
				)
			)
		}
	) { contentPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(contentPadding)
				.background(MaterialTheme.colorScheme.background)
		) {
			when (val s = state) {
				is TripDetailState.Loading -> {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
					}
				}
				is TripDetailState.Loaded -> {
					TripOverview(trip = s.trip)
				}
				is TripDetailState.NotFound -> {
					Box(
						Modifier
							.fillMaxSize()
							.padding(32.dp),
						contentAlignment = Alignment.Center
					) {
						EmptyStateCard(
							icon = Icons.Filled.ErrorOutline,
							title = stringResource(R.string.trip_detail_not_found),
							subtitle = stringResource(R.string.trip_detail_not_found_subtitle)
						)
					}
				}
				is TripDetailState.Error -> {
					Box(
						Modifier
							.fillMaxSize()
							.padding(32.dp),
						contentAlignment = Alignment.Center
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							EmptyStateCard(
								icon = Icons.Filled.ErrorOutline,
								title = stringResource(R.string.trip_detail_error_title),
								subtitle = s.message
							)
							Spacer(Modifier.height(16.dp))
							androidx.compose.material3.Button(onClick = { viewModel.retry() }) {
								Text(stringResource(R.string.trip_detail_retry))
							}
						}
					}
				}
			}
		}
	}
}

private val dateTimeFormatter: DateTimeFormatter by lazy {
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
}

@Composable
private fun TripOverview(trip: Trip) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = remember { TrackerSettingsQuick.snapshot(context) }

	val startText = remember(trip.startTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.startTimeMs).atZone(ZoneId.systemDefault())
		)
	}
	val durationText = remember(trip.durationMs) {
		trip.durationMs.formatAsDuration(context)
	}
	val distanceText = remember(trip.distanceM, settings) {
		resources.formatDistance(
			trip.distanceM,
			digits = if (trip.distanceM >= 1000f) 1 else 2,
			unit = settings.lengthSystem
		)
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp)
	) {
		// Header card with time and icon
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically
			) {
				Box(
					modifier = Modifier
						.size(56.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
					contentAlignment = Alignment.Center
				) {
					Icon(
						imageVector = Icons.Filled.Route,
						contentDescription = null,
						modifier = Modifier.size(28.dp),
						tint = MaterialTheme.colorScheme.primary
					)
				}
				Column(Modifier.padding(start = 16.dp)) {
					Text(
						text = startText,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface
					)
					Text(
						text = durationText,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}

		// Metrics grid
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			MetricCard(
				label = stringResource(R.string.trip_detail_distance),
				value = distanceText,
				modifier = Modifier.weight(1f)
			)
			MetricCard(
				label = stringResource(R.string.trip_detail_steps),
				value = trip.steps?.formatReadable() ?: "-",
				modifier = Modifier.weight(1f)
			)
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			MetricCard(
				label = stringResource(R.string.trip_detail_samples),
				value = trip.sampleCount.toString(),
				modifier = Modifier.weight(1f)
			)
			MetricCard(
				label = stringResource(R.string.trip_detail_source),
				value = trip.source.name.replace('_', ' ').lowercase()
					.replaceFirstChar { it.uppercase() },
				modifier = Modifier.weight(1f)
			)
		}

		Spacer(Modifier.height(80.dp))
	}
}

@Composable
private fun MetricCard(
	label: String,
	value: String,
	modifier: Modifier = Modifier
) {
	GlassCard(modifier = modifier) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Spacer(Modifier.height(4.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface
			)
		}
	}
}
