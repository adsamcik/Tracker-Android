package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBestComparison
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.tweenEmphasized
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Outrun's live chase visualization. The ghost remains the spatial anchor while
 * the runner advances or falls behind, making the current race relationship
 * readable without exposing any location data.
 */
@Composable
internal fun OutrunGapRail(
	snapshot: MiniGameSnapshot,
	modifier: Modifier = Modifier,
) {
	val payload = snapshot.visualPayload as MiniGameVisualPayload.Outrun
	val reducedMotion = LocalReducedMotion.current
	val goal = snapshot.goalProgress as? MiniGameGoalProgress.Tracked
	val goalReached = goal?.isReached == true
	val caught = snapshot.phase == MiniGamePhase.COMPLETED &&
		payload.hasRaceStarted &&
		payload.currentGapMeters < 0.0
	val running = payload.hasRaceStarted &&
		snapshot.phase in setOf(MiniGamePhase.ACTIVE, MiniGamePhase.WARNING)
	val danger = snapshot.phase == MiniGamePhase.WARNING

	val currentGap = payload.currentGapMeters.roundToInt()
	val bestThisRun = payload.bestThisRunMeters.roundToInt()
	val personalBest = payload.personalBestMeters?.roundToInt()
	val scaleMax = maxOf(
		goal?.target ?: 0.0,
		payload.bestThisRunMeters,
		payload.warningThresholdMeters * 2.0,
		OUTRUN_MIN_SCALE_M,
	) * OUTRUN_SCALE_PADDING
	val targetGapFraction = (payload.currentGapMeters / scaleMax).toFloat()
	val renderedGapFraction = if (reducedMotion) {
		targetGapFraction
	} else {
		val animatedGapFraction by animateFloatAsState(
			targetValue = targetGapFraction,
			animationSpec = RidgelineMotion.Respond,
			label = "outrun-runner-position",
		)
		animatedGapFraction
	}

	val motion = rememberOutrunAmbientMotion(
		enabled = running,
		danger = danger,
		reducedMotion = reducedMotion,
	)
	val oneShot = rememberOutrunOneShotState(
		snapshot = snapshot,
		hasRaceStarted = payload.hasRaceStarted,
		reducedMotion = reducedMotion,
	)

	val stateDescription = outrunStateDescription(
		snapshot = snapshot,
		payload = payload,
		goal = goal,
		caught = caught,
		goalReached = goalReached,
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = stateDescription
			},
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		OutrunStatus(
			snapshot = snapshot,
			payload = payload,
			caught = caught,
			goalReached = goalReached,
		)

		ChaseArena(
			payload = payload,
			goal = goal,
			scaleMax = scaleMax,
			gapFraction = renderedGapFraction,
			speedPhase = motion.speedPhase,
			dangerPulse = motion.dangerPulse,
			startProgress = oneShot.startProgress,
			feedbackBeat = oneShot.feedbackBeat,
			feedbackProgress = oneShot.feedbackProgress,
			running = running,
			danger = danger,
			caught = caught,
			goalReached = goalReached,
		)

		RunnerLegend()

		Text(
			text = if (payload.currentGapMeters >= 0.0) {
				stringResource(R.string.minigame_outrun_lead_value, currentGap)
			} else {
				stringResource(
					R.string.minigame_outrun_ghost_lead_value,
					abs(payload.currentGapMeters).roundToInt().coerceAtLeast(1),
				)
			},
			style = MaterialTheme.typography.headlineSmall,
			color = when {
				caught -> MaterialTheme.colorScheme.error
				danger -> MaterialTheme.colorScheme.tertiary
				else -> MaterialTheme.colorScheme.onSurface
			},
			fontWeight = FontWeight.Bold,
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		) {
			MetricCard(
				label = stringResource(R.string.minigame_outrun_best_run),
				value = stringResource(R.string.minigame_outrun_gap_meters, bestThisRun),
				accent = MaterialTheme.colorScheme.secondary,
				modifier = Modifier.weight(1f),
			)
			MetricCard(
				label = stringResource(R.string.minigame_outrun_personal_best),
				value = personalBest?.let {
					stringResource(R.string.minigame_outrun_gap_meters, it)
				} ?: stringResource(R.string.minigame_outrun_first_run),
				accent = MaterialTheme.colorScheme.tertiary,
				modifier = Modifier.weight(1f),
			)
		}

		goal?.let {
			OutrunGoalRibbon(
				progress = it,
				goalReached = goalReached,
				reducedMotion = reducedMotion,
			)
		}
	}
}

