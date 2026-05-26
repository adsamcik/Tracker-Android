package com.adsamcik.tracker.game.ui.compose

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import java.text.NumberFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.leaderboard.LeaderboardState
import com.adsamcik.tracker.game.leaderboard.WeeklyLeaderboardCard
import com.adsamcik.tracker.game.leaderboard.WeeklyLeaderboardErrorCard
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSectionHeader
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import com.adsamcik.tracker.game.repository.TrophySummaryUi
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout
import com.adsamcik.tracker.shared.utils.style.compose.rememberMainNavigationLayout

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
    val progress: Float,
    val difficulty: String = "",
    val timeRemainingMs: Long = 0L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    pointsToday: Int? = null,
    steps: StepsSummaryUi? = null,
    challenges: List<ChallengeUi>? = null,
    miniGameEntries: List<MiniGameEntry>? = null,
    explorationState: ExplorationState? = null,
    achievementState: AchievementSummaryState? = null,
    heroLevelState: HeroLevelUiState? = null,
    trophySummary: TrophySummaryUi? = null,
    leaderboardState: LeaderboardState? = null,
    isLeaderboardError: Boolean = false,
    modifier: Modifier = Modifier,
    isLoadingChallenges: Boolean = false,
    onViewAllAchievements: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNavigateToTrophyCase: () -> Unit = {},
    onChallengeClick: (ChallengeUi) -> Unit = {},
    onStartStreakClick: () -> Unit = {},
    onNavigateToTracker: () -> Unit = {},
    onLeaderboardMetricSelected: (LeaderboardMetric) -> Unit = {},
    onRetryLeaderboard: () -> Unit = {},
) {
	val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsetStart = safeDrawingPadding.calculateStartPadding(layoutDirection)
    val horizontalInsetEnd = safeDrawingPadding.calculateEndPadding(layoutDirection)
    val safeBottomPadding = safeDrawingPadding.calculateBottomPadding()
    val navigationLayout = rememberMainNavigationLayout()
    val bottomClearance = if (navigationLayout == MainNavigationLayout.SideRail) {
        24.dp + safeBottomPadding
    } else {
        AppDimensions.FloatingNavBarClearance + navBarPadding + safeBottomPadding + 40.dp
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            // Nav bar already labels this tab "Game" — drop the duplicate title, keep only the
            // settings action so the screen reads as content-first.
            TopAppBar(
                title = {},
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.game_open_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = horizontalInsetStart, end = horizontalInsetEnd),
            contentPadding = PaddingValues(
                top = RidgelineSpacing.Lg,
                bottom = bottomClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg)
        ) {
            item {
                if (heroLevelState == null) {
                    LoadingGameCard()
                } else {
                    HeroLevelCard(
                        level = heroLevelState.playerProfile?.level ?: 0,
                        xpIntoCurrentLevel = heroLevelState.playerProfile?.xpIntoCurrentLevel ?: 0L,
                        xpForNextLevel = heroLevelState.playerProfile?.xpForNextLevel ?: 0L,
                        streakCount = heroLevelState.streak?.currentCount ?: 0,
                        streakBest = heroLevelState.streak?.bestCount ?: 0,
                        freezeCount = heroLevelState.streak?.freezeCount ?: 0,
                        onStartTrackingClick = onNavigateToTracker,
                    )
                }
            }
            item {
                if (pointsToday == null) {
                    LoadingGameCard()
                } else {
                    PointsCard(
                        points = pointsToday,
                    )
                }
            }
            item {
                val context = LocalContext.current
                val stepCounterSupported = remember {
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
                }
                if (stepCounterSupported) {
                    if (steps == null) {
                        LoadingGameCard()
                    } else {
                        StepsCard(steps = steps, stepCounterSupported = true)
                    }
                }
            }
            item {
                if (isLeaderboardError) {
                    WeeklyLeaderboardErrorCard(onRetry = onRetryLeaderboard)
                } else if (leaderboardState != null) {
                    WeeklyLeaderboardCard(
                        state = leaderboardState,
                        onMetricSelected = onLeaderboardMetricSelected,
                    )
                }
            }
            item {
                if (explorationState == null) {
                    LoadingGameCard()
                } else {
                    ExplorationCard(state = explorationState)
                }
            }
            item {
                if (achievementState == null) {
                    LoadingGameCard()
                } else {
                    AchievementCard(
                        state = achievementState,
                        onViewAll = onViewAllAchievements,
                    )
                }
            }
            item { RidgelineSectionHeader(title = stringResource(R.string.game_active_challenges)) }
            item {
                if (isLoadingChallenges || challenges == null) {
                    ChallengesLoadingState()
                } else {
                    ActiveChallengesRow(
                        challenges = challenges,
                        onChallengeClick = onChallengeClick,
                        onStartStreakClick = onStartStreakClick,
                    )
                }
            }
            item { RidgelineSectionHeader(title = stringResource(R.string.minigame_section_title)) }
            item {
                if (miniGameEntries == null) {
                    LoadingGameCard()
                } else {
                    MiniGamesGrid(
                        games = miniGameEntries.map { entry ->
                            MiniGameUi(
                                id = entry.id,
                                name = stringResource(entry.nameRes),
                                 description = stringResource(entry.descriptionRes),
                                 unlockLevel = entry.unlockLevel,
                                 isUnlocked = entry.isUnlocked,
                                 isAvailable = entry.isAvailable,
                             )
                         },
                     )
                }
            }
            item {
                if (trophySummary == null) {
                    LoadingGameCard()
                } else {
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
}

@Composable
internal fun ChallengeDetailsDialog(
	challenge: ChallengeUi,
	onDismiss: () -> Unit,
	onViewTrophyCase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = challenge.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = challenge.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(challenge.progress * 100).toInt()}% complete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = challenge.difficulty.replace('_', ' ').lowercase()
                        .replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.game_challenge_time_remaining,
                        formatTimeRemaining(challenge.timeRemainingMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onViewTrophyCase) {
                Text(text = stringResource(R.string.game_trophy_view_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.game_close))
            }
        },
	)
}

