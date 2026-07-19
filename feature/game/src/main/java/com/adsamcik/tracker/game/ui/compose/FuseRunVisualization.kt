package com.adsamcik.tracker.game.ui.compose

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.minigame.FuseRunVisualPayload
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A session-relative blast arena. The player marker's angle is decorative and
 * deterministic; only distance from the private charge origin affects progress.
 */
@Composable
internal fun FuseRunComposable(
	payload: FuseRunVisualPayload,
	modifier: Modifier = Modifier,
) {
	val reducedMotion = LocalReducedMotion.current
	val targetDistanceFraction = payload.distanceFraction.toFloat()
	val animatedDistanceFraction by animateFloatAsState(
		targetValue = targetDistanceFraction,
		animationSpec = if (reducedMotion) RidgelineMotion.Settle else RidgelineMotion.Respond,
		label = "fuse-run-distance",
	)
	val targetFuseFraction = payload.fuseFraction.toFloat()
	val animatedFuseFraction by animateFloatAsState(
		targetValue = targetFuseFraction,
		animationSpec = if (reducedMotion) RidgelineMotion.Settle else RidgelineMotion.Respond,
		label = "fuse-run-timer",
	)
	val ambientTransition = rememberInfiniteTransition(label = "fuse-run-pulse")
	val ambientPulse by ambientTransition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = 1_200),
			repeatMode = RepeatMode.Reverse,
		),
		label = "fuse-run-pulse-value",
	)
	val renderedDistance = if (reducedMotion) targetDistanceFraction else animatedDistanceFraction
	val renderedFuse = if (reducedMotion) targetFuseFraction else animatedFuseFraction
	val renderedPulse = if (reducedMotion || !payload.hasStarted) 0f else ambientPulse
	val secondsRemaining = ceil(payload.remainingTimeMs / 1_000.0).toInt()
	val description = fuseRunDescription(payload, secondsRemaining)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = description
			},
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		FuseRunStatus(payload)
		FuseRunArena(
			payload = payload,
			distanceFraction = renderedDistance,
			fuseFraction = renderedFuse,
			pulse = renderedPulse,
			description = description,
		)
		ChargeProgress(
			defusedCharges = payload.defusedCharges,
			targetCharges = payload.targetCharges,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		) {
			FuseMetric(
				label = "Blast radius",
				value = "${payload.distanceFromChargeMeters.roundToInt()} / " +
					"${payload.requiredDistanceMeters.roundToInt()} m",
				accent = MaterialTheme.colorScheme.primary,
				modifier = Modifier.weight(1f),
			)
			FuseMetric(
				label = "Fuse",
				value = if (payload.hasStarted) "${secondsRemaining}s" else "Ready",
				accent = if (payload.isFuseCritical) {
					MaterialTheme.colorScheme.tertiary
				} else {
					MaterialTheme.colorScheme.secondary
				},
				modifier = Modifier.weight(1f),
			)
			FuseMetric(
				label = "Streak",
				value = "${payload.currentStreak}",
				accent = MaterialTheme.colorScheme.tertiary,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun FuseRunStatus(payload: FuseRunVisualPayload) {
	val (text, container, content) = when {
		payload.isGoalReached -> Triple(
			"Blast chain complete!",
			MaterialTheme.colorScheme.primaryContainer,
			MaterialTheme.colorScheme.onPrimaryContainer,
		)
		!payload.hasStarted -> Triple(
			"Waiting for a GPS fix",
			MaterialTheme.colorScheme.surfaceVariant,
			MaterialTheme.colorScheme.onSurfaceVariant,
		)
		payload.isFuseCritical -> Triple(
			"Fuse critical — move!",
			MaterialTheme.colorScheme.tertiaryContainer,
			MaterialTheme.colorScheme.onTertiaryContainer,
		)
		else -> Triple(
			"Escape the blast radius",
			MaterialTheme.colorScheme.secondaryContainer,
			MaterialTheme.colorScheme.onSecondaryContainer,
		)
	}
	Surface(
		color = container,
		contentColor = content,
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
private fun FuseRunArena(
	payload: FuseRunVisualPayload,
	distanceFraction: Float,
	fuseFraction: Float,
	pulse: Float,
	description: String,
) {
	val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
	val trackColor = MaterialTheme.colorScheme.outlineVariant
	val safeColor = MaterialTheme.colorScheme.primary
	val fuseColor = if (payload.isFuseCritical) {
		MaterialTheme.colorScheme.tertiary
	} else {
		MaterialTheme.colorScheme.secondary
	}
	val chargeColor = MaterialTheme.colorScheme.tertiary
	val playerColor = MaterialTheme.colorScheme.primary
	val playerHighlightColor = MaterialTheme.colorScheme.onPrimary
	val celebrationColor = MaterialTheme.colorScheme.secondary

	Surface(
		color = surfaceColor,
		shape = MaterialTheme.shapes.extraLarge,
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(FUSE_ARENA_HEIGHT)
				.clearAndSetSemantics { contentDescription = description },
		) {
			val center = Offset(size.width / 2f, size.height / 2f)
			val targetRadius = min(size.width, size.height) * 0.34f
			val playerAngle = Math.toRadians(
				(payload.roundNumber * ROUND_ANGLE_STEP_DEGREES - 90).toDouble(),
			)
			val playerRadius = targetRadius * distanceFraction.coerceIn(0f, 1.08f)
			val playerCenter = Offset(
				x = center.x + cos(playerAngle).toFloat() * playerRadius,
				y = center.y + sin(playerAngle).toFloat() * playerRadius,
			)

			drawCircle(
				color = fuseColor.copy(alpha = 0.08f + pulse * 0.08f),
				radius = targetRadius + pulse * 12.dp.toPx(),
				center = center,
			)
			drawCircle(
				color = trackColor,
				radius = targetRadius,
				center = center,
				style = Stroke(width = 4.dp.toPx()),
			)
			drawCircle(
				color = safeColor.copy(alpha = 0.18f),
				radius = targetRadius,
				center = center,
				style = Stroke(width = 14.dp.toPx()),
			)
			drawLine(
				color = safeColor.copy(alpha = 0.45f),
				start = center,
				end = playerCenter,
				strokeWidth = 5.dp.toPx(),
				cap = StrokeCap.Round,
			)
			drawArc(
				color = fuseColor,
				startAngle = -90f,
				sweepAngle = 360f * fuseFraction,
				useCenter = false,
				topLeft = Offset(
					center.x - CHARGE_FUSE_RADIUS.toPx(),
					center.y - CHARGE_FUSE_RADIUS.toPx(),
				),
				size = Size(
					CHARGE_FUSE_RADIUS.toPx() * 2f,
					CHARGE_FUSE_RADIUS.toPx() * 2f,
				),
				style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
			)
			drawCharge(center, chargeColor, pulse)
			drawPlayer(playerCenter, playerColor, playerHighlightColor)

			if (payload.isFuseCritical) {
				drawSparks(center, targetRadius, fuseColor, pulse)
			}
			if (payload.isGoalReached) {
				drawCelebration(center, targetRadius, celebrationColor)
			}
		}
	}
}

@Composable
private fun ChargeProgress(
	defusedCharges: Int,
	targetCharges: Int,
) {
	val completedColor = MaterialTheme.colorScheme.primary
	val pendingColor = MaterialTheme.colorScheme.surfaceVariant
	Column(verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "Charges defused",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurface,
				fontWeight = FontWeight.Bold,
			)
			Text(
				text = "$defusedCharges / $targetCharges",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
				fontWeight = FontWeight.Bold,
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
		) {
			repeat(targetCharges) { index ->
				Canvas(
					modifier = Modifier
						.weight(1f)
						.height(12.dp),
				) {
					drawRoundRect(
						color = if (index < defusedCharges) {
							completedColor
						} else {
							pendingColor
						},
						cornerRadius = CornerRadius(size.height / 2f),
					)
				}
			}
		}
	}
}

@Composable
private fun FuseMetric(
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
				style = MaterialTheme.typography.labelSmall,
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

private fun DrawScope.drawCharge(
	center: Offset,
	color: Color,
	pulse: Float,
) {
	drawCircle(
		color = color.copy(alpha = 0.18f),
		radius = 29.dp.toPx() + pulse * 3.dp.toPx(),
		center = center,
	)
	drawCircle(
		color = color,
		radius = 18.dp.toPx(),
		center = center,
	)
	drawLine(
		color = color,
		start = Offset(center.x + 10.dp.toPx(), center.y - 12.dp.toPx()),
		end = Offset(center.x + 20.dp.toPx(), center.y - 25.dp.toPx()),
		strokeWidth = 4.dp.toPx(),
		cap = StrokeCap.Round,
	)
	drawCircle(
		color = color.copy(alpha = 0.65f + pulse * 0.35f),
		radius = 4.dp.toPx(),
		center = Offset(center.x + 21.dp.toPx(), center.y - 27.dp.toPx()),
	)
}

private fun DrawScope.drawPlayer(
	center: Offset,
	color: Color,
	highlightColor: Color,
) {
	drawCircle(
		color = color.copy(alpha = 0.2f),
		radius = 18.dp.toPx(),
		center = center,
	)
	drawCircle(
		color = color,
		radius = 10.dp.toPx(),
		center = center,
	)
	drawCircle(
		color = highlightColor.copy(alpha = 0.9f),
		radius = 3.dp.toPx(),
		center = Offset(center.x - 2.dp.toPx(), center.y - 2.dp.toPx()),
	)
}

private fun DrawScope.drawSparks(
	center: Offset,
	radius: Float,
	color: Color,
	pulse: Float,
) {
	repeat(SPARK_COUNT) { index ->
		val angle = index * (2.0 * PI / SPARK_COUNT) + pulse * 0.25
		val startRadius = radius * 0.68f
		val endRadius = startRadius + (8.dp.toPx() + pulse * 9.dp.toPx())
		drawLine(
			color = color.copy(alpha = 0.45f + pulse * 0.4f),
			start = Offset(
				center.x + cos(angle).toFloat() * startRadius,
				center.y + sin(angle).toFloat() * startRadius,
			),
			end = Offset(
				center.x + cos(angle).toFloat() * endRadius,
				center.y + sin(angle).toFloat() * endRadius,
			),
			strokeWidth = 3.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun DrawScope.drawCelebration(
	center: Offset,
	radius: Float,
	color: Color,
) {
	repeat(CELEBRATION_RAY_COUNT) { index ->
		val angle = index * (2.0 * PI / CELEBRATION_RAY_COUNT)
		val startRadius = radius * 0.84f
		val endRadius = radius * 1.08f
		drawLine(
			color = color.copy(alpha = 0.8f),
			start = Offset(
				center.x + cos(angle).toFloat() * startRadius,
				center.y + sin(angle).toFloat() * startRadius,
			),
			end = Offset(
				center.x + cos(angle).toFloat() * endRadius,
				center.y + sin(angle).toFloat() * endRadius,
			),
			strokeWidth = 5.dp.toPx(),
			cap = StrokeCap.Round,
		)
	}
}

private fun fuseRunDescription(
	payload: FuseRunVisualPayload,
	secondsRemaining: Int,
): String = when {
	payload.isGoalReached ->
		"Fuse Run complete. ${payload.defusedCharges} of ${payload.targetCharges} charges defused. " +
			"Best streak ${payload.bestStreak}."
	!payload.hasStarted ->
		"Fuse Run waiting for a GPS fix. Goal ${payload.targetCharges} charges."
	else ->
		"Charge ${payload.defusedCharges + 1} of ${payload.targetCharges}. " +
			"${formatMetres(payload.distanceFromChargeMeters)} of " +
			"${formatMetres(payload.requiredDistanceMeters)} metres from the charge. " +
			"$secondsRemaining seconds remaining. Streak ${payload.currentStreak}."
}

private fun formatMetres(value: Double): String =
	String.format(Locale.US, "%.0f", value)

private const val ROUND_ANGLE_STEP_DEGREES: Int = 47
private const val SPARK_COUNT: Int = 10
private const val CELEBRATION_RAY_COUNT: Int = 12
private val FUSE_ARENA_HEIGHT = 220.dp
private val CHARGE_FUSE_RADIUS = 33.dp
