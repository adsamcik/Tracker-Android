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
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.DashboardRadioHistoryFacts
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

@Composable
internal fun WifiOnlyTrackingContent(
	sessionData: TrackerSessionSnapshot,
	presentation: DashboardLiveSessionPresentation.WifiOnly,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	modifier: Modifier = Modifier,
) {
	require(sessionData.id == presentation.segmentId)
	RadioOnlyTrackingContent(
		title = stringResource(R.string.dashboard_live_wifi_title),
		tag = "dashboard_wifi_only_tracking",
		presentation = presentation.history,
		bottomClearance = bottomClearance,
		modifier = modifier,
	)
}

@Composable
internal fun CellOnlyTrackingContent(
	sessionData: TrackerSessionSnapshot,
	presentation: DashboardLiveSessionPresentation.CellOnly,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	modifier: Modifier = Modifier,
) {
	require(sessionData.id == presentation.segmentId)
	RadioOnlyTrackingContent(
		title = stringResource(R.string.dashboard_live_cell_title),
		tag = "dashboard_cell_only_tracking",
		presentation = presentation.history,
		bottomClearance = bottomClearance,
		modifier = modifier,
	)
}

@Composable
private fun RadioOnlyTrackingContent(
	title: String,
	tag: String,
	presentation: com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioHistoryValue,
	bottomClearance: Dp,
	modifier: Modifier,
) {
	LazyColumn(
		modifier = modifier
			.fillMaxSize()
			.padding(horizontal = RidgelineSpacing.Lg)
			.testTag(tag),
		contentPadding = PaddingValues(bottom = bottomClearance),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		item(key = tag) {
			Card(
				modifier = Modifier.fillMaxWidth(),
				colors = CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer,
				),
				shape = RidgelineCardDefaults.shape,
			) {
				Column(
					modifier = Modifier.padding(RidgelineSpacing.Lg),
					verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
				) {
					Text(
						text = title,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
					DashboardRadioHistoryFacts(presentation)
					Text(
						text = stringResource(R.string.dashboard_radio_location_excluded),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
				}
			}
		}
	}
}
