package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackTurnDirection
import com.adsamcik.tracker.game.minigame.SwitchbackVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineDurations
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import kotlin.math.roundToInt

/**
 * A stylized trail carved only from privacy-safe turn handedness.
 *
 * The visualization has no access to coordinates or absolute headings. Its
 * path is an abstract replay of recent left/right choices.
 */
@Composable
internal fun SwitchbackComposable(
	payload: SwitchbackVisualPayload,
	modifier: Modifier = Modifier,
) {
	val reducedMotion = LocalReducedMotion.current
	val targetProgress = (payload.turnsCompleted.toFloat() / payload.targetTurns)
		.coerceIn(0f, 1f)
	val animatedProgress by animateFloatAsState(
		targetValue = targetProgress,
		animationSpec = RidgelineMotion.Respond,
		label = "switchback-progress",
	)
	val progress = if (reducedMotion) targetProgress else animatedProgress
	val trailPulse = if (reducedMotion || payload.goalReached) {
		0.5f
	} else {
		val transition = rememberInfiniteTransition(label = "switchback-trail")
		val pulse by transition.animateFloat(
			initialValue = 0f,
			targetValue = 1f,
			animationSpec = infiniteRepeatable(
				animation = tween(
					durationMillis = RidgelineDurations.AMBIENT_MS,
					easing = FastOutSlowInEasing,
				),
				repeatMode = RepeatMode.Reverse,
			),
			label = "switchback-next-turn-pulse",
		)
		pulse
	}

	val primary = MaterialTheme.colorScheme.primary
	val secondary = MaterialTheme.colorScheme.secondary
	val tertiary = MaterialTheme.colorScheme.tertiary
	val colors = SwitchbackColors(
		sky = MaterialTheme.colorScheme.surfaceVariant,
		farRidge = MaterialTheme.colorScheme.secondaryContainer,
		nearRidge = MaterialTheme.colorScheme.primaryContainer,
		trail = primary,
		trailHighlight = MaterialTheme.colorScheme.onPrimary,
		nextTurn = secondary,
		celebration = tertiary,
	)
	val description = switchbackDescription(payload)
	val headline = when {
		payload.goalReached -> "Trail complete!"
		!payload.hasFirstLeg -> "Find your line"
		else -> "Carve the trail"
	}
	val instruction = when {
		payload.goalReached -> "Every corner counted"
		!payload.hasFirstLeg ->
			"Walk ${payload.minimumLegMeters.roundToInt()} m to set your first trail leg"
		payload.expectedTurn == SwitchbackTurnDirection.LEFT -> "Turn left next"
		else -> "Turn right next"
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) { contentDescription = description },
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = headline,
			style = MaterialTheme.typography.titleMedium,
			color = if (payload.goalReached) tertiary else MaterialTheme.colorScheme.onSurface,
			fontWeight = FontWeight.Bold,
		)
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(SWITCHBACK_CANVAS_HEIGHT),
		) {
			drawSwitchbackScene(
				payload = payload,
				progress = progress,
				pulse = trailPulse,
				colors = colors,
			)
		}
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = RidgelineSpacing.Xs),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "${payload.turnsCompleted} / ${payload.targetTurns} turns",
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				fontWeight = FontWeight.Bold,
			)
			if (payload.currentCombo > 0) {
				Text(
					text = "${payload.currentCombo}× flow",
					style = MaterialTheme.typography.labelLarge,
					color = secondary,
					fontWeight = FontWeight.Bold,
				)
			}
		}
		Text(
			text = instruction,
			modifier = Modifier.fillMaxWidth(),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
		)
	}
}

private fun switchbackDescription(payload: SwitchbackVisualPayload): String {
	val progress = "${payload.turnsCompleted} of ${payload.targetTurns} turns complete."
	if (!payload.hasFirstLeg) {
		return "Switchback trail. Waiting for the first trail leg. $progress Goal in progress."
	}
	if (payload.goalReached) {
		return "Switchback trail. $progress Trail complete. " +
			"Best flow combo ${payload.bestCombo}. Goal complete."
	}
	val direction = if (payload.expectedTurn == SwitchbackTurnDirection.LEFT) {
		"Turn left next."
	} else {
		"Turn right next."
	}
	return "Switchback trail. $progress $direction Current flow combo ${payload.currentCombo}. " +
		"Goal in progress."
}

