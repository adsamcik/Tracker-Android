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
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureMetrics
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

/** Minimal live surface for accepted exact-capture Pressure-only history. */
@Composable
internal fun PressureOnlyTrackingContent(
	sessionData: TrackerSessionSnapshot,
	presentation: DashboardLiveSessionPresentation.PressureOnly,
	modifier: Modifier = Modifier,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
) {
	require(sessionData.id == presentation.segmentId) {
		"Live Pressure presentation must match the active physical segment"
	}
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg)
			.testTag("dashboard_pressure_only_tracking_content"),
		contentPadding = PaddingValues(bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		item(key = "pressure_only_hero") {
			PressureOnlyTrackingCard(presentation.pressure)
		}
	}
}

@Composable
private fun PressureOnlyTrackingCard(pressure: DashboardLivePressureValue) {
	val metrics = pressure.metricsOrNull
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("dashboard_pressure_only_tracking_card"),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
		),
	) {
		Column(modifier = Modifier.padding(RidgelineSpacing.Lg)) {
			PressureOnlyStateHeader(pressure)
			Spacer(Modifier.height(RidgelineSpacing.Md))
			Text(
				text = stringResource(R.string.dashboard_metric_pressure),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
			)
			Text(
				text = metrics?.let {
					stringResource(R.string.dashboard_live_pressure_value, it.latestHectopascals)
				} ?: stringResource(R.string.dashboard_live_value_missing),
				modifier = Modifier.testTag("dashboard_live_pressure_value"),
				style = MaterialTheme.typography.displaySmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
			Text(
				text = stringResource(pressure.statusResource),
				modifier = Modifier.testTag("dashboard_live_pressure_status"),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
			)
			if (metrics != null) PressureDetails(metrics)
		}
	}
}

@Composable
private fun PressureOnlyStateHeader(pressure: DashboardLivePressureValue) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		if (pressure.hasDurableRecordingHeader) {
			Box(
				modifier = Modifier
					.size(10.dp)
					.clip(MaterialTheme.shapes.extraLarge)
					.background(MaterialTheme.colorScheme.error),
			)
		}
		Text(
			text = stringResource(pressure.headerResource),
			modifier = Modifier.testTag("dashboard_live_pressure_header"),
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
		)
	}
}

@Composable
private fun PressureDetails(metrics: DashboardLivePressureMetrics) {
	Spacer(Modifier.height(RidgelineSpacing.Sm))
	Text(
		text = stringResource(
			R.string.dashboard_live_pressure_range,
			metrics.minimumHectopascals,
			metrics.maximumHectopascals,
		),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onPrimaryContainer,
	)
	Text(
		text = stringResource(
			R.string.dashboard_live_pressure_change,
			metrics.changeHectopascals,
		),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onPrimaryContainer,
	)
	Text(
		text = stringResource(metrics.coverage.labelResource),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onPrimaryContainer,
	)
	Text(
		text = stringResource(
			R.string.dashboard_live_pressure_zones,
			metrics.zoneAuthorities.joinToString(),
		),
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
	)
}

private val DashboardLivePressureValue.metricsOrNull: DashboardLivePressureMetrics?
	get() = when (this) {
		is DashboardLivePressureValue.Ready -> metrics
		is DashboardLivePressureValue.Partial -> metrics
		is DashboardLivePressureValue.Materializing -> metrics
		DashboardLivePressureValue.Unavailable,
		DashboardLivePressureValue.Failed -> null
	}

private val DashboardLivePressureValue.statusResource: Int
	get() = when (this) {
		is DashboardLivePressureValue.Ready -> R.string.dashboard_recent_pressure_available
		is DashboardLivePressureValue.Partial -> R.string.dashboard_recent_pressure_partial
		is DashboardLivePressureValue.Materializing -> R.string.dashboard_recent_pressure_materializing
		DashboardLivePressureValue.Unavailable -> R.string.dashboard_recent_pressure_unavailable
		DashboardLivePressureValue.Failed -> R.string.dashboard_recent_pressure_failed
	}

private val DashboardLivePressureValue.hasDurableRecordingHeader: Boolean
	get() = when (this) {
		is DashboardLivePressureValue.Ready -> true
		is DashboardLivePressureValue.Partial -> metrics != null
		is DashboardLivePressureValue.Materializing -> metrics != null
		DashboardLivePressureValue.Unavailable,
		DashboardLivePressureValue.Failed -> false
	}

private val DashboardLivePressureValue.headerResource: Int
	get() = if (hasDurableRecordingHeader) {
		R.string.dashboard_recording
	} else {
		statusResource
	}

private val PressureHistoryCoverage.labelResource: Int
	get() = when (this) {
		PressureHistoryCoverage.NONE -> R.string.dashboard_pressure_coverage_none
		PressureHistoryCoverage.COMPLETE -> R.string.dashboard_pressure_coverage_complete
		PressureHistoryCoverage.PARTIAL -> R.string.dashboard_pressure_coverage_partial
		PressureHistoryCoverage.UNKNOWN -> R.string.dashboard_pressure_coverage_unknown
	}
