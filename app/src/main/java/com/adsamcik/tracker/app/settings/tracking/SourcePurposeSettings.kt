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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason

@Composable
internal fun AutomaticTrackingAvailabilityCard(
	uiState: TrackingSettingsUiState,
	modifier: Modifier = Modifier,
	onEnableTransitions: () -> Unit = {},
	onPermission: () -> Unit = {},
) {
	if (!uiState.autoTrackingEnabled || uiState.automaticTrackingOperational) {
		return
	}
	val unavailable = uiState.automaticControlAvailability as?
		AutomaticTrackingOperationalAvailability.Unavailable
	val summary = when {
		!uiState.transitionDetectionEnabled -> stringResource(
			com.adsamcik.tracker.R.string.automatic_control_enable_transitions_summary,
		)
		uiState.automaticTrackingPermissionRequired -> stringResource(
			com.adsamcik.tracker.R.string.settings_automatic_permission_required_summary,
		)
		unavailable?.reason == AutomaticTrackingUnavailableReason.PROVIDER_UNAVAILABLE -> stringResource(
			com.adsamcik.tracker.R.string.automatic_control_provider_unavailable_summary,
		)
		else -> stringResource(
			com.adsamcik.tracker.R.string.automatic_control_pending_summary,
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
				if (!uiState.transitionDetectionEnabled) {
					TextButton(onClick = onEnableTransitions,
						modifier = Modifier.testTag("automaticEnableTransitions")) {
						Text(stringResource(com.adsamcik.tracker.R.string.automatic_control_enable_transitions))
					}
				}
				if (uiState.automaticTrackingPermissionRequired) {
					TextButton(onClick = onPermission, modifier = Modifier.testTag("automaticAllowAccess")) {
						Text(stringResource(com.adsamcik.tracker.R.string.settings_location_allow_access))
					}
				}
			}
		}
	}
}

