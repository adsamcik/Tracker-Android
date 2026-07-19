package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameCompletionOutcome
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameGoalUnit
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.session.GameSessionResult
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Completion screen. Distinguishes first run / new PB / tied / behind, shows the
 * goal outcome and awarded points as separate lines (points are never conflated
 * with the score), and offers Play again / Change setup / Done.
 */
@Composable
internal fun MiniGameCompletionPanel(
	result: GameSessionResult,
	scoreUnit: MiniGameScoreUnit,
	onPlayAgain: (MiniGameConfiguration) -> Unit,
	onChangeSetup: () -> Unit,
	onDone: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val outcome = result.completionOutcome
	val reducedMotion = LocalReducedMotion.current

	Column(
		modifier = modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(vertical = RidgelineSpacing.Lg),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
	) {
		CompletionBadge(isPersonalBest = outcome is MiniGameCompletionOutcome.PersonalBest, reducedMotion)

		Text(
			text = stringResource(completionTitleRes(result)),
			style = MaterialTheme.typography.headlineSmall,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)

		Text(
			text = stringResource(
				R.string.minigame_completion_result,
				formatScoreValue(scoreUnit, result.finalScore),
			),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)

		completionDetail(outcome, scoreUnit)?.let { detail ->
			Text(
				text = detail,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}

		val goalReached = result.finalScore >= result.configuration.goal.scoreTarget
		val goalDescription = goalDescription(result.configuration)
		Text(
			text = stringResource(
				if (goalReached) {
					R.string.minigame_completion_goal_reached
				} else {
					R.string.minigame_completion_goal_incomplete
				},
				goalDescription,
			),
			style = MaterialTheme.typography.bodyLarge,
			color = if (goalReached) {
				MaterialTheme.colorScheme.tertiary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
		)

		Text(
			text = if (result.pointsAwarded > 0) {
				stringResource(R.string.minigame_completion_points, result.pointsAwarded)
			} else {
				stringResource(R.string.minigame_completion_points_zero)
			},
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.primary,
			textAlign = TextAlign.Center,
		)

		Spacer(Modifier.size(RidgelineSpacing.Sm))

		Button(
			onClick = { onPlayAgain(result.configuration) },
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = MIN_TOUCH_TARGET),
		) {
			Text(stringResource(R.string.minigame_session_play_again))
		}
		FilledTonalButton(
			onClick = onChangeSetup,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = MIN_TOUCH_TARGET),
		) {
			Text(stringResource(R.string.minigame_completion_change_setup))
		}
		OutlinedButton(
			onClick = onDone,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = MIN_TOUCH_TARGET),
		) {
			Text(stringResource(R.string.minigame_completion_done))
		}
	}
}

@Composable
private fun CompletionBadge(isPersonalBest: Boolean, reducedMotion: Boolean) {
	val scale = if (isPersonalBest && !reducedMotion) {
		val transition = rememberInfiniteTransition(label = "completion-pulse")
		val animated by transition.animateFloat(
			initialValue = 0.94f,
			targetValue = 1.06f,
			animationSpec = infiniteRepeatable(
				animation = tween(durationMillis = PULSE_DURATION_MS),
				repeatMode = RepeatMode.Reverse,
			),
			label = "completion-scale",
		)
		animated
	} else {
		1f
	}
	Icon(
		imageVector = Icons.Outlined.EmojiEvents,
		contentDescription = null,
		tint = MaterialTheme.colorScheme.tertiary,
		modifier = Modifier
			.size(72.dp)
			.scale(scale)
			.clearAndSetSemantics { },
	)
}

private fun completionTitleRes(result: GameSessionResult): Int = when {
	result.pointsAwarded <= 0 -> R.string.minigame_completion_no_participation_title
	result.completionOutcome is MiniGameCompletionOutcome.PersonalBest -> R.string.minigame_completion_new_best_title
	result.completionOutcome == MiniGameCompletionOutcome.FirstRun -> R.string.minigame_completion_first_run_title
	result.completionOutcome == MiniGameCompletionOutcome.TiedBest -> R.string.minigame_completion_tied_title
	else -> R.string.minigame_completion_behind_title
}

@Composable
private fun completionDetail(
	outcome: MiniGameCompletionOutcome,
	scoreUnit: MiniGameScoreUnit,
): String? = when (outcome) {
	is MiniGameCompletionOutcome.PersonalBest -> stringResource(
		R.string.minigame_completion_improvement,
		formatScoreValue(scoreUnit, outcome.improvement),
	)
	is MiniGameCompletionOutcome.BelowBest -> stringResource(
		R.string.minigame_completion_deficit,
		formatScoreValue(scoreUnit, outcome.distanceFromBest),
	)
	MiniGameCompletionOutcome.FirstRun,
	MiniGameCompletionOutcome.TiedBest,
	-> null
}

@Composable
private fun formatScoreValue(unit: MiniGameScoreUnit, value: Double): String {
	val safe = if (value.isFinite() && value >= 0.0) value else 0.0
	return when (unit) {
		MiniGameScoreUnit.DISTANCE_METERS ->
			stringResource(R.string.minigame_outrun_gap_meters, safe.roundToInt())
		MiniGameScoreUnit.CELL_COUNT -> {
			val cells = safe.roundToInt()
			pluralStringResource(R.plurals.minigame_setup_goal_cells, cells, cells)
		}
		MiniGameScoreUnit.DEFUSAL_COUNT -> {
			val charges = safe.roundToInt()
			pluralStringResource(R.plurals.minigame_unit_charges, charges, charges)
		}
		MiniGameScoreUnit.TURN_COUNT -> {
			val turns = safe.roundToInt()
			pluralStringResource(R.plurals.minigame_unit_turns, turns, turns)
		}
		MiniGameScoreUnit.DURATION_SECONDS ->
			formatDurationSeconds(safe.roundToLong())
	}
}

@Composable
private fun goalDescription(configuration: MiniGameConfiguration): String {
	val goal = configuration.goal
	return when (goal.unit) {
		MiniGameGoalUnit.METERS ->
			stringResource(R.string.minigame_setup_goal_meters, goal.displayValue)
		MiniGameGoalUnit.CELLS ->
			pluralStringResource(
				R.plurals.minigame_setup_goal_cells,
				goal.displayValue,
				goal.displayValue,
			)
		MiniGameGoalUnit.MINUTES ->
			stringResource(R.string.minigame_setup_goal_minutes, goal.displayValue)
		MiniGameGoalUnit.CHARGES ->
			pluralStringResource(
				R.plurals.minigame_setup_goal_charges,
				goal.displayValue,
				goal.displayValue,
			)
		MiniGameGoalUnit.TURNS ->
			pluralStringResource(
				R.plurals.minigame_setup_goal_turns,
				goal.displayValue,
				goal.displayValue,
			)
	}
}

private val MIN_TOUCH_TARGET = 48.dp
private const val PULSE_DURATION_MS = 1200
