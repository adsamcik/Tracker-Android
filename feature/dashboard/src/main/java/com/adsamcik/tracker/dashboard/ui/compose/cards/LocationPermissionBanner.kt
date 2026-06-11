package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R

/**
 * Permission-denied banner shown when ACCESS_FINE_LOCATION (or COARSE) is missing
 * after the user has already onboarded. Surfaces explicitly that tracking is paused
 * and offers a tap-to-grant affordance. Solves the QC resilience-pass MINOR where
 * a permission revoke + relaunch left the Dashboard rendering as if nothing changed.
 */
@Composable
internal fun LocationPermissionBanner(
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val bannerCd = stringResource(R.string.dashboard_permission_banner_cd)
	Card(
		onClick = onRequestPermission,
		modifier = modifier
			.fillMaxWidth()
			.testTag("dashboard_permission_banner")
			.semantics {
				contentDescription = bannerCd
			},
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
			contentColor = MaterialTheme.colorScheme.onErrorContainer,
		),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = Icons.Default.LocationOff,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onErrorContainer,
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.dashboard_permission_banner_title),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
				)
				Text(
					text = stringResource(R.string.dashboard_permission_banner_subtitle),
					style = MaterialTheme.typography.bodyMedium,
				)
			}
		}
	}
}

