package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability

@Composable
internal fun AutomaticTrackingAvailabilityCard(
	uiState: TrackingSettingsUiState,
	modifier: Modifier = Modifier,
) {
	if (!uiState.autoTrackingEnabled || uiState.automaticTrackingOperational) return
	val unavailable = uiState.automaticControlAvailability as?
		AutomaticTrackingOperationalAvailability.Unavailable
	val summary = when {
		unavailable != null -> stringResource(
			com.adsamcik.tracker.R.string.settings_automatic_policy_unavailable_summary,
		)
		uiState.automaticTrackingPermissionRequired -> stringResource(
			com.adsamcik.tracker.R.string.settings_automatic_permission_required_summary,
		)
		else -> stringResource(
			com.adsamcik.tracker.R.string.settings_automatic_unavailable_summary,
		)
	}
	Card(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.testTag("automaticTrackingUnavailable"),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.secondaryContainer,
		),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.Top,
		) {
			Icon(Icons.Default.Warning, contentDescription = null)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(
					text = stringResource(
						com.adsamcik.tracker.R.string.settings_automatic_unavailable_title,
					),
					style = MaterialTheme.typography.titleMedium,
				)
				Text(text = summary, style = MaterialTheme.typography.bodyMedium)
				unavailable?.let { status ->
					Text(
						text = stringResource(
							com.adsamcik.tracker.R.string.settings_automatic_status_code,
							status.reason.stableCode,
						),
						modifier = Modifier.testTag("automaticTrackingUnavailableCode"),
						style = MaterialTheme.typography.labelSmall,
					)
				}
			}
		}
	}
}

@Composable
internal fun AmbientSourcePurposeSettings(
	uiState: TrackingSettingsUiState,
	onLocationEnabledChanged: (Boolean) -> Unit,
	onStepsEnabledChanged: (Boolean) -> Unit,
	onWifiEnabledChanged: (Boolean) -> Unit,
	onCellEnabledChanged: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = stringResource(com.adsamcik.tracker.R.string.settings_ambient_sources_summary),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Card(
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.surfaceContainer,
			),
		) {
			AmbientSourceSettingItem(
				source = AmbientTrackingSource.STEPS,
				title = stringResource(
					com.adsamcik.tracker.R.string.settings_ambient_steps_title,
				),
				enabledIntent = uiState.ambientStepsEnabled,
				availability = uiState.ambientSourceAvailability.getValue(
					AmbientTrackingSource.STEPS,
				),
				onEnabledChanged = onStepsEnabledChanged,
			)
			HorizontalDivider()
			AmbientSourceSettingItem(
				source = AmbientTrackingSource.LOCATION,
				title = stringResource(
					com.adsamcik.tracker.R.string.settings_ambient_location_title,
				),
				enabledIntent = uiState.ambientLocationEnabled,
				availability = uiState.ambientSourceAvailability.getValue(
					AmbientTrackingSource.LOCATION,
				),
				onEnabledChanged = onLocationEnabledChanged,
			)
			HorizontalDivider()
			AmbientSourceSettingItem(
				source = AmbientTrackingSource.WIFI,
				title = stringResource(
					com.adsamcik.tracker.R.string.settings_ambient_wifi_title,
				),
				enabledIntent = uiState.ambientWifiEnabled,
				availability = uiState.ambientSourceAvailability.getValue(
					AmbientTrackingSource.WIFI,
				),
				onEnabledChanged = onWifiEnabledChanged,
			)
			HorizontalDivider()
			AmbientSourceSettingItem(
				source = AmbientTrackingSource.CELL,
				title = stringResource(
					com.adsamcik.tracker.R.string.settings_ambient_cell_title,
				),
				enabledIntent = uiState.ambientCellEnabled,
				availability = uiState.ambientSourceAvailability.getValue(
					AmbientTrackingSource.CELL,
				),
				onEnabledChanged = onCellEnabledChanged,
			)
		}
		Text(
			text = stringResource(com.adsamcik.tracker.R.string.settings_ambient_retention_summary),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.testTag("ambientRetentionExplanation"),
		)
	}
}