@Composable
private fun OutrunStatus(
	snapshot: MiniGameSnapshot,
	payload: MiniGameVisualPayload.Outrun,
	caught: Boolean,
	goalReached: Boolean,
) {
	val isNewBest = snapshot.personalBest.comparison == MiniGamePersonalBestComparison.AHEAD
	val (text, containerColor, contentColor) = when {
		goalReached -> Triple(
			stringResource(R.string.minigame_outrun_escaped),
			MaterialTheme.colorScheme.primaryContainer,
			MaterialTheme.colorScheme.onPrimaryContainer,
		)
		caught -> Triple(
			stringResource(R.string.minigame_outrun_caught),
			MaterialTheme.colorScheme.errorContainer,
			MaterialTheme.colorScheme.onErrorContainer,
		)
		snapshot.phase == MiniGamePhase.COMPLETED -> Triple(
			stringResource(R.string.minigame_feedback_completed),
			MaterialTheme.colorScheme.surfaceVariant,
			MaterialTheme.colorScheme.onSurfaceVariant,
		)
		!payload.hasRaceStarted -> Triple(
			stringResource(R.string.minigame_outrun_move_to_start),
			MaterialTheme.colorScheme.primaryContainer,
			MaterialTheme.colorScheme.onPrimaryContainer,
		)
		snapshot.phase == MiniGamePhase.WARNING -> Triple(
			stringResource(R.string.minigame_outrun_warning),
			MaterialTheme.colorScheme.tertiaryContainer,
			MaterialTheme.colorScheme.onTertiaryContainer,
		)
		isNewBest -> Triple(
			stringResource(R.string.minigame_outrun_ahead_of_best),
			MaterialTheme.colorScheme.secondaryContainer,
			MaterialTheme.colorScheme.onSecondaryContainer,
		)
		else -> Triple(
			stringResource(R.string.minigame_outrun_keep_moving),
			MaterialTheme.colorScheme.surfaceVariant,
			MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	Surface(
		color = containerColor,
		contentColor = contentColor,
		shape = MaterialTheme.shapes.large,
	) {
		Text(
			text = text,
			modifier = Modifier.padding(
				horizontal = RidgelineSpacing.Md,
				vertical = RidgelineSpacing.Sm,
			),
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.Bold,
		)
	}
}

@Composable
private fun ChaseArena(
	payload: MiniGameVisualPayload.Outrun,
	goal: MiniGameGoalProgress.Tracked?,
	scaleMax: Double,
	gapFraction: Float,
	speedPhase: Float,
	dangerPulse: Float,
	startProgress: Float,
	feedbackBeat: OutrunFeedbackBeat,
	feedbackProgress: Float,
	running: Boolean,
	danger: Boolean,
	caught: Boolean,
	goalReached: Boolean,
) {
	val trackColor = MaterialTheme.colorScheme.outlineVariant
	val safeColor = MaterialTheme.colorScheme.primary
	val warningColor = MaterialTheme.colorScheme.tertiary
	val caughtColor = MaterialTheme.colorScheme.error
	val playerColor = if (caught) caughtColor else safeColor
	val playerGlow = if (caught) {
		MaterialTheme.colorScheme.errorContainer
	} else {
		MaterialTheme.colorScheme.primaryContainer
	}
	val ghostColor = if (caught) caughtColor else warningColor
	val ghostEyeColor = if (caught) {
		MaterialTheme.colorScheme.onError
	} else {
		MaterialTheme.colorScheme.onTertiary
	}
	val bestColor = MaterialTheme.colorScheme.secondary
	val personalBestColor = MaterialTheme.colorScheme.tertiary
	val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
	val goalColor = MaterialTheme.colorScheme.primary

	Surface(
		color = surfaceColor,
		shape = MaterialTheme.shapes.extraLarge,
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(OUTRUN_ARENA_HEIGHT),
		) {
			val horizontalPadding = size.width * 0.08f
			val ghostX = size.width * GHOST_ANCHOR_FRACTION
			val usableWidth = size.width - ghostX - horizontalPadding
			val runnerX = (ghostX + gapFraction * usableWidth).coerceIn(
				horizontalPadding,
				size.width - horizontalPadding,
			)
			val trackY = size.height * TRACK_Y_FRACTION
			val warningX = ghostX +
				(payload.warningThresholdMeters / scaleMax).toFloat() * usableWidth

			if (running || goalReached) {
				drawSpeedStreaks(
					runnerX = runnerX,
					trackY = trackY,
					phase = speedPhase,
					color = playerColor,
					intensity = if (danger) 1f else 0.65f,
				)
			}

			drawRoundRect(
				color = warningColor.copy(alpha = 0.12f + dangerPulse * 0.12f),
				topLeft = Offset(ghostX, trackY - 14.dp.toPx()),
				size = Size(
					(warningX - ghostX).coerceAtLeast(4.dp.toPx()),
					28.dp.toPx(),
				),
				cornerRadius = CornerRadius(14.dp.toPx()),
			)
			drawLine(
				color = trackColor,
				start = Offset(horizontalPadding, trackY),
				end = Offset(size.width - horizontalPadding, trackY),
				strokeWidth = 6.dp.toPx(),
				cap = StrokeCap.Round,
			)
			drawLine(
				color = if (caught) caughtColor else safeColor,
				start = Offset(ghostX, trackY),
				end = Offset(runnerX, trackY),
				strokeWidth = 7.dp.toPx(),
				cap = StrokeCap.Round,
			)

			drawGapMarker(
				x = ghostX +
					(payload.bestThisRunMeters / scaleMax).toFloat() * usableWidth,
				trackY = trackY,
				color = bestColor,
				isFlag = false,
			)
			payload.personalBestMeters?.let {
				drawGapMarker(
					x = ghostX + (it / scaleMax).toFloat() * usableWidth,
					trackY = trackY,
					color = personalBestColor,
					isFlag = true,
				)
			}
			goal?.let {
				drawGoalLine(
					x = ghostX + (it.target / scaleMax).toFloat() * usableWidth,
					trackY = trackY,
					color = goalColor,
				)
			}

			val ghostCenter = Offset(ghostX, trackY - 3.dp.toPx())
			val runnerCenter = Offset(runnerX, trackY - 7.dp.toPx())
			if (danger) {
				drawCircle(
					color = warningColor.copy(alpha = 0.12f + dangerPulse * 0.12f),
					radius = (25.dp.toPx() + dangerPulse * 8.dp.toPx()),
					center = ghostCenter,
				)
			}
			drawGhost(
				center = ghostCenter,
				radius = 18.dp.toPx(),
				color = ghostColor,
				eyeColor = ghostEyeColor,
			)
			drawRunner(
				center = runnerCenter,
				radius = 18.dp.toPx(),
				color = playerColor,
				glowColor = playerGlow,
				lean = if (payload.hasRaceStarted) 0.16f else 0f,
			)

			drawStartBeat(
				center = runnerCenter,
				progress = startProgress,
				color = safeColor,
			)
			drawFeedbackBeat(
				beat = feedbackBeat,
				progress = feedbackProgress,
				ghostCenter = ghostCenter,
				runnerCenter = runnerCenter,
				color = when (feedbackBeat) {
					OutrunFeedbackBeat.DANGER -> warningColor
					OutrunFeedbackBeat.PERSONAL_BEST -> bestColor
					OutrunFeedbackBeat.GOAL -> goalColor
					OutrunFeedbackBeat.COMPLETED -> if (caught) caughtColor else safeColor
					OutrunFeedbackBeat.NONE -> Color.Transparent
				},
			)

			if (goalReached) {
				drawGoalCrest(
					center = runnerCenter,
					color = goalColor,
					secondaryColor = bestColor,
				)
			}
		}
	}
}

@Composable
private fun RunnerLegend() {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		LegendKey(
			color = MaterialTheme.colorScheme.primary,
			label = stringResource(R.string.minigame_outrun_you),
		)
		LegendKey(
			color = MaterialTheme.colorScheme.tertiary,
			label = stringResource(R.string.minigame_outrun_ghost),
		)
	}
}

