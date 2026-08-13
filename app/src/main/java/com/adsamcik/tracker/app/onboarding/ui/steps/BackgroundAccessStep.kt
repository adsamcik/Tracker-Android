package com.adsamcik.tracker.app.onboarding.ui.steps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.background.BackgroundReliabilityCard
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Optional onboarding step that helps the user keep tracking alive in the
 * background by excluding the app from battery optimization (and, on Samsung,
 * the "Sleeping apps" list). Skippable — the Continue action proceeds regardless.
 */
@Composable
fun BackgroundAccessStep(
	state: SetupUiState,
	onBackgroundLocationResult: (Boolean) -> Unit,
	onDeclineBackgroundLocation: () -> Unit,
	onContinue: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val grantRoute = backgroundLocationGrantRoute(Build.VERSION.SDK_INT)
	val backgroundPermissionLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
		onBackgroundLocationResult,
	)
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

		if (state.needsBackgroundLocationPermission && state.locationPermissionGranted) {
			BackgroundLocationDisclosure(
				state = state,
				onGrant = {
					when (grantRoute) {
						BackgroundLocationGrantRoute.APP_LOCATION_SETTINGS -> context.openAppLocationSettings()
						BackgroundLocationGrantRoute.RUNTIME_PERMISSION ->
							backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
						BackgroundLocationGrantRoute.UNAVAILABLE -> Unit
					}
				},
				onDecline = onDeclineBackgroundLocation,
			)

			Spacer(modifier = Modifier.height(RidgelineSpacing.Lg))
		}

		BackgroundReliabilityCard(modifier = Modifier.fillMaxWidth())

		Spacer(modifier = Modifier.height(RidgelineSpacing.Md))

		Text(
			text = stringResource(R.string.setup_background_access_optional),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

internal enum class BackgroundLocationGrantRoute {
	UNAVAILABLE,
	RUNTIME_PERMISSION,
	APP_LOCATION_SETTINGS,
}

internal fun backgroundLocationGrantRoute(apiLevel: Int): BackgroundLocationGrantRoute = when {
	apiLevel >= Build.VERSION_CODES.R -> BackgroundLocationGrantRoute.APP_LOCATION_SETTINGS
	apiLevel >= Build.VERSION_CODES.Q -> BackgroundLocationGrantRoute.RUNTIME_PERMISSION
	else -> BackgroundLocationGrantRoute.UNAVAILABLE
}

@Composable
private fun BackgroundLocationDisclosure(
	state: SetupUiState,
	onGrant: () -> Unit,
	onDecline: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	Card(
		modifier = modifier
			.fillMaxWidth()
			.testTag("setup_background_location_disclosure"),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
			contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
		),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(RidgelineSpacing.Lg),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
		) {
			Text(
				text = stringResource(R.string.setup_background_location_title),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
			)
			when {
				state.backgroundLocationGranted -> Text(
					text = stringResource(R.string.setup_perm_granted),
					style = MaterialTheme.typography.bodyLarge,
				)
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Text(
					text = stringResource(
						R.string.setup_background_location_disclosure,
						context.backgroundPermissionOptionLabel(),
					),
					style = MaterialTheme.typography.bodyLarge,
				)
				else -> Text(
					text = stringResource(R.string.setup_perm_location_bg_why),
					style = MaterialTheme.typography.bodyLarge,
				)
			}

			if (!state.backgroundLocationGranted) {
				Row(horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm)) {
					Button(
						onClick = onGrant,
						modifier = Modifier.testTag("setup_background_location_settings"),
					) {
						Text(
							text = stringResource(
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
									R.string.setup_background_location_open_settings
								} else {
									R.string.setup_perm_grant
								},
							),
						)
					}
					TextButton(
						onClick = onDecline,
						modifier = Modifier.testTag("setup_background_location_decline"),
					) {
						Text(stringResource(R.string.setup_background_location_decline))
					}
				}
			}

			if (state.backgroundLocationDeclined || state.manualLocationOnly) {
				Text(
					text = stringResource(R.string.setup_background_location_manual_only),
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.testTag("setup_background_location_manual_only"),
				)
			}
		}
	}
}

internal fun Context.backgroundPermissionOptionLabel(): CharSequence =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		packageManager.backgroundPermissionOptionLabel
	} else {
		""
	}

internal fun Context.appLocationSettingsIntent(): Intent = Intent(
	Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
	Uri.fromParts("package", packageName, null),
).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun Context.openAppLocationSettings() {
	startActivity(appLocationSettingsIntent())
}
