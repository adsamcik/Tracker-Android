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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveStepsValue
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

/** Minimal live product surface for a session whose exact capture set is Steps only. */
@Composable
internal fun StepsOnlyTrackingContent(
	sessionData: TrackerSessionSnapshot,
	presentation: DashboardLiveSessionPresentation.StepsOnly,
	modifier: Modifier = Modifier,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
) {
	require(sessionData.id == presentation.segmentId) {
		"Live Steps presentation must match the active physical segment"
	}
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg)
			.testTag("dashboard_steps_only_tracking_content"),
		contentPadding = PaddingValues(bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		item(key = "steps_only_hero") {
			StepsOnlyTrackingCard(steps = presentation.steps)
		}
	}
}

@Composable
private fun StepsOnlyTrackingCard(
	steps: DashboardLiveStepsValue,
) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("dashboard_steps_only_tracking_card"),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(modifier = Modifier.padding(RidgelineSpacing.Lg)) {
			StepsOnlyRecordingHeader()
			Spacer(Modifier.height(RidgelineSpacing.Md))
			StepsOnlyMetric(steps)
		}
	}
}

@Composable
private fun StepsOnlyRecordingHeader() {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		Box(
			modifier = Modifier
				.size(10.dp)
				.clip(MaterialTheme.shapes.extraLarge)
				.background(MaterialTheme.colorScheme.error),
		)
		Text(
			text = stringResource(R.string.dashboard_recording),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
		)
	}
}

@Composable
private fun StepsOnlyMetric(steps: DashboardLiveStepsValue) {
	Text(
		text = stringResource(R.string.dashboard_metric_steps),
		style = MaterialTheme.typography.labelMedium,
		color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
	)
	Text(
		text = steps.metricValue(),
		modifier = Modifier.testTag("dashboard_live_steps_value"),
		style = MaterialTheme.typography.displaySmall,
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.onPrimaryContainer,
	)
	Text(
		text = steps.metricStatus(),
		modifier = Modifier.testTag("dashboard_live_steps_status"),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
	)
}

/** Neutral state while capture authority is unresolved or its read stream failed. */
@Composable
internal fun TrackingHistoryResolutionContent(
	historyUnavailable: Boolean,
	modifier: Modifier = Modifier,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
) {
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg)
			.testTag("dashboard_tracking_history_resolution"),
		contentPadding = PaddingValues(bottom = bottomClearance),
	) {
		item(key = "tracking_history_resolution") {
			Card(
				modifier = Modifier.fillMaxWidth(),
				colors = CardDefaults.cardColors(
					containerColor = RidgelineCardDefaults.containerColor,
				),
				shape = RidgelineCardDefaults.shape,
			) {
				Text(
					text = stringResource(
						if (historyUnavailable) {
							R.string.dashboard_live_history_unavailable
						} else {
							R.string.dashboard_live_history_resolving
						},
					),
					modifier = Modifier.padding(RidgelineSpacing.Lg),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun DashboardLiveStepsValue.metricValue(): String = when (this) {
	is DashboardLiveStepsValue.Complete -> count.formatReadable()
	DashboardLiveStepsValue.CoveredZero -> "0"
	is DashboardLiveStepsValue.Partial -> lowerBound?.let { count ->
		stringResource(R.string.dashboard_live_steps_lower_bound, count.formatReadable())
	} ?: stringResource(R.string.dashboard_live_value_missing)
	DashboardLiveStepsValue.Materializing,
	DashboardLiveStepsValue.Missing,
	DashboardLiveStepsValue.Unavailable -> stringResource(R.string.dashboard_live_value_missing)
}

@Composable
private fun DashboardLiveStepsValue.metricStatus(): String = stringResource(
	when (this) {
		is DashboardLiveStepsValue.Complete -> R.string.dashboard_live_steps_complete
		DashboardLiveStepsValue.CoveredZero -> R.string.dashboard_live_steps_covered_zero
		is DashboardLiveStepsValue.Partial -> R.string.dashboard_live_steps_partial
		DashboardLiveStepsValue.Materializing -> R.string.dashboard_live_steps_materializing
		DashboardLiveStepsValue.Missing -> R.string.dashboard_live_steps_missing
		DashboardLiveStepsValue.Unavailable -> R.string.dashboard_live_steps_unavailable
	},
)
