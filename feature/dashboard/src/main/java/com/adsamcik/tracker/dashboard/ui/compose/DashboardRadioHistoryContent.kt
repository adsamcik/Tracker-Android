@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioCoverage
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioHistoryValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioOrigin
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardRadioProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource

/** Identity-free radio status and counts shared by live and recent Dashboard products. */
@Composable
internal fun DashboardRadioHistoryFacts(
	value: DashboardRadioHistoryValue,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = stringResource(value.state.labelResource),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(value.origin.labelResource),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(value.coverage.labelResource),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (value.deliveryCount == 0) {
			Text(
				text = stringResource(value.emptyExplanationResource),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
		} else {
			Text(
				text = pluralStringResource(
					when (value.source) {
						HistorySource.WIFI -> R.plurals.dashboard_radio_wifi_retained_records
						HistorySource.CELL -> R.plurals.dashboard_radio_cell_retained_records
						else -> error("Only radio sources have radio history facts")
					},
					value.retainedRecordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
					value.retainedRecordCount,
				),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = pluralStringResource(
					R.plurals.dashboard_radio_retained_deliveries,
					value.deliveryCount,
					value.deliveryCount,
				),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (value.partialDeliveryCount > 0) {
				Text(
					text = pluralStringResource(
						R.plurals.dashboard_radio_partial_deliveries,
						value.partialDeliveryCount,
						value.partialDeliveryCount,
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

internal val DashboardRadioProductState.labelResource: Int
	get() = when (this) {
		DashboardRadioProductState.MATERIALIZING -> R.string.dashboard_radio_state_materializing
		DashboardRadioProductState.PARTIAL -> R.string.dashboard_radio_state_partial
		DashboardRadioProductState.READY -> R.string.dashboard_radio_state_ready
		DashboardRadioProductState.UNAVAILABLE -> R.string.dashboard_radio_state_unavailable
		DashboardRadioProductState.MISSING -> R.string.dashboard_radio_state_missing
		DashboardRadioProductState.DELETED -> R.string.dashboard_radio_state_deleted
		DashboardRadioProductState.UNVERIFIABLE -> R.string.dashboard_radio_state_unverifiable
		DashboardRadioProductState.FAILED -> R.string.dashboard_radio_state_failed
	}

internal val DashboardRadioOrigin.labelResource: Int
	get() = when (this) {
		DashboardRadioOrigin.LOCAL -> R.string.dashboard_radio_origin_local
		DashboardRadioOrigin.IMPORTED -> R.string.dashboard_radio_origin_imported
	}

internal val DashboardRadioCoverage.labelResource: Int
	get() = when (this) {
		DashboardRadioCoverage.NONE -> R.string.dashboard_radio_coverage_none
		DashboardRadioCoverage.PARTIAL -> R.string.dashboard_radio_coverage_partial
		DashboardRadioCoverage.COMPLETE -> R.string.dashboard_radio_coverage_complete
		DashboardRadioCoverage.UNKNOWN -> R.string.dashboard_radio_coverage_unknown
	}

private val DashboardRadioHistoryValue.emptyExplanationResource: Int
	get() = when (state) {
		DashboardRadioProductState.MATERIALIZING -> R.string.dashboard_radio_waiting_for_evidence
		DashboardRadioProductState.DELETED -> R.string.dashboard_radio_deleted_explanation
		DashboardRadioProductState.UNVERIFIABLE,
		DashboardRadioProductState.FAILED -> R.string.dashboard_radio_unverifiable_explanation
		DashboardRadioProductState.PARTIAL,
		DashboardRadioProductState.READY,
		DashboardRadioProductState.UNAVAILABLE,
		DashboardRadioProductState.MISSING -> R.string.dashboard_radio_no_retained_evidence
	}
