package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.ui.achievement.AchievementFormatting
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementTier

@Composable
internal fun AchievementDetailCard(row: AchievementDetailRow) {
	val tier = row.definition.tier
	val tColor = tierColor(tier)
	val isUnlocked = row.isUnlocked
	val isNearComplete = !isUnlocked && row.progress >= NEAR_COMPLETE_THRESHOLD

	val containerColor = if (isUnlocked) {
		MaterialTheme.colorScheme.surfaceContainerHigh
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}
	val borderColor = when {
		isUnlocked -> tColor.copy(alpha = 0.5f)
		isNearComplete -> tColor.copy(alpha = 0.35f)
		else -> Color.Transparent
	}
	val contentAlpha = if (isUnlocked || isNearComplete) 1f else 0.78f

	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.border(
				width = if (borderColor == Color.Transparent) 0.dp else 1.5.dp,
				color = borderColor,
				shape = RoundedCornerShape(20.dp),
			),
		colors = CardDefaults.cardColors(containerColor = containerColor),
		shape = RoundedCornerShape(20.dp),
	) {
		// Subtle tier-color gradient overlay only for unlocked cards
		Box {
			if (isUnlocked) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(80.dp)
						.background(
							Brush.verticalGradient(
								colors = listOf(
									tColor.copy(alpha = 0.10f),
									tColor.copy(alpha = 0f),
								),
							),
						),
				)
			}
			Column(
				modifier = Modifier.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					TierMedal(tier = tier, isUnlocked = isUnlocked, category = row.definition.category)
					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						Text(
							AchievementFormatting.rememberTitle(row.definition),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
							fontWeight = FontWeight.SemiBold,
						)
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp),
						) {
							Icon(
								Icons.Outlined.Stars,
								contentDescription = null,
								tint = tColor,
								modifier = Modifier.size(12.dp),
							)
							Text(
								stringResource(
									R.string.achievement_tier_label,
									tierLabel(tier),
									tier.pointBonus,
								),
								style = MaterialTheme.typography.labelSmall,
								color = tColor,
								fontWeight = FontWeight.Medium,
							)
						}
					}
					if (isUnlocked) {
						Icon(
							Icons.Outlined.EmojiEvents,
							contentDescription = stringResource(R.string.achievement_unlocked_content_description),
							tint = tColor,
							modifier = Modifier.size(24.dp),
						)
					} else {
						Icon(
							Icons.Outlined.Lock,
							contentDescription = stringResource(R.string.achievement_locked_content_description),
							tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
							modifier = Modifier.size(20.dp),
						)
					}
				}
				Text(
					AchievementFormatting.rememberDescription(row.definition),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
				)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						AchievementFormatting.rememberValueLabel(row.currentValue, row.definition),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						stringResource(
							R.string.achievement_progress_percent,
							(row.progress * PERCENT_MULTIPLIER).toInt(),
						),
						style = MaterialTheme.typography.labelMedium,
						color = if (isUnlocked) tColor else MaterialTheme.colorScheme.primary,
						fontWeight = FontWeight.SemiBold,
					)
				}
				ThickProgressBar(progress = row.progress, color = tColor, isUnlocked = isUnlocked)
			}
		}
	}
}

@Composable
private fun TierMedal(tier: AchievementTier, isUnlocked: Boolean, category: AchievementCategory) {
	val baseColor = tierColor(tier)
	val displayColor = if (isUnlocked) baseColor else baseColor.copy(alpha = 0.55f)
	val bgAlpha = if (isUnlocked) 0.28f else 0.10f
	Box(
		modifier = Modifier
			.size(48.dp)
			.clip(CircleShape)
			.background(
				Brush.linearGradient(
					colors = listOf(
						baseColor.copy(alpha = bgAlpha + 0.05f),
						baseColor.copy(alpha = bgAlpha),
					),
				),
			)
			.border(
				width = if (isUnlocked) 1.5.dp else 1.dp,
				color = displayColor.copy(alpha = if (isUnlocked) 0.55f else 0.30f),
				shape = CircleShape,
			),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			if (isUnlocked) Icons.Outlined.WorkspacePremium else categoryIcon(category),
			contentDescription = null,
			tint = displayColor,
			modifier = Modifier.size(24.dp),
		)
	}
}

@Composable
private fun ThickProgressBar(progress: Float, color: Color, isUnlocked: Boolean) {
	val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(10.dp)
			.clip(RoundedCornerShape(5.dp))
			.background(trackColor),
	) {
		AnimatedVisibility(
			visible = progress > 0f,
			enter = fadeIn(),
			exit = fadeOut(),
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth(progress)
					.height(10.dp)
					.clip(RoundedCornerShape(5.dp))
					.background(
						if (isUnlocked) {
							Brush.horizontalGradient(
								colors = listOf(color.copy(alpha = 0.85f), color),
							)
						} else {
							Brush.horizontalGradient(
								colors = listOf(color.copy(alpha = 0.65f), color.copy(alpha = 0.85f)),
							)
						},
					),
			)
		}
	}
}