@Composable
private fun LegendKey(
	color: Color,
	label: String,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(Modifier.size(10.dp)) {
			drawCircle(color = color)
		}
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			fontWeight = FontWeight.SemiBold,
		)
	}
}

@Composable
private fun MetricCard(
	label: String,
	value: String,
	accent: Color,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier,
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		shape = MaterialTheme.shapes.large,
	) {
		Column(
			modifier = Modifier.padding(RidgelineSpacing.Sm),
			verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = value,
				style = MaterialTheme.typography.titleMedium,
				color = accent,
				fontWeight = FontWeight.Bold,
			)
		}
	}
}

@Composable
private fun OutrunGoalRibbon(
	progress: MiniGameGoalProgress.Tracked,
	goalReached: Boolean,
	reducedMotion: Boolean,
) {
	val targetProgress = progress.fraction.toFloat()
	val renderedProgress = if (reducedMotion) {
		targetProgress
	} else {
		val animatedProgress by animateFloatAsState(
			targetValue = targetProgress,
			animationSpec = RidgelineMotion.Respond,
			label = "outrun-goal-progress",
		)
		animatedProgress
	}
	val color = if (goalReached) {
		MaterialTheme.colorScheme.primary
	} else {
		MaterialTheme.colorScheme.secondary
	}
	Column(
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = if (goalReached) {
					stringResource(R.string.minigame_session_goal_reached)
				} else {
					stringResource(R.string.minigame_outrun_escape_goal)
				},
				style = MaterialTheme.typography.labelLarge,
				color = color,
				fontWeight = FontWeight.Bold,
			)
			Text(
				text = stringResource(
					R.string.minigame_outrun_goal_value,
					progress.current.roundToInt(),
					progress.target.roundToInt(),
				),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		LinearProgressIndicator(
			progress = { renderedProgress },
			modifier = Modifier
				.fillMaxWidth()
				.height(10.dp),
			color = color,
			trackColor = MaterialTheme.colorScheme.surfaceVariant,
		)
	}
}

