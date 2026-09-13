@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveActivityValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType

/** Activity-only live surface. It intentionally contains no Location-shaped controls or metrics. */
@Composable
internal fun ActivityOnlyTrackingContent(
	presentation: DashboardLiveSessionPresentation.ActivityOnly,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg),
		contentPadding = PaddingValues(bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		item(key = "activity_only") {
			ActivityOnlyTrackingCard(presentation.activity)
		}
	}
}

@Composable
private fun ActivityOnlyTrackingCard(activity: DashboardLiveActivityValue) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("dashboard_activity_only_tracking_card"),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(modifier = Modifier.padding(RidgelineSpacing.Lg)) {
			ActivityStateHeader(activity)
			Spacer(Modifier.size(RidgelineSpacing.Md))
			Text(
				text = stringResource(R.string.dashboard_live_activity_title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
			ActivityValueRow(
				label = stringResource(R.string.dashboard_live_activity_movement),
				value = activity.latestMovementBand?.labelResource?.let { stringResource(it) }
					?: stringResource(R.string.dashboard_live_value_missing),
				tag = "dashboard_live_activity_band",
			)
			ActivityValueRow(
				label = stringResource(R.string.dashboard_live_activity_active_time),
				value = activity.knownActiveDurationNanos?.let { nanos ->
					(nanos / NANOS_PER_MILLISECOND).formatAsDuration(LocalContext.current)
				} ?: stringResource(R.string.dashboard_live_value_missing),
				tag = "dashboard_live_activity_active_time",
			)
			Text(
				text = stringResource(activity.coverage.labelResource),
				modifier = Modifier.testTag("dashboard_live_activity_coverage"),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
			if (activity.gapCount > 0) {
				Text(
					text = stringResource(R.string.dashboard_live_activity_gaps, activity.gapCount),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
			}
		}
	}
}

@Composable
private fun ActivityStateHeader(activity: DashboardLiveActivityValue) {
	val recording = activity.hasRetainedQualifiedEvidence
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		if (recording) {
			Box(
				modifier = Modifier
					.size(10.dp)
					.clip(MaterialTheme.shapes.extraLarge)
					.background(MaterialTheme.colorScheme.error),
			)
		}
		Text(
			text = stringResource(
				if (recording) R.string.dashboard_recording else activity.state.labelResource,
			),
			modifier = Modifier.testTag("dashboard_live_activity_header"),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
		)
	}
}

@Composable
private fun ActivityValueRow(label: String, value: String, tag: String) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = RidgelineSpacing.Xs),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = label, style = MaterialTheme.typography.bodyMedium)
		Text(text = value, modifier = Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
	}
}

internal val ActivityHistoryProductState.labelResource: Int
	get() = when (this) {
		ActivityHistoryProductState.MATERIALIZING -> R.string.dashboard_recent_activity_materializing
		ActivityHistoryProductState.PARTIAL -> R.string.dashboard_recent_activity_partial
		ActivityHistoryProductState.READY -> R.string.dashboard_recent_activity_ready
		ActivityHistoryProductState.UNAVAILABLE -> R.string.dashboard_recent_activity_unavailable
		ActivityHistoryProductState.FAILED -> R.string.dashboard_recent_activity_failed
	}

internal val ActivityHistoryCoverage.labelResource: Int
	get() = when (this) {
		ActivityHistoryCoverage.NONE -> R.string.dashboard_activity_coverage_none
		ActivityHistoryCoverage.PARTIAL -> R.string.dashboard_activity_coverage_partial
		ActivityHistoryCoverage.COMPLETE -> R.string.dashboard_activity_coverage_complete
	}

internal val ActivityHistoryType.labelResource: Int
	get() = when (this) {
		ActivityHistoryType.STILL -> R.string.dashboard_activity_still
		ActivityHistoryType.WALKING -> R.string.dashboard_activity_walking
		ActivityHistoryType.RUNNING -> R.string.dashboard_activity_running
		ActivityHistoryType.ON_BICYCLE -> R.string.dashboard_activity_cycling
		ActivityHistoryType.IN_VEHICLE -> R.string.dashboard_activity_vehicle
		ActivityHistoryType.ON_FOOT -> R.string.dashboard_activity_on_foot
		ActivityHistoryType.TILTING -> R.string.dashboard_activity_tilting
		ActivityHistoryType.UNKNOWN -> R.string.dashboard_activity_unknown
	}

private const val NANOS_PER_MILLISECOND = 1_000_000L