@Composable
internal fun AmbientSourcePurposeSettings(
	uiState: TrackingSettingsUiState,
	onLocationEnabledChanged: (Boolean) -> Unit,
	onWifiEnabledChanged: (Boolean) -> Unit,
	onCellEnabledChanged: (Boolean) -> Unit,
	onLocationModeChanged: (LocationBackgroundMode) -> Unit = {},
	onLocationBalanceChanged: (Int) -> Unit = {},
	onLocationAssistanceChanged: (Boolean) -> Unit = {},
	onLocationPermission: () -> Unit = {},
	onMotionPermission: () -> Unit = {},
	onSourcePermission: (TrackingSourceComponent) -> Unit = {},
	onWifiNotificationChanged: (Boolean) -> Unit = {},
	onCellNotificationChanged: (Boolean) -> Unit = {},
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
			AdaptiveLocationSettings(
				state = uiState,
				onEnabledChanged = onLocationEnabledChanged,
				onModeChanged = onLocationModeChanged,
				onBalanceChanged = onLocationBalanceChanged,
				onAssistanceChanged = onLocationAssistanceChanged,
				onLocationPermission = onLocationPermission,
				onMotionPermission = onMotionPermission,
			)
			HorizontalDivider()
			AmbientWifiPurposeSettings(
				uiState = uiState,
				onWifiEnabledChanged = onWifiEnabledChanged,
				onWifiNotificationChanged = onWifiNotificationChanged,
				onSourcePermission = onSourcePermission,
			)
			HorizontalDivider()
			AmbientCellPurposeSettings(
				uiState = uiState,
				onCellEnabledChanged = onCellEnabledChanged,
				onCellNotificationChanged = onCellNotificationChanged,
				onSourcePermission = onSourcePermission,
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
private fun AmbientWifiPurposeSettings(
	uiState: TrackingSettingsUiState,
	onWifiEnabledChanged: (Boolean) -> Unit,
	onWifiNotificationChanged: (Boolean) -> Unit,
	onSourcePermission: (TrackingSourceComponent) -> Unit,
) {
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
	if (uiState.ambientWifiEnabled) {
		BackgroundRadioNotificationSetting(
		AmbientTrackingSource.WIFI, uiState.backgroundWifiNotificationEnabled, onWifiNotificationChanged)
	}
	SourcePermissionRepair(TrackingSourceComponent.WIFI,
		uiState.ambientWifiEnabled && !uiState.wifiPermissionGranted, onSourcePermission,
		"ambientSourcePermission-wifi")
}

@Composable
private fun AmbientCellPurposeSettings(
	uiState: TrackingSettingsUiState,
	onCellEnabledChanged: (Boolean) -> Unit,
	onCellNotificationChanged: (Boolean) -> Unit,
	onSourcePermission: (TrackingSourceComponent) -> Unit,
) {
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
	if (uiState.ambientCellEnabled) {
		BackgroundRadioNotificationSetting(
		AmbientTrackingSource.CELL, uiState.backgroundCellNotificationEnabled, onCellNotificationChanged)
	}
	SourcePermissionRepair(TrackingSourceComponent.CELL,
		uiState.ambientCellEnabled && !uiState.cellPermissionGranted, onSourcePermission,
		"ambientSourcePermission-cell")
}

@Composable
private fun BackgroundRadioNotificationSetting(
	source: AmbientTrackingSource,
	enabled: Boolean,
	onChanged: (Boolean) -> Unit,
) {
	val label = stringResource(com.adsamcik.tracker.R.string.settings_radio_notification_title)
	val sourceName = stringResource(when (source) {
		AmbientTrackingSource.WIFI -> com.adsamcik.tracker.tracker.R.string.notification_background_source_wifi
		AmbientTrackingSource.CELL -> com.adsamcik.tracker.tracker.R.string.notification_background_source_cell
		else -> com.adsamcik.tracker.tracker.R.string.notification_background_source_other
	})
	Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChanged)
		.semantics { contentDescription = sourceName }
		.heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 12.dp)
		.testTag("backgroundNotification-${source.name.lowercase()}"), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			Text(stringResource(com.adsamcik.tracker.R.string.settings_radio_notification_summary),
				style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Spacer(Modifier.width(16.dp))
		Switch(checked = enabled, onCheckedChange = null)
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
internal fun ambientOperationalSummary(
	availability: AmbientSourceOperationalAvailability,
): String {
	val summaryResource = when {
		availability.reason == AmbientSourceUnavailableReason.FOREGROUND_HOST_UNAVAILABLE ->
			com.adsamcik.tracker.R.string.settings_radio_notification_unavailable
		availability.reason == AmbientSourceUnavailableReason.LOCATION_REQUEST_ADJUSTED ->
			com.adsamcik.tracker.R.string.settings_location_request_adjusted
		availability.reason == AmbientSourceUnavailableReason.HEALTH_CONNECT_BACKGROUND_PERMISSION_OPTIONAL ->
			com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_background_optional
		availability.isOperational -> ambientMechanismSummaryResource(availability.mechanism)
		else -> ambientUnavailableSummaryResource(availability.reason)
	}
	return stringResource(summaryResource)
}

private fun ambientMechanismSummaryResource(
	mechanism: AmbientAcquisitionMechanism?,
): Int = when (mechanism) {
	AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS ->
		com.adsamcik.tracker.R.string.settings_ambient_steps_health_connect_ready
	AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS ->
		com.adsamcik.tracker.R.string.settings_ambient_steps_recording_ready
	AmbientAcquisitionMechanism.PASSIVE_LOCATION ->
		com.adsamcik.tracker.R.string.settings_ambient_location_ready
	AmbientAcquisitionMechanism.ADAPTIVE_LOCATION ->
		com.adsamcik.tracker.R.string.settings_location_adaptive_ready
	AmbientAcquisitionMechanism.WIFI_SCAN_RESULTS ->
		com.adsamcik.tracker.R.string.settings_ambient_wifi_ready
	AmbientAcquisitionMechanism.CELL_CHANGE_CALLBACKS ->
		com.adsamcik.tracker.R.string.settings_ambient_cell_ready
	null -> com.adsamcik.tracker.R.string.settings_ambient_reconciliation_pending
}

// Keep the finite unavailable-reason to existing copy mapping complete and visible.
@Suppress("CyclomaticComplexMethod")
private fun ambientUnavailableSummaryResource(
	reason: AmbientSourceUnavailableReason?,
): Int = when (reason) {
	AmbientSourceUnavailableReason.FOREGROUND_HOST_UNAVAILABLE ->
		com.adsamcik.tracker.R.string.settings_radio_notification_unavailable
	AmbientSourceUnavailableReason.USER_TURNED_OFF ->
		com.adsamcik.tracker.R.string.settings_ambient_user_turned_off
	AmbientSourceUnavailableReason.LOCATION_REQUEST_ADJUSTED ->
		com.adsamcik.tracker.R.string.settings_location_request_adjusted
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
}