@Composable
private fun rememberOutrunAmbientMotion(
	enabled: Boolean,
	danger: Boolean,
	reducedMotion: Boolean,
): OutrunAmbientMotion {
	if (!enabled || reducedMotion) {
		return OutrunAmbientMotion(
			speedPhase = STATIC_SPEED_PHASE,
			dangerPulse = if (danger) STATIC_DANGER_PULSE else 0f,
		)
	}
	val transition = rememberInfiniteTransition(label = "outrun-ambient-motion")
	val speedPhase by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(
				durationMillis = if (danger) DANGER_SPEED_CYCLE_MS else SPEED_CYCLE_MS,
				easing = LinearEasing,
			),
			repeatMode = RepeatMode.Restart,
		),
		label = "outrun-speed-streaks",
	)
	val dangerPulse by transition.animateFloat(
		initialValue = 0f,
		targetValue = if (danger) 1f else 0f,
		animationSpec = infiniteRepeatable(
			animation = tween(
				durationMillis = DANGER_PULSE_MS,
				easing = FastOutSlowInEasing,
			),
			repeatMode = RepeatMode.Reverse,
		),
		label = "outrun-danger-pulse",
	)
	return OutrunAmbientMotion(speedPhase, dangerPulse)
}

@Composable
private fun rememberOutrunOneShotState(
	snapshot: MiniGameSnapshot,
	hasRaceStarted: Boolean,
	reducedMotion: Boolean,
): OutrunOneShotState {
	val startAnimation = remember { Animatable(1f) }
	val feedbackAnimation = remember { Animatable(1f) }
	var feedbackBeat by remember { mutableStateOf(OutrunFeedbackBeat.NONE) }
	var lastFeedbackId by remember { mutableLongStateOf(0L) }
	var previousRaceStarted by remember { mutableStateOf(hasRaceStarted) }

	LaunchedEffect(hasRaceStarted, reducedMotion) {
		if (reducedMotion) {
			previousRaceStarted = hasRaceStarted
			startAnimation.snapTo(1f)
			return@LaunchedEffect
		}
		val justStarted = hasRaceStarted && !previousRaceStarted
		previousRaceStarted = hasRaceStarted
		if (!justStarted) return@LaunchedEffect
		startAnimation.snapTo(0f)
		startAnimation.animateTo(1f, animationSpec = tweenEmphasized())
	}

	LaunchedEffect(snapshot.latestFeedback?.eventId?.value, reducedMotion) {
		val feedback = snapshot.latestFeedback
		if (reducedMotion) {
			if (feedback != null && feedback.eventId.value != lastFeedbackId) {
				lastFeedbackId = feedback.eventId.value
				feedbackBeat = feedback.cue.toOutrunFeedbackBeat()
			}
			feedbackAnimation.snapTo(1f)
			return@LaunchedEffect
		}
		feedback ?: return@LaunchedEffect
		if (feedback.eventId.value == lastFeedbackId) return@LaunchedEffect
		lastFeedbackId = feedback.eventId.value
		feedbackBeat = feedback.cue.toOutrunFeedbackBeat()
		if (feedbackBeat == OutrunFeedbackBeat.NONE) {
			feedbackAnimation.snapTo(1f)
		} else {
			feedbackAnimation.snapTo(0f)
			feedbackAnimation.animateTo(1f, animationSpec = tweenEmphasized())
		}
	}

	return OutrunOneShotState(
		startProgress = startAnimation.value,
		feedbackBeat = feedbackBeat,
		feedbackProgress = feedbackAnimation.value,
	)
}

