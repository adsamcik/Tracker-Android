package com.adsamcik.tracker.dashboard.ui.compose.cards

import android.text.format.DateUtils
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.visualization.SessionPathPreview
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance

/**
 * Card showing last session overview with duration, distance, steps,
 * and an animated path preview.
 */
@Composable
internal fun LastSessionCard(
	session: TrackerSession,
	pathPoints: List<Location>?,
	onMapClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = TrackerSettingsQuick.snapshot(context)

	val durationMs = (session.end - session.start).coerceAtLeast(0L)
	val durationText = durationMs.formatAsDuration(context)
	val distanceText = resources.formatDistance(
		session.distanceInM,
		digits = if (session.distanceInM >= 1000f) 1 else 2,
		unit = settings.lengthSystem,
	)
	val stepsText = session.steps.takeIf { it > 0 }?.formatReadable() ?: "0"
	val sessionAge = DateUtils.getRelativeTimeSpanString(
		session.start,
		System.currentTimeMillis(),
		DateUtils.MINUTE_IN_MILLIS,
		DateUtils.FORMAT_ABBREV_RELATIVE,
	).toString()

	val cardContentDescription = stringResource(
		R.string.dashboard_cd_last_session,
		durationText,
		distanceText,
	)

	Card(
		modifier = modifier
			.fillMaxWidth()
			.semantics {
				contentDescription = cardContentDescription
			},
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
		),
		shape = MaterialTheme.shapes.large,
	) {
		Column(modifier = Modifier.padding(20.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = stringResource(R.string.dashboard_last_session_title),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
				)
				Spacer(Modifier.weight(1f))
				Text(
					text = sessionAge,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
				)
			}

			Spacer(Modifier.height(12.dp))
			Text(
				text = durationText,
				style = MaterialTheme.typography.headlineMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(R.string.dashboard_last_session_duration),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
			)

			Spacer(Modifier.height(16.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(16.dp),
			) {
				SessionMetricItem(
					label = stringResource(R.string.dashboard_last_session_distance),
					value = distanceText,
					modifier = Modifier.weight(1f),
				)
				SessionMetricItem(
					label = stringResource(R.string.dashboard_last_session_steps),
					value = stepsText,
					modifier = Modifier.weight(1f),
				)
			}

			// Path preview
			if (pathPoints != null && pathPoints.isNotEmpty()) {
				Spacer(Modifier.height(16.dp))
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp)
						.clip(MaterialTheme.shapes.medium)
						.clickable { onMapClick() },
				) {
					SessionPathPreview(
						points = pathPoints,
						modifier = Modifier
							.fillMaxSize()
							.background(
								MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
							),
					)

					// View Map overlay
					Box(
						modifier = Modifier
							.align(Alignment.BottomEnd)
							.padding(8.dp)
							.background(
								MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
								MaterialTheme.shapes.small,
							)
							.padding(horizontal = 6.dp, vertical = 2.dp),
					) {
						Text(
							text = stringResource(R.string.dashboard_last_session_view_map),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurface,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun SessionMetricItem(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Column(modifier) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
		)
		Spacer(Modifier.height(4.dp))
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}
