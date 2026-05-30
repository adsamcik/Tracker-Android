package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.stats.api.AchievementTier

@Composable
internal fun AchievementHeroCard(
	unlocked: Int,
	total: Int,
	tierCounts: Map<AchievementTier, Int>,
	totalPoints: Int,
) {
	val progress = if (total > 0) unlocked.toFloat() / total else 0f
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
		),
		shape = RoundedCornerShape(24.dp),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(20.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ProgressRing(
				progress = progress,
				size = 88.dp,
				strokeWidth = 8.dp,
				trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
				progressColor = MaterialTheme.colorScheme.primary,
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(0.dp),
				) {
					Text(
						"$unlocked",
						style = MaterialTheme.typography.headlineMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
					Text(
						"of $total",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
					)
				}
			}
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					stringResource(R.string.achievement_hero_title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Icon(
						Icons.Outlined.Stars,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onPrimaryContainer,
						modifier = Modifier.size(16.dp),
					)
					Text(
						stringResource(R.string.achievement_hero_points, totalPoints),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
					)
				}
				TierBreakdownRow(tierCounts)
			}
		}
	}
}

@Composable
private fun ProgressRing(
	progress: Float,
	size: Dp,
	strokeWidth: Dp,
	trackColor: Color,
	progressColor: Color,
	content: @Composable () -> Unit,
) {
	Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
		Canvas(modifier = Modifier.size(size)) {
			val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
			val arcSize = Size(
				size.toPx() - strokeWidth.toPx(),
				size.toPx() - strokeWidth.toPx(),
			)
			val arcTopLeft = Offset(strokeWidth.toPx() / 2f, strokeWidth.toPx() / 2f)
			drawArc(
				color = trackColor,
				startAngle = 0f,
				sweepAngle = 360f,
				useCenter = false,
				topLeft = arcTopLeft,
				size = arcSize,
				style = stroke,
			)
			drawArc(
				color = progressColor,
				startAngle = -90f,
				sweepAngle = 360f * progress.coerceIn(0f, 1f),
				useCenter = false,
				topLeft = arcTopLeft,
				size = arcSize,
				style = stroke,
			)
		}
		content()
	}
}

@Composable
private fun TierBreakdownRow(tierCounts: Map<AchievementTier, Int>) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		AchievementTier.entries.forEach { tier ->
			val count = tierCounts[tier] ?: 0
			TierPill(tier = tier, count = count)
		}
	}
}

@Composable
private fun TierPill(tier: AchievementTier, count: Int) {
	val color = tierColor(tier)
	Surface(
		shape = RoundedCornerShape(8.dp),
		color = color.copy(alpha = if (count > 0) 0.25f else 0.08f),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.clip(CircleShape)
					.background(if (count > 0) color else color.copy(alpha = 0.4f)),
			)
			Text(
				count.toString(),
				style = MaterialTheme.typography.labelSmall,
				fontWeight = FontWeight.SemiBold,
				color = if (count > 0) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
				},
			)
		}
	}
}
