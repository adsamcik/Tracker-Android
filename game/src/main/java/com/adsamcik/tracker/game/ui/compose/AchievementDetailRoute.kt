package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.R
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
		val progressByMetric = progressRows
			.mapNotNull { row -> MetricKey.fromStorageKey(row.metricKey)?.let { it to row } }
			.toMap()
		AchievementCatalog.definitions.map { definition ->
			val progress = progressByMetric[definition.metric]
			AchievementDetailRow(
				definition = definition,
				currentValue = progress?.lastValue ?: 0.0,
				isUnlocked = (progress?.lastTierIndex ?: -1) >= definition.tierIndex,
			)
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), emptyList())
}

/**
 * UI row backing an [AchievementDefinition] in the achievement list, paired
 * with the user's current [progress][currentValue] toward it.
 */
data class AchievementDetailRow(
	val definition: AchievementDefinition,
	val currentValue: Double,
	val isUnlocked: Boolean,
) {
	val progress: Float
		get() = if (definition.threshold <= 0.0) {
			0f
		} else {
			(currentValue / definition.threshold).toFloat().coerceIn(0f, 1f)
		}
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
				navigationIcon = {
					TextButton(onClick = onBack) { Text(stringResource(R.string.game_close)) }
				},
			)
		},
	) { padding ->
		val visibleRows = rows.filter { showLocked || it.isUnlocked }
		val tierCounts = remember(rows) { countByTier(rows) }
		val totalPoints = remember(tierCounts) {
			tierCounts.entries.sumOf { (tier, count) -> tier.pointBonus * count }
		}

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
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
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

private fun countByTier(rows: List<AchievementDetailRow>): Map<AchievementTier, Int> {
	val result = AchievementTier.entries.associateWith { 0 }.toMutableMap()
	for (row in rows) {
		if (row.isUnlocked) {
			result[row.definition.tier] = (result[row.definition.tier] ?: 0) + 1
		}
	}
	return result
}

private const val STATE_STOP_TIMEOUT_MS = 5_000L
