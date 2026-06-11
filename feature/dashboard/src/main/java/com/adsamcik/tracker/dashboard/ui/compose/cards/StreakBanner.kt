package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Compact full-width banner showing current tracking streak.
 *
 * Layout (two rows):
 * - Top: Flame icon + streak count + "day streak" + trend pill
 * - Bottom: 7-day "week-at-a-glance" strip with day-of-week initials inside
 *   intensity-coloured cells, today highlighted with a primary ring.
 */
@Composable
internal fun StreakBanner(
	streakState: StreakState,
	onClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	if (streakState.currentStreak <= 0) {
		// No active streak — keep the banner in a single encouragement state.
		EncouragementBanner(
			modifier = modifier,
			onClick = onClick,
		)
		return
	}

	val streakContentDescription = pluralStringResource(
		R.plurals.dashboard_cd_streak_banner,
		streakState.currentStreak,
		streakState.currentStreak,
	)
	val streakClickLabel = stringResource(R.string.dashboard_action_view_streak_details)

	Card(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = streakContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = streakClickLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = RidgelineCardDefaults.containerColor,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
		) {
			// Top row: streak count + trend pill
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(
						imageVector = Icons.Filled.LocalFireDepartment,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(24.dp),
					)
					Spacer(Modifier.width(8.dp))
					Text(
						text = "${streakState.currentStreak}",
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Spacer(Modifier.width(4.dp))
					Text(
						text = stringResource(R.string.dashboard_streak_day_streak),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				TrendPill(
					trend = streakState.weeklyTrend,
					percent = computeTrendPercent(streakState.weeklyDistances),
				)
			}

			if (streakState.weeklyDistances.isNotEmpty()) {
				Spacer(Modifier.height(10.dp))
				WeekAtAGlance(
					distances = streakState.weeklyDistances,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}

@Composable
private fun EncouragementBanner(
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null,
) {
	val streakContentDescription = stringResource(R.string.dashboard_cd_streak_banner_empty)
	val streakClickLabel = stringResource(R.string.dashboard_action_view_streak_details)

	Card(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = streakContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = streakClickLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = RidgelineCardDefaults.containerColor,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = Icons.Filled.LocalFireDepartment,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				text = stringResource(R.string.dashboard_streak_start),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Compact trend pill rendered as a chip in the top row of the banner.
 * Replaces the bare-text trend label so the up/down/steady status reads
 * as an interactive-looking badge rather than a stray percentage.
 */
@Composable
private fun TrendPill(
	trend: WeeklyTrend,
	percent: Int,
) {
	val (containerColor, contentColor, text) = when (trend) {
		WeeklyTrend.UP -> Triple(
			MaterialTheme.colorScheme.tertiaryContainer,
			MaterialTheme.colorScheme.onTertiaryContainer,
			stringResource(R.string.dashboard_streak_trend_up, percent),
		)
		WeeklyTrend.DOWN -> Triple(
			MaterialTheme.colorScheme.errorContainer,
			MaterialTheme.colorScheme.onErrorContainer,
			stringResource(R.string.dashboard_streak_trend_down, percent),
		)
		WeeklyTrend.STEADY -> Triple(
			Color.Transparent,
			MaterialTheme.colorScheme.onSurfaceVariant,
			"",
		)
	}

	if (text.isEmpty()) return

	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(50))
			.background(containerColor)
			.padding(horizontal = 10.dp, vertical = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.SemiBold,
			color = contentColor,
		)
	}
}

/**
 * Full-width row of 7 day cells, one per day of the past week (oldest →
 * today). Each cell shows the weekday initial inside a rounded square
 * tinted by the day's activity intensity. Today is highlighted with a
 * 2dp primary-coloured ring and slightly larger footprint so the user's
 * eye lands on it first.
 */
@Composable
private fun WeekAtAGlance(
	distances: List<Float>,
	modifier: Modifier = Modifier,
) {
	val capped = remember(distances) {
		distances.takeLast(7).let { last ->
			if (last.size < 7) List(7 - last.size) { 0f } + last else last
		}
	}
	val maxValue = remember(capped) { capped.maxOrNull()?.takeIf { it > 0f } ?: 1f }
	val today = remember { LocalDate.now() }
	// `capped` is ordered oldest → today, so the last entry is today.
	val dayOfWeekForIndex: (Int) -> DayOfWeek = remember(today) {
		{ index -> today.minusDays((6 - index).toLong()).dayOfWeek }
	}

	val primary = MaterialTheme.colorScheme.primary
	val onPrimary = MaterialTheme.colorScheme.onPrimary
	val emptyContainer = MaterialTheme.colorScheme.surfaceContainerHighest
	val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
	val outline = MaterialTheme.colorScheme.outlineVariant

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		capped.forEachIndexed { index, value ->
			val dayOfWeek = dayOfWeekForIndex(index)
			val isToday = index == capped.lastIndex
			val intensity = (value / maxValue).coerceIn(0f, 1f)
			val isActive = value > 0f

			DayCell(
				dayOfWeek = dayOfWeek,
				intensity = intensity,
				isActive = isActive,
				isToday = isToday,
				primary = primary,
				onPrimary = onPrimary,
				emptyContainer = emptyContainer,
				onSurfaceVariant = onSurfaceVariant,
				outline = outline,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun DayCell(
	dayOfWeek: DayOfWeek,
	intensity: Float,
	isActive: Boolean,
	isToday: Boolean,
	primary: Color,
	onPrimary: Color,
	emptyContainer: Color,
	onSurfaceVariant: Color,
	outline: Color,
	modifier: Modifier = Modifier,
) {
	// Single-letter initial — Locale-aware so e.g. Czech "P / Ú / S" works too.
	val initial = remember(dayOfWeek) {
		dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
			.firstOrNull()?.uppercase() ?: ""
	}

	val ringWidth by animateDpAsState(
		targetValue = if (isToday) 2.dp else 0.dp,
		label = "todayRingWidth",
	)
	val shape = RoundedCornerShape(percent = 32)

	// Background blends from the empty-container base to a primary-tinted
	// fill as intensity rises, so even a low-intensity day still reads as
	// "something happened" without being indistinguishable from the empty
	// state — the chief complaint with the old "- - - - -" sparkline.
	val containerColor = if (isActive) {
		lerp(emptyContainer, primary, 0.25f + intensity * 0.7f)
	} else {
		emptyContainer
	}

	// Letter contrast scales with the same intensity so high-activity days
	// flip to onPrimary when the background is dark enough.
	val letterColor = when {
		isActive && intensity >= 0.55f -> onPrimary
		isActive -> lerp(onSurfaceVariant, onPrimary, intensity)
		else -> onSurfaceVariant.copy(alpha = 0.7f)
	}

	Box(
		modifier = modifier
			.aspectRatio(0.85f) // slightly taller than wide reads as a "pill" not a square
			.clip(shape)
			.background(containerColor)
			.then(
				if (isToday) {
					Modifier.border(ringWidth, primary, shape)
				} else {
					Modifier.border(1.dp, outline.copy(alpha = if (isActive) 0f else 0.4f), shape)
				},
			),
		contentAlignment = Alignment.Center,
	) {
		// Soft inner gradient on highly-active days for a touch of depth.
		if (isActive && intensity > 0.4f) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.verticalGradient(
							0f to Color.White.copy(alpha = 0.12f),
							1f to Color.Transparent,
						),
					),
			)
		}

		Text(
			text = initial,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
			color = letterColor,
		)
	}
}

private fun computeTrendPercent(values: List<Float>): Int {
	if (values.size < 2) return 0
	val recent = values.takeLast(3).average().toFloat()
	val older = values.take((values.size - 3).coerceAtLeast(1)).average().toFloat()
	if (older <= 0f) return 0
	return ((recent - older) / older * 100f).toInt().coerceIn(-999, 999).let {
		kotlin.math.abs(it)
	}
}
