package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Displays the player's level, XP progress bar, and streak information.
 */
@Composable
fun HeroLevelCard(
	level: Int,
	xpIntoCurrentLevel: Long,
	xpForNextLevel: Long,
	streakCount: Int,
	streakBest: Int,
	freezeCount: Int,
	modifier: Modifier = Modifier,
) {
	GlassCard(
		modifier = modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth()
	) {
		if (level == 0 || xpForNextLevel == 0L) {
			EmptyLevelContent()
		} else {
			LevelContent(
				level = level,
				xpIntoCurrentLevel = xpIntoCurrentLevel,
				xpForNextLevel = xpForNextLevel,
				streakCount = streakCount,
				streakBest = streakBest,
				freezeCount = freezeCount,
			)
		}
	}
}

@Composable
private fun EmptyLevelContent() {
	Text(
		text = stringResource(R.string.game_hero_start_tracking_hint),
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.padding(vertical = 8.dp),
		textAlign = TextAlign.Center,
	)
}

@Composable
private fun LevelContent(
	level: Int,
	xpIntoCurrentLevel: Long,
	xpForNextLevel: Long,
	streakCount: Int,
	streakBest: Int,
	freezeCount: Int,
) {
	Column {
		// Header: level circle + label
		Row(verticalAlignment = Alignment.CenterVertically) {
			LevelCircle(level)
			Text(
				text = stringResource(R.string.game_level, level),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(start = 16.dp),
			)
		}

		Spacer(modifier = Modifier.height(16.dp))

		// XP progress bar
		XpProgressBar(
			xpIntoCurrentLevel = xpIntoCurrentLevel,
			xpForNextLevel = xpForNextLevel,
		)

		Spacer(modifier = Modifier.height(16.dp))

		// Streak row
		StreakRow(
			streakCount = streakCount,
			streakBest = streakBest,
			freezeCount = freezeCount,
		)
	}
}

@Composable
private fun LevelCircle(level: Int) {
	Box(
		modifier = Modifier
			.size(56.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.primary),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = level.toString(),
			style = MaterialTheme.typography.displaySmall,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onPrimary,
			textAlign = TextAlign.Center,
		)
	}
}

@Composable
private fun XpProgressBar(
	xpIntoCurrentLevel: Long,
	xpForNextLevel: Long,
) {
	val xpFraction = if (xpForNextLevel > 0L) {
		(xpIntoCurrentLevel.toFloat() / xpForNextLevel).coerceIn(0f, 1f)
	} else {
		0f
	}

	val animatedFraction by animateFloatAsState(
		targetValue = xpFraction,
		animationSpec = tween(durationMillis = XP_ANIMATION_DURATION_MS),
		label = "xp_progress",
	)

	// Progress bar track
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(8.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
			.semantics {
				progressBarRangeInfo = ProgressBarRangeInfo(
					current = animatedFraction,
					range = 0f..1f,
				)
			},
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(animatedFraction)
				.height(8.dp)
				.background(MaterialTheme.colorScheme.primary),
		)
	}

	// XP label
	Text(
		text = stringResource(
			R.string.game_xp_progress,
			xpIntoCurrentLevel.toString(),
			xpForNextLevel.toString(),
		),
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(top = 4.dp),
	)
}

@Composable
private fun StreakRow(
	streakCount: Int,
	streakBest: Int,
	freezeCount: Int,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = streakEmoji(streakCount),
			style = MaterialTheme.typography.titleMedium,
		)
		Text(
			text = "${stringResource(R.string.game_streak_label)}: $streakCount",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(start = 8.dp),
		)
		Spacer(modifier = Modifier.weight(1f))
		Text(
			text = stringResource(R.string.game_streak_best, streakBest),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (freezeCount > 0) {
			Text(
				text = "❄\uFE0F $freezeCount",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 12.dp),
			)
		}
	}
}

private fun streakEmoji(streak: Int): String = when {
	streak >= STREAK_TIER_INFERNO -> "\uD83D\uDD25\uD83D\uDD25\uD83D\uDD25"
	streak >= STREAK_TIER_BLAZE -> "\uD83D\uDD25\uD83D\uDD25"
	streak >= STREAK_TIER_FLAME -> "\uD83D\uDD25"
	else -> "\uD83D\uDD6F\uFE0F"
}

private const val XP_ANIMATION_DURATION_MS = 600
private const val STREAK_TIER_FLAME = 5
private const val STREAK_TIER_BLAZE = 10
private const val STREAK_TIER_INFERNO = 20
