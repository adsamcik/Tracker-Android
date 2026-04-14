package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion

@Composable
fun ActiveChallengesRow(
	challenges: List<ChallengeUi>,
	modifier: Modifier = Modifier,
	maxSlots: Int = 3,
	onChallengeClick: (ChallengeUi) -> Unit = {},
	onStartStreakClick: () -> Unit = {},
) {
	LazyRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
	) {
		items(challenges.take(maxSlots), key = { it.id }) { challenge ->
			ChallengeCard(
				challenge = challenge,
				onClick = onChallengeClick,
			)
		}
		val emptySlots = (maxSlots - challenges.size).coerceAtLeast(0)
		items(emptySlots) {
			EmptySlotCard(onStartStreakClick = onStartStreakClick)
		}
	}
}

@Composable
private fun ChallengeCard(
	challenge: ChallengeUi,
	onClick: (ChallengeUi) -> Unit,
) {
	val percent = (challenge.progress * 100).toInt()
	val timeDesc = if (challenge.timeRemainingMs > 0) {
		val hours = (challenge.timeRemainingMs / (1000 * 60 * 60)).toInt()
		if (hours >= 24) ", ${hours / 24} ${if (hours / 24 == 1) "day" else "days"} remaining"
		else ", $hours ${if (hours == 1) "hour" else "hours"} remaining"
	} else ""
	val description = "Challenge: ${challenge.title}, $percent percent complete$timeDesc"
	val difficultyLabel = challenge.difficulty.replace('_', ' ').lowercase()
		.replaceFirstChar { it.uppercase() }
	val difficultyColor = when (challenge.difficulty.lowercase()) {
		"easy", "very_easy" -> MaterialTheme.colorScheme.tertiary
		"hard", "very_hard" -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.secondary
	}
	val timeRemaining = if (challenge.timeRemainingMs > 0) {
		val hours = (challenge.timeRemainingMs / (1000 * 60 * 60)).toInt()
		if (hours >= 24) "${hours / 24} ${if (hours / 24 == 1) "day" else "days"} remaining"
		else "$hours ${if (hours == 1) "hour" else "hours"} remaining"
	} else {
		null
	}

	GlassCard(
		modifier = Modifier
			.width(200.dp)
			.heightIn(min = 150.dp)
			.clickable(role = Role.Button) { onClick(challenge) }
			.semantics { contentDescription = description },
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			horizontalAlignment = Alignment.Start,
		) {
			Icon(
				imageVector = Icons.Outlined.EmojiEvents,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(24.dp),
			)
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = challenge.title,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				textAlign = TextAlign.Start,
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(modifier = Modifier.height(6.dp))
			Surface(
				color = difficultyColor.copy(alpha = 0.14f),
				shape = MaterialTheme.shapes.extraLarge,
			) {
				Text(
					text = difficultyLabel,
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.SemiBold,
					color = difficultyColor,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}
			Spacer(modifier = Modifier.height(10.dp))
			ProgressArc(progress = challenge.progress)
			timeRemaining?.let {
				Spacer(modifier = Modifier.height(8.dp))
				Text(
					text = it,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}

@Composable
private fun ProgressArc(progress: Float) {
	val animatedProgress by animateFloatAsState(
		targetValue = progress,
		label = "challengeProgress",
	)
	val percent = (animatedProgress * 100).toInt()
	val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
	val fillColor = MaterialTheme.colorScheme.primary
	val textStyle = MaterialTheme.typography.labelMedium
	val textColor = MaterialTheme.colorScheme.onSurface

	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Canvas(modifier = Modifier.size(40.dp)) {
			val strokeWidth = 4.dp.toPx()
			val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
			val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
			drawArc(
				color = trackColor,
				startAngle = -90f,
				sweepAngle = 360f,
				useCenter = false,
				topLeft = topLeft,
				size = arcSize,
				style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
			)
			drawArc(
				color = fillColor,
				startAngle = -90f,
				sweepAngle = animatedProgress * 360f,
				useCenter = false,
				topLeft = topLeft,
				size = arcSize,
				style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
			)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "$percent%",
			style = textStyle,
			color = textColor,
		)
	}
}

@Composable
private fun EmptySlotCard(onStartStreakClick: () -> Unit) {
	val reducedMotion = LocalReducedMotion.current
	val alpha = if (reducedMotion) {
		0.65f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "emptySlotPulse")
		val animated by infiniteTransition.animateFloat(
			initialValue = 0.3f,
			targetValue = 1f,
			animationSpec = infiniteRepeatable(
				animation = tween(durationMillis = 1000),
				repeatMode = RepeatMode.Reverse,
			),
			label = "emptySlotAlpha",
		)
		animated
	}

	GlassCard(
		modifier = Modifier
			.width(200.dp)
			.heightIn(min = 150.dp)
			.clickable(role = Role.Button, onClick = onStartStreakClick),
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Spacer(modifier = Modifier.height(12.dp))
			Text(
				text = stringResource(R.string.game_challenge_slot_empty),
				style = MaterialTheme.typography.displaySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
			)
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = stringResource(R.string.game_challenge_start_hint),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
			)
		}
	}
}
