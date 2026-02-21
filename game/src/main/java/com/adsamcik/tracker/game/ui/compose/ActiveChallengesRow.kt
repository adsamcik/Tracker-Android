package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

@Composable
fun ActiveChallengesRow(
	challenges: List<ChallengeUi>,
	modifier: Modifier = Modifier,
	maxSlots: Int = 3,
) {
	LazyRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
	) {
		items(challenges.take(maxSlots), key = { it.id }) { challenge ->
			ChallengeCard(challenge)
		}
		val emptySlots = (maxSlots - challenges.size).coerceAtLeast(0)
		items(emptySlots) {
			EmptySlotCard()
		}
	}
}

@Composable
private fun ChallengeCard(challenge: ChallengeUi) {
	val percent = (challenge.progress * 100).toInt()
	val description = "Challenge: ${challenge.title}, $percent percent"

	GlassCard(
		modifier = Modifier
			.width(160.dp)
			.heightIn(min = 120.dp)
			.semantics { contentDescription = description },
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
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
				textAlign = TextAlign.Center,
			)
			Spacer(modifier = Modifier.height(8.dp))
			ProgressArc(progress = challenge.progress)
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
private fun EmptySlotCard() {
	val infiniteTransition = rememberInfiniteTransition(label = "emptySlotPulse")
	val alpha by infiniteTransition.animateFloat(
		initialValue = 0.3f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1000),
			repeatMode = RepeatMode.Reverse,
		),
		label = "emptySlotAlpha",
	)

	GlassCard(
		modifier = Modifier
			.width(160.dp)
			.heightIn(min = 120.dp),
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
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