private fun DrawScope.drawSwitchbackScene(
	payload: SwitchbackVisualPayload,
	progress: Float,
	pulse: Float,
	colors: SwitchbackColors,
) {
	drawRoundRect(
		color = colors.sky.copy(alpha = 0.62f),
		cornerRadius = CornerRadius(size.minDimension * 0.08f),
	)
	drawRidge(
		color = colors.farRidge.copy(alpha = 0.58f),
		baselineY = size.height * 0.66f,
		peakY = size.height * 0.2f,
		horizontalShift = size.width * 0.12f,
	)
	drawRidge(
		color = colors.nearRidge.copy(alpha = 0.86f),
		baselineY = size.height,
		peakY = size.height * 0.38f,
		horizontalShift = -size.width * 0.15f,
	)

	val points = switchbackTrailPoints(
		turns = payload.recentTurns,
		hasFirstLeg = payload.hasFirstLeg,
	)
		.map { normalized ->
			Offset(normalized.x * size.width, normalized.y * size.height)
		}
	if (points.size >= 2) {
		val routePath = Path().apply {
			moveTo(points.first().x, points.first().y)
			points.drop(1).forEach { lineTo(it.x, it.y) }
		}
		drawPath(
			path = routePath,
			color = colors.trail.copy(alpha = 0.28f),
			style = Stroke(
				width = size.minDimension * 0.075f,
				cap = StrokeCap.Round,
				join = StrokeJoin.Round,
			),
		)
		drawPath(
			path = routePath,
			color = colors.trail,
			style = Stroke(
				width = size.minDimension * 0.035f,
				cap = StrokeCap.Round,
				join = StrokeJoin.Round,
			),
		)
		points.forEachIndexed { index, point ->
			val recency = (index + 1f) / points.size
			drawCircle(
				color = colors.trailHighlight.copy(alpha = 0.58f + recency * 0.38f),
				radius = size.minDimension * (0.016f + recency * 0.006f),
				center = point,
			)
		}
	}

	val progressWidth = size.width * 0.72f
	val progressLeft = (size.width - progressWidth) / 2f
	val progressTop = size.height * 0.89f
	val progressHeight = size.height * 0.035f
	drawRoundRect(
		color = colors.sky,
		topLeft = Offset(progressLeft, progressTop),
		size = Size(progressWidth, progressHeight),
		cornerRadius = CornerRadius(progressHeight),
	)
	drawRoundRect(
		color = if (payload.goalReached) colors.celebration else colors.trail,
		topLeft = Offset(progressLeft, progressTop),
		size = Size(progressWidth * progress, progressHeight),
		cornerRadius = CornerRadius(progressHeight),
	)

	if (payload.hasFirstLeg && !payload.goalReached) {
		val lastPoint = points.last()
		val direction = payload.expectedTurn ?: SwitchbackTurnDirection.LEFT
		val nextX = if (direction == SwitchbackTurnDirection.LEFT) {
			(lastPoint.x - size.width * 0.18f).coerceAtLeast(size.width * 0.14f)
		} else {
			(lastPoint.x + size.width * 0.18f).coerceAtMost(size.width * 0.86f)
		}
		val nextPoint = Offset(nextX, (lastPoint.y - size.height * 0.14f).coerceAtLeast(size.height * 0.08f))
		drawLine(
			color = colors.nextTurn.copy(alpha = 0.48f + pulse * 0.32f),
			start = lastPoint,
			end = nextPoint,
			strokeWidth = size.minDimension * 0.018f,
			cap = StrokeCap.Round,
			pathEffect = PathEffect.dashPathEffect(
				floatArrayOf(size.minDimension * 0.04f, size.minDimension * 0.025f),
			),
		)
		drawCircle(
			color = colors.nextTurn.copy(alpha = 0.2f + pulse * 0.18f),
			radius = size.minDimension * (0.045f + pulse * 0.012f),
			center = nextPoint,
		)
		drawCircle(
			color = colors.nextTurn,
			radius = size.minDimension * 0.018f,
			center = nextPoint,
		)
	}

	if (payload.goalReached) {
		repeat(3) { ring ->
			drawCircle(
				color = colors.celebration.copy(alpha = 0.62f - ring * 0.16f),
				radius = size.minDimension * (0.11f + ring * 0.075f),
				center = Offset(size.width / 2f, size.height * 0.43f),
				style = Stroke(width = size.minDimension * 0.014f),
			)
		}
	}
}

private fun DrawScope.drawRidge(
	color: Color,
	baselineY: Float,
	peakY: Float,
	horizontalShift: Float,
) {
	val path = Path().apply {
		moveTo(0f, baselineY)
		lineTo(size.width * 0.24f + horizontalShift, size.height * 0.48f)
		lineTo(size.width * 0.5f + horizontalShift, peakY)
		lineTo(size.width * 0.76f + horizontalShift, size.height * 0.52f)
		lineTo(size.width, baselineY)
		lineTo(size.width, size.height)
		lineTo(0f, size.height)
		close()
	}
	drawPath(path, color)
}

private fun switchbackTrailPoints(
	turns: List<SwitchbackTurnDirection>,
	hasFirstLeg: Boolean,
): List<Offset> {
	val points = ArrayList<Offset>(turns.size + 2)
	var x = 0.5f
	var y = 0.8f
	points += Offset(x, y)
	if (!hasFirstLeg) return points
	y -= TRAIL_VERTICAL_STEP
	points += Offset(x, y)
	turns.forEach { direction ->
		x = when (direction) {
			SwitchbackTurnDirection.LEFT -> (x - TRAIL_HORIZONTAL_STEP).coerceAtLeast(0.16f)
			SwitchbackTurnDirection.RIGHT -> (x + TRAIL_HORIZONTAL_STEP).coerceAtMost(0.84f)
		}
		y = (y - TRAIL_VERTICAL_STEP).coerceAtLeast(0.1f)
		points += Offset(x, y)
	}
	return points.takeLast(MAX_VISIBLE_TRAIL_POINTS)
}

private data class SwitchbackColors(
	val sky: Color,
	val farRidge: Color,
	val nearRidge: Color,
	val trail: Color,
	val trailHighlight: Color,
	val nextTurn: Color,
	val celebration: Color,
)

private val SWITCHBACK_CANVAS_HEIGHT = 240.dp
private const val TRAIL_HORIZONTAL_STEP = 0.22f
private const val TRAIL_VERTICAL_STEP = 0.105f
private const val MAX_VISIBLE_TRAIL_POINTS = 9
