package com.adsamcik.tracker.app.background

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Reusable card showing Android's observable background-restriction signals. It re-reads the
 * state on every resume so the status updates after the user returns from system settings.
 */
@Composable
fun BackgroundReliabilityCard(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current

	var reliabilityState by remember {
		mutableStateOf(BatteryOptimizationHelper.readReliabilityState(context))
	}

	// Refresh the status when the user comes back from the settings screen.
	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				reliabilityState = BatteryOptimizationHelper.readReliabilityState(context)
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}

	GlassCard(modifier = modifier.fillMaxWidth().testTag("background_reliability_card")) {
		Column(verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md)) {
			StatusRow(condition = reliabilityState.condition)

			Text(
				text = stringResource(reliabilityDescription(reliabilityState.condition)),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			if (reliabilityState.actionRequired) {
				PrimaryActionButton(
					text = stringResource(reliabilityAction(reliabilityState.condition)),
					onClick = {
						BatteryOptimizationHelper.openReliabilitySettings(context, reliabilityState)
					},
					modifier = Modifier
						.fillMaxWidth()
						.testTag("background_reliability_allow_button"),
				)
			}
		}
	}
}

@Composable
private fun StatusRow(condition: BackgroundReliabilityCondition) {
	val tint by animateColorAsState(
		targetValue = when (condition) {
			BackgroundReliabilityCondition.BACKGROUND_RESTRICTED,
			BackgroundReliabilityCondition.BATTERY_OPTIMIZED,
			-> MaterialTheme.colorScheme.error
			BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION ->
				MaterialTheme.colorScheme.primary
			BackgroundReliabilityCondition.UNKNOWN ->
				MaterialTheme.colorScheme.onSurfaceVariant
		},
		label = "background_reliability_status_tint",
	)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		Icon(
			imageVector = if (condition == BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION) {
				Icons.Default.CheckCircle
			} else {
				Icons.Default.BatteryAlert
			},
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(24.dp),
		)
		Text(
			text = stringResource(reliabilityStatus(condition)),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = tint,
		)
	}
}

private fun reliabilityStatus(condition: BackgroundReliabilityCondition): Int = when (condition) {
	BackgroundReliabilityCondition.BACKGROUND_RESTRICTED ->
		R.string.background_reliability_status_restricted_detected
	BackgroundReliabilityCondition.BATTERY_OPTIMIZED ->
		R.string.background_reliability_status_optimization_detected
	BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION ->
		R.string.background_reliability_status_no_limit_detected
	BackgroundReliabilityCondition.UNKNOWN -> R.string.background_reliability_status_unknown
}

private fun reliabilityDescription(condition: BackgroundReliabilityCondition): Int = when (condition) {
	BackgroundReliabilityCondition.BACKGROUND_RESTRICTED ->
		R.string.background_reliability_restricted_detected_desc
	BackgroundReliabilityCondition.BATTERY_OPTIMIZED ->
		R.string.background_reliability_optimization_detected_desc
	BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION ->
		R.string.background_reliability_no_limit_detected_desc
	BackgroundReliabilityCondition.UNKNOWN -> R.string.background_reliability_unknown_desc
}

private fun reliabilityAction(condition: BackgroundReliabilityCondition): Int = when (condition) {
	BackgroundReliabilityCondition.BACKGROUND_RESTRICTED ->
		R.string.background_reliability_open_app_settings_action
	BackgroundReliabilityCondition.BATTERY_OPTIMIZED ->
		R.string.background_reliability_open_battery_settings_action
	BackgroundReliabilityCondition.NO_DETECTED_RESTRICTION,
	BackgroundReliabilityCondition.UNKNOWN,
	-> error("No remediation action for $condition")
}

/**
 * A compact entry-point row for the settings screen that links to the same
 * background-reliability controls. Reused so settings and onboarding stay in sync.
 */
@Composable
fun BackgroundReliabilitySettingsEntry(modifier: Modifier = Modifier) {
	Column(modifier = modifier.padding(horizontal = RidgelineSpacing.Lg)) {
		Text(
			text = stringResource(R.string.background_reliability_title),
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Spacer(modifier = Modifier.height(RidgelineSpacing.Sm))
		BackgroundReliabilityCard()
	}
}
