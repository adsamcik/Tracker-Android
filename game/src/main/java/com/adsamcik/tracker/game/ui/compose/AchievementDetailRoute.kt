package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.ui.achievement.AchievementFormatting
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AchievementDetailViewModel @Inject constructor(
	progressDao: AchievementProgressDao,
) : ViewModel() {
	val rows = progressDao.getAllFlow().map { progressRows ->
		val progressByMetric = progressRows.mapNotNull { row -> MetricKey.fromStorageKey(row.metricKey)?.let { it to row } }.toMap()
		AchievementCatalog.definitions.map { definition ->
			val progress = progressByMetric[definition.metric]
			AchievementDetailRow(
				definition = definition,
				currentValue = progress?.lastValue ?: 0.0,
				isUnlocked = (progress?.lastTierIndex ?: -1) >= definition.tierIndex,
			)
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class AchievementDetailRow(
	val definition: AchievementDefinition,
	val currentValue: Double,
	val isUnlocked: Boolean,
) {
	val progress: Float get() = if (definition.threshold <= 0.0) 0f else (currentValue / definition.threshold).toFloat().coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementDetailRoute(
	onBack: () -> Unit,
	viewModel: AchievementDetailViewModel = hiltViewModel(),
) {
	val rows by viewModel.rows.collectAsStateWithLifecycle()
	val totalUnlocked = remember(rows) { rows.count { it.isUnlocked } }
	val totalDefinitions = remember(rows) { rows.size }
	// Smart default: if user has 0 unlocked achievements, default the toggle to ON
	// so they immediately see the catalog of what they can earn instead of a blank
	// screen. Once they unlock something, default flips to OFF (earned-only view).
	var showLocked by remember { mutableStateOf(false) }
	var hasAutoSet by remember { mutableStateOf(false) }
	LaunchedEffect(rows.isNotEmpty(), totalUnlocked) {
		if (!hasAutoSet && rows.isNotEmpty()) {
			showLocked = totalUnlocked == 0
			hasAutoSet = true
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.achievements_title)) },
				navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.game_close)) } },
			)
		},
	) { padding ->
		val visibleRows = rows.filter { showLocked || it.isUnlocked }
		val tierCounts = remember(rows) { countByTier(rows) }
		val totalPoints = remember(tierCounts) { tierCounts.entries.sumOf { (tier, count) -> tier.pointBonus * count } }

		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(padding),
			contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item(key = "hero_progress") {
				AchievementHeroCard(
					unlocked = totalUnlocked,
					total = totalDefinitions,
					tierCounts = tierCounts,
					totalPoints = totalPoints,
				)
			}

			item(key = "show_locked_toggle") {
				ShowLockedToggleRow(
					showLocked = showLocked,
					showSummary = !showLocked && totalUnlocked == 0,
					onToggle = { showLocked = it },
				)
			}

			if (visibleRows.isEmpty()) {
				item(key = "empty_state") { AchievementEmptyState() }
			} else {
				AchievementCategory.entries.forEach { category ->
					val categoryRows = visibleRows.filter { it.definition.category == category }
					if (categoryRows.isNotEmpty()) {
						val categoryUnlocked = categoryRows.count { it.isUnlocked }
						val categoryTotal = rows.count { it.definition.category == category }
						item(key = "header_${category.name}") {
							CategorySectionHeader(
								category = category,
								unlocked = categoryUnlocked,
								total = categoryTotal,
							)
						}
						items(categoryRows, key = { it.definition.id }) { row ->
							AchievementDetailCard(row)
						}
					}
				}
			}
		}
	}
}

// ─── Hero progress card ──────────────────────────────────────────────────────

