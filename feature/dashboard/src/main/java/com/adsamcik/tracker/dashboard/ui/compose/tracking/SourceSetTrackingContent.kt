@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.dashboardNameResource
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

/** One source-first live surface for exact multi-source sessions without Location capture. */
@Composable
internal fun SourceSetTrackingContent(
	sessionData: TrackerSessionSnapshot,
	presentation: DashboardLiveSessionPresentation.SourceSetWithoutLocation,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	modifier: Modifier = Modifier,
) {
	require(sessionData.id == presentation.segmentId)
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg)
			.testTag("dashboard_source_set_tracking"),
		contentPadding = PaddingValues(bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		item(key = "source_set_header") {
			SourceSetRecordingHeader()
		}
		HistorySource.entries.filter(presentation.capturedSources::contains).forEach { source ->
			item(key = source.name) {
				SourceSetEvidenceCard(source, presentation)
			}
		}
	}
}

@Composable
private fun MissingSourceEvidenceCard(source: HistorySource) {
	val sourceName = stringResource(source.dashboardNameResource)
	Card(
		modifier = Modifier.fillMaxWidth().testTag("dashboard_source_set_${source.name.lowercase()}_unavailable"),
		colors = CardDefaults.cardColors(containerColor = RidgelineCardDefaults.containerColor),
		shape = RidgelineCardDefaults.shape,
	) {
		Text(
			text = stringResource(R.string.dashboard_live_source_evidence_unavailable, sourceName),
			modifier = Modifier.padding(RidgelineSpacing.Lg),
			style = MaterialTheme.typography.bodyMedium,
		)
	}
}

@Composable
private fun SourceSetRecordingHeader() {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(
			modifier = Modifier.padding(RidgelineSpacing.Lg),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		) {
			Text(
				text = stringResource(R.string.dashboard_live_source_set_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
			)
			Text(
				text = stringResource(R.string.dashboard_live_source_set_no_route),
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}

@Composable
private fun SourceSetEvidenceCard(
	source: HistorySource,
	presentation: DashboardLiveSessionPresentation.SourceSetWithoutLocation,
) {
	when (source) {
		HistorySource.STEPS -> if (presentation.steps != null) {
			StepsOnlyTrackingCard(presentation.steps)
		} else {
			MissingSourceEvidenceCard(source)
		}
		HistorySource.ACTIVITY -> if (presentation.activity != null) {
			ActivityOnlyTrackingCard(presentation.activity)
		} else {
			MissingSourceEvidenceCard(source)
		}
		HistorySource.PRESSURE -> PressureOnlyTrackingCard(
			presentation.pressure ?: DashboardLivePressureValue.Unavailable,
		)
		HistorySource.WIFI -> RadioSourceEvidenceCard(
			source, R.string.dashboard_live_wifi_title, presentation.wifi,
		)
		HistorySource.CELL -> RadioSourceEvidenceCard(
			source, R.string.dashboard_live_cell_title, presentation.cell,
		)
		HistorySource.LOCATION -> error("Location cannot enter a location-free source set")
	}
}

@Composable
private fun RadioSourceEvidenceCard(
	source: HistorySource,
	titleResource: Int,
	value: com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioHistoryValue?,
) {
	if (value != null) {
		RadioTrackingCard(
			title = stringResource(titleResource),
			presentation = value,
			includeLocationNotice = false,
		)
	} else {
		MissingSourceEvidenceCard(source)
	}
}