@Composable
private fun outrunStateDescription(
	snapshot: MiniGameSnapshot,
	payload: MiniGameVisualPayload.Outrun,
	goal: MiniGameGoalProgress.Tracked?,
	caught: Boolean,
	goalReached: Boolean,
): String {
	val gap = payload.currentGapMeters.roundToInt()
	val best = payload.bestThisRunMeters.roundToInt()
	val raceDescription = when {
		!payload.hasRaceStarted ->
			stringResource(R.string.minigame_outrun_description_waiting)
		goalReached ->
			stringResource(R.string.minigame_outrun_description_escaped, gap.coerceAtLeast(0), best)
		caught ->
			stringResource(
				R.string.minigame_outrun_description_caught,
				abs(payload.currentGapMeters).roundToInt().coerceAtLeast(1),
				best,
			)
		snapshot.phase == MiniGamePhase.COMPLETED ->
			stringResource(R.string.minigame_outrun_description_finished, gap, best)
		snapshot.phase == MiniGamePhase.WARNING ->
			stringResource(
				R.string.minigame_outrun_description_warning,
				gap.coerceAtLeast(0),
				best,
			)
		else ->
			stringResource(R.string.minigame_outrun_rail_description, gap, best)
	}
	val goalDescription = goal?.let {
		stringResource(
			R.string.minigame_outrun_goal_description,
			(it.fraction * 100.0).roundToInt(),
		)
	}
	return listOfNotNull(raceDescription, goalDescription).joinToString(" ")
}

private fun MiniGameFeedbackCue.toOutrunFeedbackBeat(): OutrunFeedbackBeat = when (this) {
	MiniGameFeedbackCue.OutrunDanger -> OutrunFeedbackBeat.DANGER
	MiniGameFeedbackCue.PersonalBestCrossed -> OutrunFeedbackBeat.PERSONAL_BEST
	MiniGameFeedbackCue.GoalReached -> OutrunFeedbackBeat.GOAL
	is MiniGameFeedbackCue.SessionCompleted -> OutrunFeedbackBeat.COMPLETED
	MiniGameFeedbackCue.TerritoryCellClaimed,
	MiniGameFeedbackCue.FuseCritical,
	MiniGameFeedbackCue.FuseDefused,
	MiniGameFeedbackCue.SwitchbackTurnCarved,
	MiniGameFeedbackCue.ZenZoneEntered,
	MiniGameFeedbackCue.ZenZoneExited,
	-> OutrunFeedbackBeat.NONE
}

