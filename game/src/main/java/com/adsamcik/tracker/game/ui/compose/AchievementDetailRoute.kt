package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(padding),
			contentPadding = PaddingValues(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item(key = "show_locked_toggle") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							stringResource(R.string.achievement_show_locked),
							style = MaterialTheme.typography.bodyLarge,
							color = MaterialTheme.colorScheme.onSurface,
						)
						if (!showLocked && totalUnlocked == 0) {
							Text(
								stringResource(R.string.achievement_show_locked_summary_off),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
					Switch(checked = showLocked, onCheckedChange = { showLocked = it })
				}
			}

			if (visibleRows.isEmpty()) {
				item(key = "empty_state") { AchievementEmptyState() }
			} else {
				AchievementCategory.entries.forEach { category ->
					val categoryRows = visibleRows.filter { it.definition.category == category }
					if (categoryRows.isNotEmpty()) {
						item(key = "header_${category.name}") {
							Text(
								categoryLabel(category),
								style = MaterialTheme.typography.titleMedium,
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.onSurface,
								modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
private fun AchievementEmptyState() {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 48.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Box(
			modifier = Modifier
				.size(64.dp)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				Icons.Outlined.EmojiEvents,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(36.dp),
			)
		}
		Text(
			stringResource(R.string.achievement_empty_state_title),
			style = MaterialTheme.typography.titleMedium,
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

@Composable
private fun AchievementDetailCard(row: AchievementDetailRow) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		colors = CardDefaults.cardColors(
			containerColor = if (row.isUnlocked) {
				MaterialTheme.colorScheme.surfaceContainerHigh
			} else {
				MaterialTheme.colorScheme.surfaceContainer
			},
		),
		shape = RoundedCornerShape(16.dp),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				TierMedal(tier = row.definition.tier, isUnlocked = row.isUnlocked)
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						AchievementFormatting.rememberTitle(row.definition),
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						stringResource(
							R.string.achievement_tier_label,
							tierLabel(row.definition.tier),
							row.definition.tier.pointBonus,
						),
						style = MaterialTheme.typography.labelSmall,
						color = tierColor(row.definition.tier),
						fontWeight = FontWeight.Medium,
					)
				}
				if (row.isUnlocked) {
					Icon(
						Icons.Outlined.EmojiEvents,
						contentDescription = null,
						tint = tierColor(row.definition.tier),
						modifier = Modifier.size(24.dp),
					)
				}
			}
			Text(
				AchievementFormatting.rememberDescription(row.definition),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
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
					color = if (row.isUnlocked) tierColor(row.definition.tier) else MaterialTheme.colorScheme.primary,
					fontWeight = FontWeight.SemiBold,
				)
			}
			ThickProgressBar(progress = row.progress, color = tierColor(row.definition.tier))
		}
	}
}

@Composable
private fun TierMedal(tier: AchievementTier, isUnlocked: Boolean) {
	val baseColor = tierColor(tier)
	Box(
		modifier = Modifier
			.size(40.dp)
			.clip(CircleShape)
			.background(if (isUnlocked) baseColor.copy(alpha = 0.25f) else baseColor.copy(alpha = 0.1f)),
		contentAlignment = Alignment.Center,
	) {
		Text(
			tierLabel(tier).take(1),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Bold,
			color = if (isUnlocked) baseColor else baseColor.copy(alpha = 0.6f),
		)
	}
}

@Composable
private fun ThickProgressBar(progress: Float, color: Color) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(8.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(progress)
				.height(8.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(color),
		)
	}
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

private fun categoryLabel(category: AchievementCategory): String =
	category.name.lowercase().replaceFirstChar { it.uppercase() }

private const val PERCENT_MULTIPLIER = 100

