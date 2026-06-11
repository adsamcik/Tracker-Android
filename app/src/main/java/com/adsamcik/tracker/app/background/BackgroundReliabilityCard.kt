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
import androidx.compose.material3.OutlinedButton
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
 * Reusable card explaining and controlling battery-optimization exemption, shown
 * both in onboarding and in Tracking settings. Reads the current exemption state
 * and re-reads it on every resume so the status updates after the user returns
 * from the system settings screen.
 */
@Composable
fun BackgroundReliabilityCard(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current

	var isExempt by remember {
		mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
	}
	val isSamsung = remember { BatteryOptimizationHelper.isSamsungDevice() }

	// Refresh the status when the user comes back from the settings screen.
	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}

	GlassCard(modifier = modifier.fillMaxWidth().testTag("background_reliability_card")) {
		Column(verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md)) {
			StatusRow(isExempt = isExempt)

			Text(
				text = stringResource(
					if (isExempt) {
						R.string.background_reliability_exempt_desc
					} else {
						R.string.background_reliability_optimized_desc
					},
				),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			if (!isExempt) {
				PrimaryActionButton(
					text = stringResource(R.string.background_reliability_allow_action),
					onClick = { BatteryOptimizationHelper.openBatteryOptimizationSettings(context) },
					modifier = Modifier
						.fillMaxWidth()
						.testTag("background_reliability_allow_button"),
				)
			}

			if (isSamsung) {
				Text(
					text = stringResource(R.string.background_reliability_samsung_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedButton(
					onClick = { BatteryOptimizationHelper.openManufacturerPowerSettings(context) },
					modifier = Modifier
						.fillMaxWidth()
						.testTag("background_reliability_samsung_button"),
				) {
					Text(text = stringResource(R.string.background_reliability_samsung_action))
				}
			}
		}
	}
}

@Composable
private fun StatusRow(isExempt: Boolean) {
	val tint by animateColorAsState(
		targetValue = if (isExempt) {
			MaterialTheme.colorScheme.primary
		} else {
			MaterialTheme.colorScheme.error
		},
		label = "background_reliability_status_tint",
	)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		Icon(
			imageVector = if (isExempt) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(24.dp),
		)
		Text(
			text = stringResource(
				if (isExempt) {
					R.string.background_reliability_status_exempt
				} else {
					R.string.background_reliability_status_optimized
				},
			),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = tint,
		)
	}
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
