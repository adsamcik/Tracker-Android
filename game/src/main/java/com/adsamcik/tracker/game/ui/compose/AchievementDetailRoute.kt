package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
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
	var showLocked by remember { mutableStateOf(false) }
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.achievements_title)) },
				navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.game_close)) } },
			)
		},
	) { padding ->
		LazyColumn(
			modifier = Modifier.padding(padding),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item {
				Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(stringResource(R.string.achievement_show_locked), style = MaterialTheme.typography.bodyLarge)
					Switch(checked = showLocked, onCheckedChange = { showLocked = it })
				}
			}
			AchievementCategory.entries.forEach { category ->
				val categoryRows = rows.filter { it.definition.category == category && (showLocked || it.isUnlocked) }
				if (categoryRows.isNotEmpty()) {
					item { Text(category.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
					items(categoryRows, key = { it.definition.id }) { row -> AchievementDetailItem(row) }
				}
			}
		}
	}
}

@Composable
private fun AchievementDetailItem(row: AchievementDetailRow) {
	Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(resolveAchievementString(row.definition.nameRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
		Text(resolveAchievementString(row.definition.descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		LinearProgressIndicator(progress = { row.progress }, modifier = Modifier.fillMaxWidth())
	}
}

@Composable
private fun resolveAchievementString(name: String): String {
	val context = LocalContext.current
	val resId = remember(name, context) { context.resources.getIdentifier(name, "string", context.packageName) }
	return if (resId != 0) stringResource(resId) else name
}
