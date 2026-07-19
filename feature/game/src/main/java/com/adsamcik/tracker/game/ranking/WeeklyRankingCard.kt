package com.adsamcik.tracker.game.ranking

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRank
import com.adsamcik.tracker.feature.game.api.ranking.WeeklyRankingSnapshot
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.tweenStandard
import java.text.NumberFormat

@Composable
internal fun WeeklyRankingCard(
	ranking: WeeklyRankingSnapshot,
	modifier: Modifier = Modifier,
) {
	val pointsFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
	val currentRankName = stringResource(ranking.currentRank.labelRes)

	GlassCard(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(RidgelineSpacing.Lg)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = Icons.Outlined.EmojiEvents,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(28.dp),
				)
				Spacer(modifier = Modifier.width(RidgelineSpacing.Sm))
				Column {
					Text(
						text = stringResource(R.string.weekly_rank_title),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Text(
						text = stringResource(R.string.weekly_rank_refresh_hint),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			Spacer(modifier = Modifier.height(RidgelineSpacing.Lg))
			Text(
				text = currentRankName,
				style = MaterialTheme.typography.headlineMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.primary,
			)
			Text(
				text = stringResource(
					R.string.weekly_rank_points_this_week,
					pointsFormat.format(ranking.accumulatedPoints),
				),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			Spacer(modifier = Modifier.height(RidgelineSpacing.Md))
			val nextTier = ranking.nextTier
			if (nextTier == null) {
				Text(
					text = stringResource(R.string.weekly_rank_highest_reached),
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
				)
			} else {
				val animatedProgress by animateFloatAsState(
					targetValue = ranking.progressToNextRank,
					animationSpec = tweenStandard(),
					label = "weekly_rank_progress",
				)
				val nextRankName = stringResource(nextTier.rank.labelRes)
				val pointsRemaining = (nextTier.minimumPoints - ranking.accumulatedPoints)
					.coerceAtLeast(0.0)
				val progressDescription = stringResource(
					R.string.weekly_rank_progress_description,
					currentRankName,
					nextRankName,
					pointsFormat.format(pointsRemaining),
				)
				LinearProgressIndicator(
					progress = { animatedProgress },
					modifier = Modifier
						.fillMaxWidth()
						.height(8.dp)
						.clip(RoundedCornerShape(4.dp))
						.semantics {
							contentDescription = progressDescription
							progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress, 0f..1f)
						},
					trackColor = MaterialTheme.colorScheme.surfaceVariant,
				)
				Text(
					text = stringResource(
						R.string.weekly_rank_points_to_next,
						pointsFormat.format(pointsRemaining),
						nextRankName,
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = RidgelineSpacing.Xs),
				)
			}

			Spacer(modifier = Modifier.height(RidgelineSpacing.Lg))
			Text(
				text = stringResource(R.string.weekly_rank_ladder_title),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
			)
			Spacer(modifier = Modifier.height(RidgelineSpacing.Xs))
			ranking.tiers.asReversed().forEach { tier ->
				WeeklyRankTierRow(
					rank = tier.rank,
					minimumPoints = tier.minimumPoints,
					isCurrent = tier.rank == ranking.currentRank,
					isReached = ranking.accumulatedPoints >= tier.minimumPoints,
					pointsFormat = pointsFormat,
				)
			}
		}
	}
}

@Composable
internal fun WeeklyRankingErrorCard(modifier: Modifier = Modifier) {
	GlassCard(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(RidgelineSpacing.Lg)) {
			Text(
				text = stringResource(R.string.weekly_rank_error_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
			)
			Text(
				text = stringResource(R.string.weekly_rank_error_body),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = RidgelineSpacing.Xs),
			)
		}
	}
}

@Composable
private fun WeeklyRankTierRow(
	rank: WeeklyRank,
	minimumPoints: Int,
	isCurrent: Boolean,
	isReached: Boolean,
	pointsFormat: NumberFormat,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(
				if (isCurrent) {
					MaterialTheme.colorScheme.primaryContainer
				} else {
					MaterialTheme.colorScheme.surface
				},
			)
			.padding(horizontal = RidgelineSpacing.Sm, vertical = RidgelineSpacing.Xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		Box(
			modifier = Modifier
				.size(28.dp)
				.clip(CircleShape)
				.background(
					if (isReached) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.surfaceVariant
					},
				),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = stringResource(rank.labelRes).take(1),
				style = MaterialTheme.typography.labelSmall,
				fontWeight = FontWeight.Bold,
				color = if (isReached) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		Text(
			text = stringResource(rank.labelRes),
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = stringResource(
				R.string.weekly_rank_tier_points,
				pointsFormat.format(minimumPoints),
			),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private val WeeklyRank.labelRes: Int
	@StringRes get() = when (this) {
		WeeklyRank.BRONZE -> R.string.weekly_rank_bronze
		WeeklyRank.SILVER -> R.string.weekly_rank_silver
		WeeklyRank.GOLD -> R.string.weekly_rank_gold
		WeeklyRank.PLATINUM -> R.string.weekly_rank_platinum
		WeeklyRank.DIAMOND -> R.string.weekly_rank_diamond
	}
