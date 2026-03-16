package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.insights.InsightCategory
import com.adsamcik.tracker.tracker.insights.SessionInsight

/**
 * Card displaying a list of post-session insights.
 * Each insight is shown as a compact row with an icon, title, and description.
 */
@Composable
fun SessionInsightsCard(
    insights: List<SessionInsight>,
    modifier: Modifier = Modifier,
) {
    if (insights.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.dashboard_insights_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.size(12.dp))

            insights.forEach { insight ->
                InsightRow(insight)
            }
        }
    }
}

@Composable
private fun InsightRow(insight: SessionInsight) {
    val cd = stringResource(R.string.dashboard_cd_insight_item, insight.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(insight.iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SessionInsightsCardPreview() {
    AppTheme {
        SessionInsightsCard(
            insights = listOf(
                SessionInsight(
                    category = InsightCategory.DURATION,
                    title = "Solid session",
                    description = "Great session — over 25 minutes of tracking.",
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
                ),
                SessionInsight(
                    category = InsightCategory.STEPS,
                    title = "Steps recorded",
                    description = "3,421 steps this session — keep it up!",
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_shoe_print,
                ),
                SessionInsight(
                    category = InsightCategory.GOAL,
                    title = "Goal in sight",
                    description = "You're at 78% of your daily step goal.",
                    iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_games_24dp,
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