@Composable
private fun AmbientSourceSettingItem(
	source: AmbientTrackingSource,
	title: String,
	enabledIntent: Boolean,
	availability: AmbientSourceOperationalAvailability,
	onEnabledChanged: (Boolean) -> Unit,
) {
	val operationalSummary = ambientOperationalSummary(availability)
	val status = if (!enabledIntent) {
		stringResource(com.adsamcik.tracker.R.string.settings_ambient_source_off_summary)
	} else {
		stringResource(
			com.adsamcik.tracker.R.string.settings_ambient_source_enabled_status,
			operationalSummary,
		)
	}
	val semanticsState = stringResource(
		when {
			!enabledIntent -> com.adsamcik.tracker.R.string.settings_ambient_semantics_off
			availability.isOperational ->
				com.adsamcik.tracker.R.string.settings_ambient_semantics_enabled_ready
			else -> com.adsamcik.tracker.R.string.settings_ambient_semantics_enabled_unavailable
		},
	)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surfaceContainer)
			.toggleable(
				value = enabledIntent,
				role = Role.Switch,
				onValueChange = onEnabledChanged,
			)
			.semantics(mergeDescendants = true) {
				stateDescription = semanticsState
			}
			.heightIn(min = 76.dp)
			.padding(horizontal = 16.dp, vertical = 12.dp)
			.testTag("ambientSource-${source.name.lowercase()}"),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Text(text = title, style = MaterialTheme.typography.titleMedium)
			Text(
				text = status,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.width(16.dp))
		Switch(checked = enabledIntent, onCheckedChange = null)
	}
}

@Composable
private fun ambientOperationalSummary(
	availability: AmbientSourceOperationalAvailability,
): String {
	if (
		availability.reason ==
		AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL
	) {
		return stringResource(
			com.adsamcik.tracker.R.string
				.settings_ambient_steps_health_connect_background_optional,
		)
	}
	if (availability.isOperational) {
		return stringResource(
			when (availability.mechanism) {
				AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS ->
					com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_ready
				AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS ->
					com.adsamcik.tracker.R.string.settings_ambient_steps_recording_ready
				AmbientAcquisitionMechanism.PASSIVE_LOCATION ->
					com.adsamcik.tracker.R.string.settings_ambient_location_ready
				AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS ->
					com.adsamcik.tracker.R.string.settings_ambient_wifi_ready
				AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS ->
					com.adsamcik.tracker.R.string.settings_ambient_cell_ready
				null -> com.adsamcik.tracker.R.string.settings_ambient_reconciliation_pending
			},
		)
	}
	return stringResource(
		when (availability.reason) {
			AmbientSourceUnavailableReason.RETENTION_POLICY_UNAVAILABLE ->
				com.adsamcik.tracker.R.string.settings_ambient_retention_unavailable
			AmbientSourceUnavailableReason.RECONCILIATION_PENDING ->
				com.adsamcik.tracker.R.string.settings_ambient_reconciliation_pending
			AmbientSourceUnavailableReason.ROLLOUT_CONTAINED ->
				com.adsamcik.tracker.R.string.settings_ambient_rollout_contained
			AmbientSourceUnavailableReason.HEALTH_CONNECT_STEPS_PERMISSION_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_permission
			AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_background_optional
			AmbientSourceUnavailableReason.HEALTH_CONNECT_UNAVAILABLE ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_unavailable
			AmbientSourceUnavailableReason.HEALTH_CONNECT_UPDATE_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_update
			AmbientSourceUnavailableReason.HEALTH_CONNECT_PROBE_FAILED ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_probe_failed
			AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_recording_permission
			AmbientSourceUnavailableReason.LOCAL_RECORDING_UNAVAILABLE ->
				com.adsamcik.tracker.R.string.settings_ambient_steps_recording_unavailable
			AmbientSourceUnavailableReason.BACKGROUND_LOCATION_PERMISSION_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_location_permission
			AmbientSourceUnavailableReason.WIFI_SCAN_PERMISSION_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_wifi_permission
			AmbientSourceUnavailableReason.CELL_SCAN_PERMISSION_REQUIRED ->
				com.adsamcik.tracker.R.string.settings_ambient_cell_permission
			AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE,
			AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
			null,
			-> com.adsamcik.tracker.R.string.settings_ambient_provider_unavailable
		},
	)
}
