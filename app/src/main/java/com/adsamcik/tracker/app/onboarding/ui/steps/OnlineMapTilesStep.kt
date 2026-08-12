package com.adsamcik.tracker.app.onboarding.ui.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Step 4 – Online map tiles.
 *
 * Pure choice screen: "Use online tiles (recommended)" vs "Offline only".
 * Defaults to online for the easiest map experience. The privacy disclosure
 * remains visible and users can choose the bundled offline map before setup
 * writes the preference.
 *
 * Provider/URL configuration is intentionally NOT exposed here — keeping
 * the onboarding step minimal and the full picker lives in
 * Settings → Map → Online map tiles. When the user opts in here the app
 * uses the default provider (`OpenFreeMap`); they can change it later.
 */
@Composable
fun OnlineMapTilesStep(
	enabled: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onComplete: () -> Unit,
	modifier: Modifier = Modifier,
) {
	SetupStepScaffold(
		actionText = stringResource(R.string.setup_start_exploring),
		actionTestTag = "setup_cta_online_tiles_complete",
		onAction = onComplete,
		modifier = modifier,
		bottomPaddingTestTag = "setup_online_tiles_scroll_bottom_padding",
	) {
		Spacer(modifier = Modifier.height(12.dp))

		Text(
			text = stringResource(R.string.setup_online_tiles_title),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
		)

		Spacer(modifier = Modifier.height(8.dp))

		Text(
			text = stringResource(R.string.setup_online_tiles_subtitle),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Spacer(modifier = Modifier.height(20.dp))

		ChoiceCard(
			icon = Icons.Default.CloudQueue,
			title = stringResource(R.string.setup_online_tiles_on_title),
			description = stringResource(R.string.setup_online_tiles_on_desc),
			selected = enabled,
			onSelect = { onEnabledChange(true) },
			testTag = "setup_online_tiles_on_choice",
		)

		Spacer(modifier = Modifier.height(12.dp))

		ChoiceCard(
			icon = Icons.Default.CloudOff,
			title = stringResource(R.string.setup_online_tiles_off_title),
			description = stringResource(R.string.setup_online_tiles_off_desc),
			selected = !enabled,
			onSelect = { onEnabledChange(false) },
			testTag = "setup_online_tiles_off_choice",
		)

		Spacer(modifier = Modifier.height(20.dp))

		// Privacy disclosure is always visible regardless of selection so the
		// user sees what the on-state implies before they pick it.
		PrivacyDisclosureRow(
			text = stringResource(R.string.setup_online_tiles_privacy),
		)

		Spacer(modifier = Modifier.height(12.dp))

		Text(
			text = stringResource(R.string.setup_online_tiles_change_later),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun ChoiceCard(
	icon: ImageVector,
	title: String,
	description: String,
	selected: Boolean,
	onSelect: () -> Unit,
	testTag: String,
	modifier: Modifier = Modifier,
) {
	GlassCard(
		modifier = modifier
			.fillMaxWidth()
			.selectable(
				selected = selected,
				onClick = onSelect,
				role = Role.RadioButton,
			)
			.testTag(testTag),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				modifier = Modifier.size(28.dp),
			)

			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = description,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			RadioButton(
				selected = selected,
				onClick = null,
			)
		}
	}
}

@Composable
private fun PrivacyDisclosureRow(text: String, modifier: Modifier = Modifier) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = 4.dp, end = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Default.PrivacyTip,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(20.dp),
		)
		Text(
			text = text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
