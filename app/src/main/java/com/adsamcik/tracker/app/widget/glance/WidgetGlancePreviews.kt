package com.adsamcik.tracker.app.widget.glance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.PolicyTier

@Preview(showBackground = true, name = "Today summary")
@Composable
private fun TodaySummaryContentPreview() {
    AppTheme {
        WidgetPreviewCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("8.4 km", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Distance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WidgetPreviewStatsRow(
                    "Steps" to WidgetFormatters.formatSteps(10432),
                    "Duration" to WidgetFormatters.formatDuration(4_800_000L),
                    "Sessions" to "3",
                )
                Text(
                    text = "Goal ${WidgetFormatters.formatSteps(8_200)} / ${WidgetFormatters.formatSteps(10_000)} (${WidgetFormatters.formatGoalProgress(GoalProgress(8200, 10000, true).progress)})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Today summary empty")
@Composable
private fun TodaySummaryEmptyPreview() {
    AppTheme {
        WidgetPreviewCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No data today", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true, name = "Quick start idle")
@Composable
private fun QuickStartContentIdlePreview() {
    AppTheme {
        WidgetPreviewCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Start tracking", style = MaterialTheme.typography.titleMedium)
                    Text("Ready when you are", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("▶", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Preview(showBackground = true, name = "Quick start running")
@Composable
private fun QuickStartContentRunningPreview() {
    AppTheme {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Stop tracking", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                    Text(WidgetFormatters.formatDuration(5_400_000L), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                }
                Text("■", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Preview(showBackground = true, name = "Active session idle")
@Composable
private fun ActiveSessionIdleContentPreview() {
    AppTheme {
        WidgetPreviewCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("▶", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("No active session", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap to start", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true, name = "Active session tracking")
@Composable
private fun ActiveSessionTrackingContentPreview() {
    val summary = DailySummary(totalDistanceM = 5420f, totalSteps = 7810, totalDurationMs = 6_300_000L, sessionCount = 1)
    AppTheme {
        WidgetPreviewCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tracking active", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("👣 Walking • ${previewPolicyTierLabel(PolicyTier.ACTIVE)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("■", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                }
                Text("4.3 km/h", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                WidgetPreviewStatsRow(
                    "Distance" to formatDistancePreview(summary.totalDistanceM),
                    "Duration" to WidgetFormatters.formatDuration(summary.totalDurationMs),
                    "Steps" to WidgetFormatters.formatSteps(summary.totalSteps),
                    "Collections" to "24",
                )
                Text("Path preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(6) {
                        Surface(modifier = Modifier.size(10.dp), shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f + (it * 0.08f).coerceAtMost(0.4f))) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreviewCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = { content() },
        )
    }
}

@Composable
private fun WidgetPreviewStatsRow(vararg items: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.toList().chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rowItems.forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(value, style = MaterialTheme.typography.titleMedium)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun formatDistancePreview(distanceMeters: Float): String =
    if (distanceMeters >= 1000f) String.format("%.1f km", distanceMeters / 1000f) else "${distanceMeters.toInt()} m"

private fun previewPolicyTierLabel(tier: PolicyTier): String =
    tier.name.lowercase().replaceFirstChar { it.titlecase() }
