package com.adsamcik.tracker.statistics.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModel
import com.adsamcik.tracker.statistics.presenter.TripDetailState
import com.adsamcik.tracker.stats.api.repository.TripSummary
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val MS_TO_KMH = 3.6

/**
 * Entry composable for trip detail screen.
 * Receives tripId from navigation args.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailRoute(
	tripId: Long,
	onBack: () -> Unit,
	viewModel: TripDetailPresenterViewModel = hiltViewModel()
) {
	val state by viewModel.state.collectAsState()
	val skiSegments by viewModel.skiSegments.collectAsState()
	var showDeleteDialog by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	val context = LocalContext.current

	if (showDeleteDialog) {
		DeleteConfirmationDialog(
			onConfirm = {
				showDeleteDialog = false
				viewModel.deleteTrip(onDeleted = onBack)
			},
			onDismiss = { showDeleteDialog = false }
		)
	}

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
				actions = {
					if (state is TripDetailState.Loaded) {
						Box {
							IconButton(onClick = { showMenu = true }) {
								Icon(
									Icons.Filled.MoreVert,
									contentDescription = stringResource(R.string.trip_detail_more_options)
								)
							}
							DropdownMenu(
								expanded = showMenu,
								onDismissRequest = { showMenu = false }
							) {
								DropdownMenuItem(
									text = { Text(stringResource(R.string.trip_detail_export_gpx)) },
									onClick = {
										showMenu = false
										viewModel.exportTripGpx(context)
									}
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.trip_detail_delete)) },
									onClick = {
										showMenu = false
										showDeleteDialog = true
									}
								)
							}
						}
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
					TripOverview(trip = s.trip, skiSegments = skiSegments)
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
private fun TripOverview(
	trip: TripSummary,
	skiSegments: List<com.adsamcik.tracker.shared.base.database.data.SkiRunSegment> = emptyList()
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = remember { TrackerSettingsQuick.snapshot(context) }

	val startText = remember(trip.startTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.startTimeMs.raw).atZone(ZoneId.systemDefault())
		)
	}
	val durationText = remember(trip.duration) {
		trip.duration.raw.formatAsDuration(context)
	}
	val distanceText = remember(trip.distance, settings) {
		resources.formatDistance(
			trip.distance.raw,
			digits = if (trip.distance.raw >= 1000f) 1 else 2,
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

		// Primary metrics grid
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
				value = trip.steps.raw.formatReadable(),
				modifier = Modifier.weight(1f)
			)
		}

		// Developer metrics behind expandable toggle
		DeveloperMetrics(trip)

		// Ski session detail (only shown when ski segments exist)
		if (skiSegments.isNotEmpty()) {
			com.adsamcik.tracker.statistics.ui.ski.SkiSessionDetailSection(
				segments = skiSegments,
				modifier = Modifier.fillMaxWidth()
			)
		}
	}
}

@Composable
private fun DeveloperMetrics(trip: TripSummary) {
	var expanded by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { expanded = !expanded }
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center
	) {
		Text(
			text = stringResource(
				if (expanded) R.string.trip_detail_hide_details
				else R.string.trip_detail_show_details
			),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Icon(
			imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
			contentDescription = null,
			modifier = Modifier.size(20.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}

	AnimatedVisibility(
		visible = expanded,
		enter = expandVertically(),
		exit = shrinkVertically()
	) {
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
				value = trip.primaryMode.name.replace('_', ' ').lowercase()
					.replaceFirstChar { it.uppercase() },
				modifier = Modifier.weight(1f)
			)
		}
	}
}

@Composable
private fun DeleteConfirmationDialog(
	onConfirm: () -> Unit,
	onDismiss: () -> Unit
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.trip_detail_delete_confirm_title)) },
		text = { Text(stringResource(R.string.trip_detail_delete_confirm_message)) },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text(stringResource(R.string.trip_detail_delete_confirm))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.trip_detail_cancel))
			}
		}
	)
}

internal fun exportGpx(
	context: android.content.Context,
	trip: TripSummary,
	points: List<DatabaseLocation>
) {
	val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	val sb = StringBuilder()
	sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
	sb.append("""<gpx version="1.1" creator="Tracker">""")
	sb.append("<trk><trkseg>")
	for (pt in points) {
		sb.append("""<trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
		pt.altitude?.let { sb.append("<ele>$it</ele>") }
		sb.append("<time>${sdf.format(Date(pt.time))}</time>")
		sb.append("</trkpt>")
	}
	sb.append("</trkseg></trk></gpx>")

	val file = File(context.cacheDir, "trip_${trip.id}.gpx")
	file.writeText(sb.toString())

	val uri = FileProvider.getUriForFile(
		context,
		"${context.packageName}.fileprovider",
		file
	)

	val shareIntent = Intent(Intent.ACTION_SEND).apply {
		type = "application/gpx+xml"
		putExtra(Intent.EXTRA_STREAM, uri)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	context.startActivity(Intent.createChooser(shareIntent, null))
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
