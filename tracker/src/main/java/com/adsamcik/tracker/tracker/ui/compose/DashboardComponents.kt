package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.PrimaryActionButton
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun LastSessionCard(
    session: TrackerSession?,
    modifier: Modifier = Modifier
) {
    if (session == null) return

    val context = LocalContext.current
    val settings = TrackerSettingsQuick.snapshot(context)
    val distance = context.resources.formatDistance(session.distanceInM, 1, settings.lengthSystem)
    val duration = (session.end - session.start).formatAsDuration(context)

    GlassCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Last Session",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = distance,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Distance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun WeeklyProgressRow(
    stats: List<Stat>,
    modifier: Modifier = Modifier
) {
    if (stats.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = "This Week",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Filter or map specific stats we want to show icons for
            stats.take(3).forEach { stat ->
                WeeklyStatItem(
                    stat = stat,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun WeeklyStatItem(
    stat: Stat,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                painter = painterResource(id = stat.iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stat.data.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(id = stat.nameRes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
fun DashboardControlBar(
    onStartClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 16.dp), // Extra padding for gesture nav
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Settings Button (Small)
            androidx.compose.material3.IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(56.dp).background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.CircleShape
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(id = com.adsamcik.tracker.tracker.R.string.description_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Start Button (Expanded)
            val startDescription = stringResource(id = com.adsamcik.tracker.tracker.R.string.description_tracking_start)
            PrimaryActionButton(
                text = "START TRACKING",
                onClick = onStartClick,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("tracking_fab")
                    .semantics { contentDescription = startDescription }
            )
        }
    }
}