@Composable
internal fun ChallengePickerDialog(
	challenges: List<ChallengeUi>,
	onDismiss: () -> Unit,
	onChallengeSelected: (ChallengeUi) -> Unit,
	onOpenTrophyCase: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		modifier = modifier,
		title = {
			Text(
				text = stringResource(R.string.game_challenge_picker_title),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
			)
		},
		text = {
			if (challenges.isEmpty()) {
				Text(
					text = stringResource(R.string.game_challenge_picker_empty_message),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = stringResource(R.string.game_challenge_picker_message),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					challenges.forEach { challenge ->
						Text(
							text = challenge.title,
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier.clickable { onChallengeSelected(challenge) },
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = if (challenges.isEmpty()) onOpenTrophyCase else onDismiss,
			) {
				Text(
					text = stringResource(
						if (challenges.isEmpty()) {
							R.string.game_trophy_view_all
						} else {
							R.string.game_close
						},
					),
				)
			}
		},
		dismissButton = {
			if (challenges.isEmpty()) {
				TextButton(onClick = onDismiss) {
					Text(text = stringResource(R.string.game_close))
				}
			}
		},
	)
}

@Composable
private fun PointsCard(
    points: Int,
){
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            text = NumberFormat.getIntegerInstance().format(points),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.points_earned_today),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun StepsCard(steps: StepsSummaryUi, stepCounterSupported: Boolean) {
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.game_steps_goals_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            if (!stepCounterSupported && steps.stepsToday <= 0 && steps.stepsWeek <= 0) {
                Text(
                    text = stringResource(R.string.game_step_sensor_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = stringResource(R.string.game_step_sensor_unavailable_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
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
}

@Composable
private fun LoadingGameCard() {
    GlassCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
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
private fun Stat(label: String, value: Int, goal: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (goal > 0) "$value / $goal" else "$value",
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

private fun formatTimeRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
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

// ─── Previews ────────────────────────────────────────────────────────

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GameScreenPreview() {
	AppTheme {
		GameScreen(
			pointsToday = 142,
			steps = StepsSummaryUi(
				stepsToday = 6500,
				stepsWeek = 35000,
				goalDay = 10000,
				goalWeek = 70000,
			),
			challenges = listOf(
				ChallengeUi(
					id = 1L,
					title = "Walk 5km",
					description = "Complete a 5km walk in one session",
					progress = 0.65f,
					difficulty = "Medium",
					timeRemainingMs = 86400000L,
				),
			),
		)
	}
}