private fun DrawScope.drawSpeedStreaks(
	runnerX: Float,
	trackY: Float,
	phase: Float,
	color: Color,
	intensity: Float,
) {
	val laneHeight = 44.dp.toPx()
	val streakLength = 22.dp.toPx()
	val travel = 42.dp.toPx()
	repeat(SPEED_STREAK_COUNT) { index ->
		val laneOffset = (index - 1) * laneHeight / 3f
		val phaseOffset = (phase + index * 0.27f) % 1f
		val endX = runnerX - 24.dp.toPx() - phaseOffset * travel
		drawLine(
			color = color.copy(alpha = (1f - phaseOffset) * 0.28f * intensity),
			start = Offset(endX - streakLength, trackY - 20.dp.toPx() + laneOffset),
			end = Offset(endX, trackY - 20.dp.toPx() + laneOffset),
			strokeWidth = 2.dp.toPx() + index.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun DrawScope.drawGapMarker(
	x: Float,
	trackY: Float,
	color: Color,
	isFlag: Boolean,
) {
	val clampedX = x.coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
	drawLine(
		color = color,
		start = Offset(clampedX, trackY - 17.dp.toPx()),
		end = Offset(clampedX, trackY + 17.dp.toPx()),
		strokeWidth = 2.dp.toPx(),
		cap = StrokeCap.Round,
	)
	if (isFlag) {
		val flag = Path().apply {
			moveTo(clampedX, trackY - 17.dp.toPx())
			lineTo(clampedX + 12.dp.toPx(), trackY - 12.dp.toPx())
			lineTo(clampedX, trackY - 7.dp.toPx())
			close()
		}
		drawPath(flag, color)
	} else {
		drawCircle(color, radius = 4.dp.toPx(), center = Offset(clampedX, trackY - 17.dp.toPx()))
	}
}

private fun DrawScope.drawGoalLine(
	x: Float,
	trackY: Float,
	color: Color,
) {
	val clampedX = x.coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
	repeat(4) { index ->
		drawLine(
			color = color.copy(alpha = if (index % 2 == 0) 1f else 0.35f),
			start = Offset(clampedX, trackY - 28.dp.toPx() + index * 14.dp.toPx()),
			end = Offset(clampedX, trackY - 21.dp.toPx() + index * 14.dp.toPx()),
			strokeWidth = 3.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun DrawScope.drawGhost(
	center: Offset,
	radius: Float,
	color: Color,
	eyeColor: Color,
) {
	val path = Path().apply {
		moveTo(center.x - radius, center.y + radius * 0.72f)
		lineTo(center.x - radius, center.y)
		cubicTo(
			center.x - radius,
			center.y - radius,
			center.x - radius * 0.45f,
			center.y - radius * 1.2f,
			center.x,
			center.y - radius * 1.2f,
		)
		cubicTo(
			center.x + radius * 0.45f,
			center.y - radius * 1.2f,
			center.x + radius,
			center.y - radius,
			center.x + radius,
			center.y,
		)
		lineTo(center.x + radius, center.y + radius * 0.72f)
		lineTo(center.x + radius * 0.52f, center.y + radius * 0.38f)
		lineTo(center.x, center.y + radius * 0.72f)
		lineTo(center.x - radius * 0.52f, center.y + radius * 0.38f)
		close()
	}
	drawPath(path, color)
	drawCircle(
		color = eyeColor,
		radius = radius * 0.14f,
		center = Offset(center.x - radius * 0.35f, center.y - radius * 0.28f),
	)
	drawCircle(
		color = eyeColor,
		radius = radius * 0.14f,
		center = Offset(center.x + radius * 0.35f, center.y - radius * 0.28f),
	)
}

private fun DrawScope.drawRunner(
	center: Offset,
	radius: Float,
	color: Color,
	glowColor: Color,
	lean: Float,
) {
	drawCircle(
		color = glowColor,
		radius = radius * 1.22f,
		center = center,
	)
	val head = Offset(center.x + radius * lean, center.y - radius * 0.72f)
	val shoulder = Offset(center.x, center.y - radius * 0.2f)
	val hip = Offset(center.x + radius * lean, center.y + radius * 0.3f)
	drawCircle(color = color, radius = radius * 0.24f, center = head)
	drawLine(color, shoulder, hip, strokeWidth = radius * 0.25f, cap = StrokeCap.Round)
	drawLine(
		color,
		shoulder,
		Offset(center.x + radius * 0.75f, center.y),
		strokeWidth = radius * 0.2f,
		cap = StrokeCap.Round,
	)
	drawLine(
		color,
		shoulder,
		Offset(center.x - radius * 0.5f, center.y + radius * 0.05f),
		strokeWidth = radius * 0.2f,
		cap = StrokeCap.Round,
	)
	drawLine(
		color,
		hip,
		Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
		strokeWidth = radius * 0.22f,
		cap = StrokeCap.Round,
	)
	drawLine(
		color,
		hip,
		Offset(center.x - radius * 0.65f, center.y + radius * 0.72f),
		strokeWidth = radius * 0.22f,
		cap = StrokeCap.Round,
	)
}

private fun DrawScope.drawStartBeat(
	center: Offset,
	progress: Float,
	color: Color,
) {
	if (progress >= 1f) return
	drawCircle(
		color = color.copy(alpha = (1f - progress) * 0.55f),
		radius = 20.dp.toPx() + progress * 30.dp.toPx(),
		center = center,
		style = Stroke(width = 3.dp.toPx()),
	)
}

private fun DrawScope.drawFeedbackBeat(
	beat: OutrunFeedbackBeat,
	progress: Float,
	ghostCenter: Offset,
	runnerCenter: Offset,
	color: Color,
) {
	if (beat == OutrunFeedbackBeat.NONE || progress >= 1f) return
	val alpha = (1f - progress).coerceIn(0f, 1f)
	when (beat) {
		OutrunFeedbackBeat.DANGER -> {
			drawCircle(
				color = color.copy(alpha = alpha * 0.7f),
				radius = 22.dp.toPx() + progress * 28.dp.toPx(),
				center = ghostCenter,
				style = Stroke(width = 4.dp.toPx()),
			)
		}
		OutrunFeedbackBeat.PERSONAL_BEST -> {
			repeat(8) { index ->
				val angle = index * PI.toFloat() / 4f
				val startRadius = 24.dp.toPx() + progress * 10.dp.toPx()
				val endRadius = startRadius + 13.dp.toPx() * alpha
				drawLine(
					color = color.copy(alpha = alpha),
					start = Offset(
						runnerCenter.x + cos(angle) * startRadius,
						runnerCenter.y + sin(angle) * startRadius,
					),
					end = Offset(
						runnerCenter.x + cos(angle) * endRadius,
						runnerCenter.y + sin(angle) * endRadius,
					),
					strokeWidth = 3.dp.toPx(),
					cap = StrokeCap.Round,
				)
			}
		}
		OutrunFeedbackBeat.GOAL -> {
			repeat(10) { index ->
				val angle = index * PI.toFloat() / 5f
				val distance = 24.dp.toPx() + progress * 48.dp.toPx()
				drawCircle(
					color = color.copy(alpha = alpha),
					radius = (2.dp.toPx() + (index % 3).dp.toPx()),
					center = Offset(
						runnerCenter.x + cos(angle) * distance,
						runnerCenter.y + sin(angle) * distance,
					),
				)
			}
		}
		OutrunFeedbackBeat.COMPLETED -> {
			drawCircle(
				color = color.copy(alpha = alpha * 0.65f),
				radius = 18.dp.toPx() + progress * 38.dp.toPx(),
				center = runnerCenter,
				style = Stroke(width = 4.dp.toPx()),
			)
		}
		OutrunFeedbackBeat.NONE -> Unit
	}
}

private fun DrawScope.drawGoalCrest(
	center: Offset,
	color: Color,
	secondaryColor: Color,
) {
	repeat(7) { index ->
		val angle = (-PI / 2.0 + (index - 3) * 0.28).toFloat()
		val radius = 33.dp.toPx() + (index % 2) * 6.dp.toPx()
		drawCircle(
			color = if (index % 2 == 0) color else secondaryColor,
			radius = 2.5.dp.toPx(),
			center = Offset(
				center.x + cos(angle) * radius,
				center.y + sin(angle) * radius,
			),
		)
	}
}

private data class OutrunAmbientMotion(
	val speedPhase: Float,
	val dangerPulse: Float,
)

private data class OutrunOneShotState(
	val startProgress: Float,
	val feedbackBeat: OutrunFeedbackBeat,
	val feedbackProgress: Float,
)

private enum class OutrunFeedbackBeat {
	NONE,
	DANGER,
	PERSONAL_BEST,
	GOAL,
	COMPLETED,
}

private const val OUTRUN_MIN_SCALE_M = 25.0
private const val OUTRUN_SCALE_PADDING = 1.15
private const val GHOST_ANCHOR_FRACTION = 0.22f
private const val TRACK_Y_FRACTION = 0.67f
private const val STATIC_SPEED_PHASE = 0.42f
private const val STATIC_DANGER_PULSE = 0.55f
private const val SPEED_STREAK_COUNT = 4
private const val SPEED_CYCLE_MS = 900
private const val DANGER_SPEED_CYCLE_MS = 560
private const val DANGER_PULSE_MS = 620
private val OUTRUN_ARENA_HEIGHT = 144.dp
