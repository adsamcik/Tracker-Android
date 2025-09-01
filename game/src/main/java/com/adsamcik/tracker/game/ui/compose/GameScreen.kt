package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.shared.utils.style.compose.TrackerTheme

data class StepsSummaryUi(
    val stepsToday: Int,
    val stepsWeek: Int,
    val goalDay: Int,
    val goalWeek: Int
)

data class ChallengeUi(
    val id: Long,
    val title: String,
    val description: String,
    val progress: Float
)

@Composable
fun GameScreen(
    pointsToday: Int,
    steps: StepsSummaryUi?,
    challenges: List<ChallengeUi>,
    modifier: Modifier = Modifier
) {
    TrackerTheme {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PointsCard(pointsToday)
            }
            item {
                steps?.let { StepsCard(it) }
            }
            item { SectionHeader(text = stringResource(R.string.challenge_list_title)) }
            items(challenges, key = { it.id }) { ch ->
                ChallengeCard(ch)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun PointsCard(points: Int) {
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Star, contentDescription = null)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(text = points.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = stringResource(R.string.points_earned_today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AssistChip(onClick = {}, label = { Text("Details") })
        }
    }
}

@Composable
private fun StepsCard(steps: StepsSummaryUi) {
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DirectionsWalk, contentDescription = null)
                Text(
                    text = "Steps goals",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Stat("Today", steps.stepsToday, steps.goalDay)
                Stat("Week", steps.stepsWeek, steps.goalWeek)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, goal: Int) {
    Column() {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = "$value / $goal", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ChallengeCard(ch: ChallengeUi) {
    ElevatedCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 80.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.EmojiEvents, contentDescription = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(text = ch.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(visible = ch.description.isNotBlank()) {
                    Text(
                        text = ch.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${(ch.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
