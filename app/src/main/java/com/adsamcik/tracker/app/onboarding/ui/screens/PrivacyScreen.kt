package com.adsamcik.tracker.app.onboarding.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.data.UserPreferences

@Composable
fun PrivacyScreen(
	preferences: UserPreferences,
	onPreferencesUpdate: (UserPreferences) -> Unit,
	onContinue: () -> Unit,
	onBack: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Spacer(modifier = Modifier.height(16.dp))

		Icon(
			imageVector = Icons.Default.Security,
			contentDescription = null,
			modifier = Modifier.size(64.dp),
			tint = MaterialTheme.colorScheme.primary
		)

		Spacer(modifier = Modifier.height(16.dp))

		Text(
			text = stringResource(R.string.onboarding_privacy_title),
			style = MaterialTheme.typography.headlineMedium,
			textAlign = TextAlign.Center,
			fontWeight = FontWeight.Bold
		)

		Spacer(modifier = Modifier.height(8.dp))

		Text(
			text = stringResource(R.string.onboarding_privacy_subtitle),
			style = MaterialTheme.typography.bodyLarge,
			textAlign = TextAlign.Center,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)

		Spacer(modifier = Modifier.height(24.dp))

		PrivacyPrincipleCard(
			icon = Icons.Default.PhoneAndroid,
			title = stringResource(R.string.privacy_local_storage_title),
			description = stringResource(R.string.privacy_local_storage_description)
		)

		Spacer(modifier = Modifier.height(12.dp))

		PrivacyPrincipleCard(
			icon = Icons.Default.CloudOff,
			title = stringResource(R.string.privacy_no_cloud_title),
			description = stringResource(R.string.privacy_no_cloud_description)
		)

		Spacer(modifier = Modifier.height(12.dp))

		PrivacyPrincipleCard(
			icon = Icons.Default.Code,
			title = stringResource(R.string.privacy_open_source_title),
			description = stringResource(R.string.privacy_open_source_description)
		)

		Spacer(modifier = Modifier.height(12.dp))

		PrivacyPrincipleCard(
			icon = Icons.Default.Lock,
			title = stringResource(R.string.privacy_your_control_title),
			description = stringResource(R.string.privacy_your_control_description)
		)

		Spacer(modifier = Modifier.height(24.dp))

		// Data storage preferences
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.surfaceVariant
			)
		) {
			Column(modifier = Modifier.padding(16.dp)) {
				Text(
					text = stringResource(R.string.privacy_data_storage_preferences),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold
				)

				Spacer(modifier = Modifier.height(12.dp))

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.privacy_auto_cleanup_title),
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Medium
						)
						Text(
							text = stringResource(R.string.privacy_auto_cleanup_description),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					Switch(
						checked = preferences.autoCleanupOldData,
						onCheckedChange = { enabled ->
							onPreferencesUpdate(preferences.copy(autoCleanupOldData = enabled))
						}
					)
				}
			}
		}

		Spacer(modifier = Modifier.height(16.dp))

		Text(
			text = stringResource(R.string.privacy_footer_note),
			style = MaterialTheme.typography.bodySmall,
			textAlign = TextAlign.Center,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 8.dp)
		)

		Spacer(modifier = Modifier.weight(1f))

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
				Text(stringResource(R.string.generic_back))
			}
			Button(onClick = onContinue, modifier = Modifier.weight(2f)) {
				Text(stringResource(R.string.generic_continue))
			}
		}
	}
}

@Composable
private fun PrivacyPrincipleCard(
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	title: String,
	description: String,
	modifier: Modifier = Modifier
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surface
		),
		border = CardDefaults.outlinedCardBorder()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary
			)
			Spacer(modifier = Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					text = description,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun PrivacyScreenPreview() {
	MaterialTheme {
		PrivacyScreen(
			preferences = UserPreferences(),
			onPreferencesUpdate = {},
			onContinue = {},
			onBack = {}
		)
	}
}

