package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.background.BackgroundReliabilityCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Optional onboarding step that helps the user keep tracking alive in the
 * background by excluding the app from battery optimization (and, on Samsung,
 * the "Sleeping apps" list). Skippable — the Continue action proceeds regardless.
 */
@Composable
fun BackgroundAccessStep(
	onContinue: () -> Unit,
	modifier: Modifier = Modifier,
) {
	SetupStepScaffold(
		actionText = stringResource(R.string.setup_continue),
		actionTestTag = "setup_cta_background_access",
		onAction = onContinue,
		modifier = modifier,
		bottomPaddingTestTag = "setup_background_access_scroll_bottom_padding",
	) {
		Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
		) {
			Box(
				modifier = Modifier
					.size(56.dp)
					.clip(MaterialTheme.shapes.large)
					.background(MaterialTheme.colorScheme.primaryContainer),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Default.BatteryChargingFull,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.size(30.dp),
				)
			}

			Text(
				text = stringResource(R.string.background_reliability_title),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
		}

		Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

		Text(
			text = stringResource(R.string.setup_background_access_subtitle),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Spacer(modifier = Modifier.height(RidgelineSpacing.Lg))

		BackgroundReliabilityCard(modifier = Modifier.fillMaxWidth())

		Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

		Text(
			text = stringResource(R.string.setup_background_access_optional),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
