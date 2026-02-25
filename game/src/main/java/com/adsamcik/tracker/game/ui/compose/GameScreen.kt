package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.AppColors
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StreakUi
import com.adsamcik.tracker.game.repository.TrophySummaryUi

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
    miniGameEntries: List<MiniGameEntry>,
    explorationState: ExplorationState,
    achievementState: AchievementSummaryState,
    playerProfile: PlayerProfileUi?,
    streak: StreakUi?,
    trophySummary: TrophySummaryUi,
    modifier: Modifier = Modifier,
    isLoadingChallenges: Boolean = false,
    onViewAllAchievements: () -> Unit = {},
    onNavigateToTrophyCase: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(top = 16.dp, bottom = AppDimensions.FloatingNavBarClearance),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroLevelCard(
                    level = playerProfile?.level ?: 0,
                    xpIntoCurrentLevel = playerProfile?.xpIntoCurrentLevel ?: 0L,
                    xpForNextLevel = playerProfile?.xpForNextLevel ?: 0L,
                    streakCount = streak?.currentCount ?: 0,
                    streakBest = streak?.bestCount ?: 0,
                    freezeCount = streak?.freezeCount ?: 0,
                )
            }
            item {
                PointsCard(pointsToday)
            }
            item {
                steps?.let { StepsCard(it) }
            }
            item {
                ExplorationCard(state = explorationState)
            }
            item {
                AchievementCard(
                    state = achievementState,
                    onViewAll = onViewAllAchievements,
                )
            }
            item { SectionHeader(text = stringResource(R.string.game_active_challenges)) }
            item {
                ActiveChallengesRow(challenges = challenges)
            }
            item { SectionHeader(text = stringResource(R.string.minigame_section_title)) }
            item {
                MiniGamesGrid(
                    games = miniGameEntries.map { entry ->
                        MiniGameUi(
                            id = entry.id,
                            name = stringResource(entry.nameRes),
                            description = stringResource(entry.descriptionRes),
                            unlockLevel = entry.unlockLevel,
                            isUnlocked = entry.isUnlocked,
                        )
                    },
                )
            }
            item {
                TrophySummaryCard(
                    totalCompleted = trophySummary.totalCompleted,
                    goldCount = trophySummary.goldCount,
                    silverCount = trophySummary.silverCount,
                    bronzeCount = trophySummary.bronzeCount,
                    onViewTrophyCase = onNavigateToTrophyCase,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun PointsCard(points: Int) {
    val detailsLabel = stringResource(R.string.game_points_details)
    val context = LocalContext.current
    val comingSoonText = stringResource(R.string.game_points_breakdown_coming_soon)
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        text = points.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (points == 0) {
                            stringResource(R.string.game_points_start_tracking_hint)
                        } else {
                            stringResource(R.string.points_earned_today)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        Toast.makeText(context, comingSoonText, Toast.LENGTH_SHORT).show()
                    }
                    .padding(8.dp)
                    .semantics { contentDescription = detailsLabel }
            ) {
                 // Simplified action indicator, perhaps an arrow
            }
        }
    }
}

@Composable
private fun StepsCard(steps: StepsSummaryUi) {
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Outlined.DirectionsWalk,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = AppColors.ActivityWalk // Keeping specific activity color
                )
                Text(
                    text = stringResource(R.string.game_steps_goals_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Row(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Stat(stringResource(R.string.game_steps_today), steps.stepsToday, steps.goalDay)
                Stat(stringResource(R.string.game_steps_week), steps.stepsWeek, steps.goalWeek)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, goal: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "$value / $goal", 
            style = MaterialTheme.typography.titleMedium, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Simple progress bar
        val progress = if (goal > 0) (value.toFloat() / goal).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(80.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = progress,
                        range = 0f..1f
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun ChallengesLoadingState() {
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.game_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChallengesEmptyState() {
    EmptyStateCard(
        icon = Icons.Outlined.EmojiEvents,
        title = stringResource(R.string.game_challenges_empty),
        subtitle = stringResource(R.string.game_challenges_empty_subtitle),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun ChallengeCard(ch: ChallengeUi) {
    val challengeDesc = "${ch.title}: ${(ch.progress * 100).toInt()}% complete"
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .semantics { contentDescription = challengeDesc }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
                Text(
                    text = ch.title, 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                AnimatedVisibility(visible = ch.description.isNotBlank()) {
                    Text(
                        text = ch.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Progress bar
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = ch.progress,
                                range = 0f..1f
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ch.progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Text(
                text = "${(ch.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
