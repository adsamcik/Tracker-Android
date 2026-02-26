package com.adsamcik.tracker.statistics.ui.ski

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R

private const val MS_TO_KMH = 3.6

/**
 * Displays ski session detail with summary stats and segment timeline.
 * Only shown when [segments] is non-empty.
 */
@Composable
fun SkiSessionDetailSection(
	segments: List<SkiRunSegment>,
	modifier: Modifier = Modifier
) {
	if (segments.isEmpty()) return

	val context = LocalContext.current
	val resources = context.resources
	val lengthSystem = remember { TrackerSettingsQuick.lengthSystem(context) }

	val downhillRuns = remember(segments) {
		segments.filter { it.segmentType == SkiSegmentType.DOWNHILL_RUN }
	}
	val liftSegments = remember(segments) {
		segments.filter { it.segmentType == SkiSegmentType.LIFT_UP }
	}

	val totalVertical = remember(downhillRuns) {
		downhillRuns.sumOf { it.verticalM.toDouble() }.toFloat()
	}
	val maxSpeed = remember(downhillRuns) {
		downhillRuns.maxOfOrNull { it.maxSpeedMps } ?: 0f
	}
	val totalSkiTimeMs = remember(downhillRuns) {
		downhillRuns.sumOf { it.endTimeMs - it.startTimeMs }
	}
	val totalLiftTimeMs = remember(liftSegments) {
		liftSegments.sumOf { it.endTimeMs - it.startTimeMs }
	}

	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
		// Section header
		Text(
			text = stringResource(R.string.ski_detail_header),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface
		)

		// Summary cards row
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			SkiMetricCard(
				label = stringResource(R.string.ski_detail_runs),
				value = downhillRuns.size.toString(),
				modifier = Modifier.weight(1f)
			)
			SkiMetricCard(
				label = stringResource(R.string.ski_detail_vertical),
				value = resources.formatDistance(
					totalVertical.toDouble(), 0, lengthSystem
				),
				modifier = Modifier.weight(1f)
			)
			SkiMetricCard(
				label = stringResource(R.string.ski_detail_max_speed),
				value = resources.formatSpeed(context, maxSpeed.toDouble(), 0),
				modifier = Modifier.weight(1f)
			)
		}

		// Time breakdown
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			SkiMetricCard(
				label = stringResource(R.string.ski_detail_time_skiing),
				value = totalSkiTimeMs.formatAsDuration(context),
				modifier = Modifier.weight(1f)
			)
			SkiMetricCard(
				label = stringResource(R.string.ski_detail_time_lifts),
				value = totalLiftTimeMs.formatAsDuration(context),
				modifier = Modifier.weight(1f)
			)
		}

		// Segment timeline
		if (segments.size > 1) {
			Spacer(Modifier.height(4.dp))
			SegmentTimeline(segments, context, resources, lengthSystem)
		}
	}
}

@Composable
private fun SegmentTimeline(
	segments: List<SkiRunSegment>,
	context: android.content.Context,
	resources: android.content.res.Resources,
	lengthSystem: com.adsamcik.tracker.shared.preferences.type.LengthSystem
) {
	var runNumber = 0

	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		for (segment in segments) {
			when (segment.segmentType) {
				SkiSegmentType.DOWNHILL_RUN -> {
					runNumber++
					SegmentRow(
						emoji = "🎿",
						label = stringResource(R.string.ski_detail_run_label, runNumber),
						detail = stringResource(
							R.string.ski_detail_segment_stats,
							resources.formatDistance(
								segment.verticalM.toDouble(), 0, lengthSystem
							),
							resources.formatSpeed(
								context, segment.maxSpeedMps.toDouble(), 0
							),
							(segment.endTimeMs - segment.startTimeMs).formatAsDuration(context)
						),
						color = MaterialTheme.colorScheme.primary
					)
				}

				SkiSegmentType.LIFT_UP -> {
					val liftEmoji = liftTypeEmoji(segment.liftType)
					val liftLabel = liftTypeLabel(segment.liftType)
					SegmentRow(
						emoji = liftEmoji,
						label = liftLabel,
						detail = stringResource(
							R.string.ski_detail_lift_stats,
							resources.formatDistance(
								kotlin.math.abs(segment.verticalM).toDouble(), 0, lengthSystem
							),
							(segment.endTimeMs - segment.startTimeMs).formatAsDuration(context)
						),
						color = MaterialTheme.colorScheme.tertiary
					)
				}

				SkiSegmentType.IDLE, SkiSegmentType.WALK -> {
					// Show idle/walk only if longer than 2 minutes
					val durationMs = segment.endTimeMs - segment.startTimeMs
					if (durationMs > 120_000L) {
						SegmentRow(
							emoji = if (segment.segmentType == SkiSegmentType.WALK) "🚶" else "⏸️",
							label = if (segment.segmentType == SkiSegmentType.WALK) "Walk" else "Pause",
							detail = durationMs.formatAsDuration(context),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
		}
	}
}

@Composable
private fun SegmentRow(
	emoji: String,
	label: String,
	detail: String,
	color: androidx.compose.ui.graphics.Color
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp, horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(
			text = emoji,
			style = MaterialTheme.typography.titleMedium
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = label,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = color
			)
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

@Composable
private fun SkiMetricCard(
	label: String,
	value: String,
	modifier: Modifier = Modifier
) {
	GlassCard(modifier = modifier) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Spacer(Modifier.height(2.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface
			)
		}
	}
}

private fun liftTypeEmoji(type: String?): String = when (type) {
	"gondola" -> "🚡"
	"cable_car" -> "🚠"
	"drag_lift" -> "🎿"
	"funicular" -> "🚃"
	"chairlift" -> "🪑"
	else -> "⬆️"
}

private fun liftTypeLabel(type: String?): String = when (type) {
	"gondola" -> "Gondola"
	"cable_car" -> "Cable car"
	"drag_lift" -> "Drag lift"
	"magic_carpet" -> "Magic carpet"
	"funicular" -> "Funicular"
	"chairlift" -> "Chairlift"
	else -> "Lift"
}
