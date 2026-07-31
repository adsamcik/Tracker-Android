package com.adsamcik.tracker.app.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator

/**
 * Dialog prompting users to upgrade from approximate to precise location.
 * Shown after several successful coarse-only tracking sessions or when
 * user attempts an action that would benefit from higher accuracy.
 *
 * Follows Apple-style philosophy:
 * - Clear benefit explanation (better route accuracy, map detail)
 * - Honest battery trade-off disclosure
 * - Non-blocking (user can dismiss and continue with coarse)
 * - Doesn't re-prompt if previously dismissed
 *
 * @param onDismiss Callback when user dismisses without upgrading
 * @param onUpgrade Callback when user chooses to upgrade
 * @param reason Optional context explaining why upgrade is beneficial
 */
@Composable
fun UpgradeToPrecisePrompt(
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    reason: UpgradeReason = UpgradeReason.GENERAL,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.upgrade_to_precise_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Context-specific benefit explanation
                Text(
                    text = stringResource(reason.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Benefits list
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_to_precise_benefits_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        BenefitItem(stringResource(R.string.upgrade_benefit_accurate_routes))
                        BenefitItem(stringResource(R.string.upgrade_benefit_detailed_maps))
                        BenefitItem(stringResource(R.string.upgrade_benefit_precise_stats))
                    }
                }

                // Battery impact disclosure
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.upgrade_battery_impact_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            BatteryImpactIndicator(
                                impact = BatteryImpact.MODERATE,
                                compact = true
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = stringResource(R.string.upgrade_battery_impact_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpgrade) {
                Text(stringResource(R.string.upgrade_to_precise_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.upgrade_to_precise_dismiss))
            }
        },
        modifier = modifier
    )
}

/**
 * Context-specific reasons for suggesting precision upgrade
 */
enum class UpgradeReason(val descriptionRes: Int) {
    /**
     * General suggestion after several coarse-only sessions
     */
    GENERAL(R.string.upgrade_reason_general),

    /**
     * User attempting detailed route analysis
     */
    ROUTE_ANALYSIS(R.string.upgrade_reason_route_analysis),

    /**
     * User viewing heatmap with low detail
     */
    HEATMAP_DETAIL(R.string.upgrade_reason_heatmap),

    /**
     * User attempting distance/speed challenges
     */
    CHALLENGES(R.string.upgrade_reason_challenges)
}

/**
 * Single benefit list item with bullet point
 */
@Composable
private fun BenefitItem(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