@Composable
private fun AchievementHeroCard(
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
	size: androidx.compose.ui.unit.Dp,
	strokeWidth: androidx.compose.ui.unit.Dp,
	trackColor: Color,
	progressColor: Color,
	content: @Composable () -> Unit,
) {
	Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
		Canvas(modifier = Modifier.size(size)) {
			val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
			val arcSize = androidx.compose.ui.geometry.Size(
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

// ─── Show locked toggle ──────────────────────────────────────────────────────

@Composable
private fun ShowLockedToggleRow(
	showLocked: Boolean,
	showSummary: Boolean,
	onToggle: (Boolean) -> Unit,
) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					stringResource(R.string.achievement_show_locked),
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface,
				)
				if (showSummary) {
					Text(
						stringResource(R.string.achievement_show_locked_summary_off),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			Switch(checked = showLocked, onCheckedChange = onToggle)
		}
	}
}

// ─── Category section header ─────────────────────────────────────────────────

@Composable
private fun CategorySectionHeader(
	category: AchievementCategory,
	unlocked: Int,
	total: Int,
) {
	val accentColor = categoryColor(category)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			modifier = Modifier
				.size(32.dp)
				.clip(CircleShape)
				.background(accentColor.copy(alpha = 0.18f)),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				categoryIcon(category),
				contentDescription = null,
				tint = accentColor,
				modifier = Modifier.size(18.dp),
			)
		}
		Text(
			categoryLabel(category),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		Text(
			"$unlocked / $total",
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

// ─── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun AchievementEmptyState() {
	val infiniteTransition = rememberInfiniteTransition(label = "empty-state-pulse")
	val scale by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = 1.06f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1800),
			repeatMode = RepeatMode.Reverse,
		),
		label = "empty-state-pulse-scale",
	)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp, vertical = 40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Box(
			modifier = Modifier
				.size(120.dp)
				.scale(scale)
				.clip(CircleShape)
				.background(
					Brush.radialGradient(
						colors = listOf(
							MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
							MaterialTheme.colorScheme.primary.copy(alpha = 0f),
						),
					),
				),
			contentAlignment = Alignment.Center,
		) {
			Box(
				modifier = Modifier
					.size(80.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.primaryContainer),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					Icons.Outlined.EmojiEvents,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(44.dp),
				)
			}
		}
		Text(
			stringResource(R.string.achievement_empty_state_title),
			style = MaterialTheme.typography.titleLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			stringResource(R.string.achievement_empty_state_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center,
		)
	}
}

// ─── Achievement card ────────────────────────────────────────────────────────

@Composable
private fun AchievementDetailCard(row: AchievementDetailRow) {
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
			.border(width = if (borderColor == Color.Transparent) 0.dp else 1.5.dp, color = borderColor, shape = RoundedCornerShape(20.dp)),
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
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
						stringResource(R.string.achievement_progress_percent, (row.progress * PERCENT_MULTIPLIER).toInt()),
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

// ─── Color & label helpers ───────────────────────────────────────────────────

private fun countByTier(rows: List<AchievementDetailRow>): Map<AchievementTier, Int> {
	val result = AchievementTier.entries.associateWith { 0 }.toMutableMap()
	for (row in rows) {
		if (row.isUnlocked) {
			result[row.definition.tier] = (result[row.definition.tier] ?: 0) + 1
		}
	}
	return result
}

@Composable
private fun tierColor(tier: AchievementTier): Color = when (tier) {
	AchievementTier.BRONZE -> MaterialTheme.colorScheme.tertiary
	AchievementTier.SILVER -> MaterialTheme.colorScheme.outline
	AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
	AchievementTier.DIAMOND -> MaterialTheme.colorScheme.inversePrimary
	AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
}

@Composable
private fun tierLabel(tier: AchievementTier): String = stringResource(
	when (tier) {
		AchievementTier.BRONZE -> R.string.achievements_bronze
		AchievementTier.SILVER -> R.string.achievements_silver
		AchievementTier.GOLD -> R.string.achievements_gold
		AchievementTier.DIAMOND -> R.string.achievements_diamond
		AchievementTier.MYTHIC -> R.string.achievements_mythic
	},
)

@Composable
private fun categoryColor(category: AchievementCategory): Color = when (category) {
	AchievementCategory.EXPLORATION -> MaterialTheme.colorScheme.primary
	AchievementCategory.DISTANCE -> MaterialTheme.colorScheme.tertiary
	AchievementCategory.STEPS -> MaterialTheme.colorScheme.secondary
	AchievementCategory.STREAKS -> MaterialTheme.colorScheme.error
	AchievementCategory.MILESTONES -> MaterialTheme.colorScheme.primary
	AchievementCategory.MODES -> MaterialTheme.colorScheme.secondary
	AchievementCategory.TIME -> MaterialTheme.colorScheme.tertiary
	AchievementCategory.CALENDAR -> MaterialTheme.colorScheme.inversePrimary
}

private fun categoryIcon(category: AchievementCategory): ImageVector = when (category) {
	AchievementCategory.EXPLORATION -> Icons.Outlined.Explore
	AchievementCategory.DISTANCE -> Icons.Outlined.Route
	AchievementCategory.STEPS -> Icons.AutoMirrored.Outlined.DirectionsWalk
	AchievementCategory.STREAKS -> Icons.Outlined.LocalFireDepartment
	AchievementCategory.MILESTONES -> Icons.Outlined.EmojiEvents
	AchievementCategory.MODES -> Icons.AutoMirrored.Outlined.DirectionsBike
	AchievementCategory.TIME -> Icons.Outlined.Schedule
	AchievementCategory.CALENDAR -> Icons.Outlined.CalendarMonth
}

private fun categoryLabel(category: AchievementCategory): String =
	category.name.lowercase().replaceFirstChar { it.uppercase() }

private const val PERCENT_MULTIPLIER = 100
private const val NEAR_COMPLETE_THRESHOLD = 0.5f
