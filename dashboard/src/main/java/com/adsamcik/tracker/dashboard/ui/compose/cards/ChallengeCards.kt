package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.ChallengeUiModel
import java.util.concurrent.TimeUnit

/**
 * Horizontal scrolling row of challenge cards.
 *
 * Each card shows difficulty badge, title, progress arc, and time remaining.
 * Empty state shows a "Start a challenge" card.
 */
@Composable
internal fun ChallengeCardsRow(
	challenges: List<ChallengeUiModel>,
	onChallengeClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier.fillMaxWidth()) {
		Text(
			text = stringResource(R.string.dashboard_challenges_title),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(horizontal = 4.dp),
		)
		Spacer(Modifier.height(8.dp))

		if (challenges.isEmpty()) {
			EmptyChallengeCard(onClick = onChallengeClick)
		} else {
			LazyRow(
				contentPadding = PaddingValues(horizontal = 0.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				items(challenges, key = { it.id }) { challenge ->
					ChallengeCard(
						challenge = challenge,
						onClick = onChallengeClick,
					)
				}
			}
		}
	}
}

@Composable
private fun ChallengeCard(
	challenge: ChallengeUiModel,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val difficultyColor = when (challenge.difficulty.lowercase()) {
		"easy" -> MaterialTheme.colorScheme.tertiary
		"hard" -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.secondary
	}
	val difficultyLabel = when (challenge.difficulty.lowercase()) {
		"easy" -> stringResource(R.string.dashboard_challenge_easy)
		"hard" -> stringResource(R.string.dashboard_challenge_hard)
		else -> stringResource(R.string.dashboard_challenge_medium)
	}
	val progressPercent = (challenge.progress.coerceIn(0f, 1f) * 100).toInt()
	val cardContentDescription = stringResource(
		R.string.dashboard_cd_challenge_card,
		challenge.title,
		progressPercent,
	)
	val viewDetailsLabel = stringResource(R.string.dashboard_action_view_details)

	Card(
		modifier = modifier
			.width(160.dp)
			.height(120.dp)
			.semantics(mergeDescendants = true) {
				contentDescription = cardContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = viewDetailsLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
		shape = MaterialTheme.shapes.large,
	) {
		Column(
			modifier = Modifier
				.padding(12.dp),
		) {
			// Difficulty badge
			Text(
				text = difficultyLabel,
				style = MaterialTheme.typography.labelSmall,
				fontWeight = FontWeight.Bold,
				color = difficultyColor,
			)
			Spacer(Modifier.height(4.dp))

			// Title
			Text(
				text = challenge.title,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)

			Spacer(Modifier.weight(1f))

			// Bottom row: progress arc + time remaining
			Box(
				modifier = Modifier.fillMaxWidth(),
				contentAlignment = Alignment.CenterStart,
			) {
				val progressColor = MaterialTheme.colorScheme.primary
				val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

				Canvas(modifier = Modifier.size(32.dp)) {
					val strokeWidth = 3.dp.toPx()
					val arcSize = size.width - strokeWidth
					val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

					// Track arc (180° at bottom)
					drawArc(
						color = trackColor,
						startAngle = 0f,
						sweepAngle = 180f,
						useCenter = false,
						topLeft = topLeft,
						size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
						style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
					)

					// Progress arc
					drawArc(
						color = progressColor,
						startAngle = 0f,
						sweepAngle = 180f * challenge.progress.coerceIn(0f, 1f),
						useCenter = false,
						topLeft = topLeft,
						size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
						style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
					)
				}

				Text(
					text = formatTimeRemaining(challenge.timeRemainingMs),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(start = 40.dp),
				)
			}
		}
	}
}

@Composable
private fun EmptyChallengeCard(
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val challengesClickLabel = stringResource(R.string.dashboard_action_view_challenges)
	val emptyCardContentDescription = stringResource(R.string.dashboard_cd_challenge_empty)

	Card(
		modifier = modifier
			.width(160.dp)
			.height(120.dp)
			.semantics(mergeDescendants = true) {
				contentDescription = emptyCardContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = challengesClickLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
		shape = MaterialTheme.shapes.large,
	) {
		Column(
			modifier = Modifier
				.padding(16.dp),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Icon(
				imageVector = Icons.Filled.EmojiEvents,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(32.dp),
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(R.string.dashboard_challenges_empty),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
	
		}
	}
}

private fun formatTimeRemaining(ms: Long): String {
	val days = TimeUnit.MILLISECONDS.toDays(ms)
	val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
	return when {
		days > 0 -> "${days}d"
		hours > 0 -> "${hours}h"
		else -> "<1h"
	}
}
