package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameGoalUnit
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Pre-start setup: pick a goal, an optional difficulty, and whether to remember
 * the choice. Scrollable and free of fixed heights so it survives 200% font
 * scaling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MiniGameSetupPanel(
	state: MiniGameSessionUiState.Setup,
	descriptionRes: Int,
	onSelectGoal: (Int) -> Unit,
	onSelectDifficulty: (MiniGameDifficulty) -> Unit,
	onSetRemember: (Boolean) -> Unit,
	onStart: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val options = state.options
	val selection = state.selection

	Column(
		modifier = modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(vertical = RidgelineSpacing.Lg),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Lg),
	) {
		Text(
			text = stringResource(R.string.minigame_setup_title),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			text = stringResource(descriptionRes),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Text(
			text = stringResource(R.string.minigame_setup_goal_title),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
		)
		FlowRow(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		) {
			options.goalValues.forEach { value ->
				val selected = value == selection.goalValue
				val label = goalLabel(options.goalUnit, value)
				FilterChip(
					selected = selected,
					onClick = { onSelectGoal(value) },
					label = { Text(label) },
					modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
				)
			}
		}

		if (options.hasDifficulty) {
			Text(
				text = stringResource(R.string.minigame_setup_difficulty_title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			FlowRow(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
				verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
			) {
				options.difficulties.forEach { difficulty ->
					val selected = difficulty == selection.difficulty
					val label = difficultyLabel(difficulty)
					FilterChip(
						selected = selected,
						onClick = { onSelectDifficulty(difficulty) },
						label = { Text(label) },
						modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
					)
				}
			}
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = MIN_TOUCH_TARGET),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.minigame_setup_remember),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = stringResource(R.string.minigame_setup_remember_description),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Switch(
				checked = state.rememberSetup,
				onCheckedChange = onSetRemember,
			)
		}

		Button(
			onClick = onStart,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = MIN_TOUCH_TARGET)
				.sizeIn(minHeight = MIN_TOUCH_TARGET),
		) {
			Icon(Icons.Outlined.PlayArrow, contentDescription = null)
			Spacer(Modifier.padding(horizontal = RidgelineSpacing.Xs))
			Text(stringResource(R.string.minigame_setup_start_cta))
		}
	}
}

@Composable
private fun goalLabel(unit: MiniGameGoalUnit, value: Int): String = when (unit) {
	MiniGameGoalUnit.METERS -> stringResource(R.string.minigame_setup_goal_meters, value)
	MiniGameGoalUnit.CELLS -> pluralStringResource(R.plurals.minigame_setup_goal_cells, value, value)
	MiniGameGoalUnit.MINUTES -> stringResource(R.string.minigame_setup_goal_minutes, value)
	MiniGameGoalUnit.CHARGES ->
		pluralStringResource(R.plurals.minigame_setup_goal_charges, value, value)
	MiniGameGoalUnit.TURNS ->
		pluralStringResource(R.plurals.minigame_setup_goal_turns, value, value)
}

@Composable
private fun difficultyLabel(difficulty: MiniGameDifficulty): String = stringResource(
	when (difficulty) {
		MiniGameDifficulty.EASY -> R.string.minigame_difficulty_easy
		MiniGameDifficulty.NORMAL -> R.string.minigame_difficulty_normal
		MiniGameDifficulty.HARD -> R.string.minigame_difficulty_hard
	},
)

private val MIN_TOUCH_TARGET = 48.dp
