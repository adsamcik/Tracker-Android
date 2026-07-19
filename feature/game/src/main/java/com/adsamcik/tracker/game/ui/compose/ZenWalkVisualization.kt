package com.adsamcik.tracker.game.ui.compose

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.ZenPaceZone
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A calm pace field rather than a speedometer. The center is the player's
 * target rhythm, while gentle left/right drift communicates pace deviation.
 */
@Composable
internal fun ZenPaceGauge(
	snapshot: MiniGameSnapshot,
	modifier: Modifier = Modifier,
) {
	val payload = snapshot.visualPayload as MiniGameVisualPayload.ZenWalk
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		val zone = payload.targetPaceZone
		if (zone == null) {
			ZenCalibration(payload.calibrationProgress)
		} else {
			ZenFlowField(
				zone = zone,
				currentPaceMps = payload.currentSmoothedPaceMetersPerSecond,
				timeInZoneMs = payload.timeInZoneMs,
			)
		}
	}
}

@Composable
private fun ZenCalibration(progress: Double) {
	val reducedMotion = LocalReducedMotion.current
	val targetProgress = progress.toFloat().coerceIn(0f, 1f)
	val renderedProgress = animatedUnlessReduced(
		targetValue = targetProgress,
		reducedMotion = reducedMotion,
		label = "zen-calibration-settle",
	)
	val percent = (progress * 100.0).roundToInt()
	val description = stringResource(R.string.minigame_zen_calibration_description, percent)
	val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
	val rippleColor = MaterialTheme.colorScheme.secondary
	val centerColor = MaterialTheme.colorScheme.tertiary

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clearAndSetSemantics { contentDescription = description },
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(ZEN_CALIBRATION_HEIGHT),
		) {
			val cornerRadius = CornerRadius(size.height * 0.28f)
			drawRoundRect(
				color = surfaceColor.copy(alpha = 0.7f),
				cornerRadius = cornerRadius,
			)

			val center = Offset(size.width / 2f, size.height / 2f)
			val maxRadius = size.minDimension * 0.34f
			repeat(CALIBRATION_RIPPLE_COUNT) { index ->
				val rippleProgress = (renderedProgress + index * 0.18f).coerceAtMost(1f)
				val radius = maxRadius * (1f - index * 0.18f - rippleProgress * 0.05f)
				drawCircle(
					color = rippleColor.copy(alpha = 0.12f + rippleProgress * 0.12f),
					radius = radius,
					center = center,
					style = Stroke(
						width = size.minDimension * (0.016f + rippleProgress * 0.008f),
					),
				)
			}

			val progressRadius = maxRadius * 0.72f
			drawArc(
				color = rippleColor.copy(alpha = 0.9f),
				startAngle = -90f,
				sweepAngle = 360f * renderedProgress,
				useCenter = false,
				topLeft = Offset(center.x - progressRadius, center.y - progressRadius),
				size = Size(progressRadius * 2f, progressRadius * 2f),
				style = Stroke(
					width = size.minDimension * 0.035f,
					cap = StrokeCap.Round,
				),
			)
			drawCircle(
				brush = Brush.radialGradient(
					colors = listOf(
						centerColor.copy(alpha = 0.55f),
						centerColor.copy(alpha = 0f),
					),
					center = center,
					radius = maxRadius * (0.38f + renderedProgress * 0.1f),
				),
				radius = maxRadius * (0.38f + renderedProgress * 0.1f),
				center = center,
			)
			drawCircle(
				color = centerColor,
				radius = size.minDimension * (0.045f + renderedProgress * 0.012f),
				center = center,
			)
		}

		Text(
			text = stringResource(R.string.minigame_zen_calibration_title),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			fontWeight = FontWeight.SemiBold,
		)
		Text(
			text = stringResource(R.string.minigame_zen_calibration_hint),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(R.string.minigame_zen_calibration_settled, percent),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			fontWeight = FontWeight.SemiBold,
		)
	}
}

@Composable
private fun ZenFlowField(
	zone: ZenPaceZone,
	currentPaceMps: Double?,
	timeInZoneMs: Long,
) {
	val reducedMotion = LocalReducedMotion.current
	val normalizedDeviation = normalizedDeviation(currentPaceMps, zone)
	val driftState = when {
		currentPaceMps == null -> ZenDriftState.UNKNOWN
		normalizedDeviation < -1f -> ZenDriftState.SLOWER
		normalizedDeviation > 1f -> ZenDriftState.FASTER
		else -> ZenDriftState.SETTLED
	}
	val inZone = driftState == ZenDriftState.SETTLED
	val renderedDeviation = animatedUnlessReduced(
		targetValue = normalizedDeviation.coerceIn(-MAX_RENDERED_DEVIATION, MAX_RENDERED_DEVIATION),
		reducedMotion = reducedMotion,
		label = "zen-pace-drift",
	)
	val breath = zenBreath(active = inZone, reducedMotion = reducedMotion)
	val flowPhase = zenFlowPhase(reducedMotion)

	val settledColor = MaterialTheme.colorScheme.secondary
	val slowerColor = MaterialTheme.colorScheme.tertiary
	val fasterColor = MaterialTheme.colorScheme.primary
	val fieldColor = paceColor(
		normalizedDeviation = renderedDeviation,
		settledColor = settledColor,
		slowerColor = slowerColor,
		fasterColor = fasterColor,
	)
	val centerSurfaceColor = MaterialTheme.colorScheme.surface

	val paceLabel = currentPaceMps?.let {
		stringResource(R.string.minigame_zen_pace_value, formatKmh(it))
	} ?: stringResource(R.string.minigame_zen_pace_unknown)
	val targetLabel = stringResource(
		R.string.minigame_zen_target_zone,
		formatKmh(zone.minimumMetersPerSecond),
		formatKmh(zone.maximumMetersPerSecond),
	)
	val statusLabel = stringResource(
		when (driftState) {
			ZenDriftState.UNKNOWN -> R.string.minigame_zen_flow_waiting
			ZenDriftState.SETTLED -> R.string.minigame_zen_flow_settled
			ZenDriftState.SLOWER -> R.string.minigame_zen_flow_slower
			ZenDriftState.FASTER -> R.string.minigame_zen_flow_faster
		},
	)
	val flowDuration = formatFlowDuration(timeInZoneMs)
	val flowLabel = stringResource(R.string.minigame_zen_flow_time, flowDuration)
	val description = stringResource(
		R.string.minigame_zen_flow_description,
		paceLabel,
		targetLabel,
		statusLabel,
		flowLabel,
	)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = description
			},
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(ZEN_FLOW_HEIGHT)
				.clearAndSetSemantics { },
		) {
			val cornerRadius = CornerRadius(size.height * 0.28f)
			drawRoundRect(
				brush = Brush.horizontalGradient(
					colors = listOf(
						slowerColor.copy(alpha = 0.18f),
						settledColor.copy(alpha = 0.24f),
						fasterColor.copy(alpha = 0.18f),
					),
				),
				cornerRadius = cornerRadius,
			)

			val center = Offset(size.width / 2f, size.height / 2f)
			drawCircle(
				brush = Brush.radialGradient(
					colors = listOf(
						settledColor.copy(alpha = 0.24f + breath * 0.06f),
						settledColor.copy(alpha = 0f),
					),
					center = center,
					radius = size.width * 0.34f,
				),
				radius = size.width * 0.34f,
				center = center,
			)

			val waveAmplitude = size.height * (0.035f + breath * 0.012f)
			repeat(FLOW_WAVE_COUNT) { index ->
				val y = size.height * (0.36f + index * 0.14f)
				val path = Path()
				for (step in 0..FLOW_WAVE_STEPS) {
					val x = size.width * step / FLOW_WAVE_STEPS
					val angle = step.toDouble() / FLOW_WAVE_STEPS * PI * 2.0 +
						index * 1.1 -
						flowPhase * PI * 2.0
					val waveY = y + sin(angle).toFloat() * waveAmplitude
					if (step == 0) path.moveTo(x, waveY) else path.lineTo(x, waveY)
				}
				drawPath(
					path = path,
					color = fieldColor.copy(alpha = 0.16f + index * 0.06f),
					style = Stroke(
						width = size.height * (0.014f + index * 0.004f),
						cap = StrokeCap.Round,
					),
				)
			}

			if (currentPaceMps != null) {
				val markerX = size.width * (
					0.5f + renderedDeviation * MARKER_DEVIATION_SCALE
					).coerceIn(MARKER_MIN_FRACTION, MARKER_MAX_FRACTION)
				val markerCenter = Offset(markerX, center.y)
				val sustainedFlow = sqrt(
					(timeInZoneMs.toDouble() / FLOW_HALO_FULL_MS).coerceIn(0.0, 1.0),
				).toFloat()
				val haloRadius = size.height * (
					0.16f + sustainedFlow * 0.11f + if (inZone) breath * 0.012f else 0f
					)
				drawCircle(
					color = fieldColor.copy(alpha = 0.08f + sustainedFlow * 0.09f),
					radius = haloRadius,
					center = markerCenter,
				)
				drawCircle(
					color = fieldColor.copy(alpha = 0.16f + sustainedFlow * 0.1f),
					radius = haloRadius * 0.68f,
					center = markerCenter,
				)
				val markerRadius = size.height * (0.072f + if (inZone) breath * 0.006f else 0f)
				drawCircle(
					color = centerSurfaceColor,
					radius = markerRadius + size.height * 0.014f,
					center = markerCenter,
				)
				drawCircle(
					color = fieldColor,
					radius = markerRadius,
					center = markerCenter,
				)
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Xs),
			) {
				Text(
					text = stringResource(R.string.minigame_zen_pace_label),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = paceLabel,
					style = MaterialTheme.typography.headlineSmall,
					color = MaterialTheme.colorScheme.onSurface,
					fontWeight = FontWeight.SemiBold,
				)
			}
			Surface(
				color = MaterialTheme.colorScheme.secondaryContainer,
				contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
				shape = MaterialTheme.shapes.extraLarge,
			) {
				Text(
					text = flowLabel,
					modifier = Modifier.padding(
						horizontal = RidgelineSpacing.Sm,
						vertical = RidgelineSpacing.Xs,
					),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
				)
			}
		}
		Text(
			text = statusLabel,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.onSurface,
			fontWeight = FontWeight.SemiBold,
		)
		Text(
			text = targetLabel,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun animatedUnlessReduced(
	targetValue: Float,
	reducedMotion: Boolean,
	label: String,
): Float {
	if (reducedMotion) return targetValue
	val value by animateFloatAsState(
		targetValue = targetValue,
		animationSpec = RidgelineMotion.Drift,
		label = label,
	)
	return value
}

@Composable
private fun zenBreath(active: Boolean, reducedMotion: Boolean): Float {
	if (!active || reducedMotion) return SETTLED_BREATH
	val transition = rememberInfiniteTransition(label = "zen-breath")
	val breath by transition.animateFloat(
		initialValue = SETTLED_BREATH,
		targetValue = BREATH_MAX,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = BREATH_HALF_CYCLE_MS),
			repeatMode = RepeatMode.Reverse,
		),
		label = "zen-breath-amplitude",
	)
	return breath
}

@Composable
private fun zenFlowPhase(reducedMotion: Boolean): Float {
	if (reducedMotion) return STATIC_FLOW_PHASE
	val transition = rememberInfiniteTransition(label = "zen-water-flow")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(
				durationMillis = FLOW_CYCLE_MS,
				easing = LinearEasing,
			),
			repeatMode = RepeatMode.Restart,
		),
		label = "zen-water-flow-phase",
	)
	return phase
}

private fun normalizedDeviation(currentPaceMps: Double?, zone: ZenPaceZone): Float {
	if (currentPaceMps == null) return 0f
	val center = (zone.minimumMetersPerSecond + zone.maximumMetersPerSecond) / 2.0
	val halfWidth = (zone.maximumMetersPerSecond - zone.minimumMetersPerSecond) / 2.0
	return ((currentPaceMps - center) / halfWidth).toFloat()
}

private fun paceColor(
	normalizedDeviation: Float,
	settledColor: Color,
	slowerColor: Color,
	fasterColor: Color,
): Color {
	val driftAmount = (abs(normalizedDeviation) / MAX_RENDERED_DEVIATION).coerceIn(0f, 1f)
	val driftColor = if (normalizedDeviation < 0f) slowerColor else fasterColor
	return lerp(settledColor, driftColor, driftAmount)
}

internal fun formatKmh(metersPerSecond: Double): String =
	"%.1f".format(metersPerSecond * MPS_TO_KMH)

internal fun formatFlowDuration(timeInZoneMs: Long): String {
	val totalSeconds = (timeInZoneMs / MILLIS_PER_SECOND).coerceAtLeast(0L)
	val minutes = totalSeconds / SECONDS_PER_MINUTE
	val seconds = totalSeconds % SECONDS_PER_MINUTE
	return "%d:%02d".format(minutes, seconds)
}

private enum class ZenDriftState {
	UNKNOWN,
	SETTLED,
	SLOWER,
	FASTER,
}

private const val MPS_TO_KMH = 3.6
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MAX_RENDERED_DEVIATION = 2f
private const val MARKER_DEVIATION_SCALE = 0.22f
private const val MARKER_MIN_FRACTION = 0.08f
private const val MARKER_MAX_FRACTION = 0.92f
private const val FLOW_HALO_FULL_MS = 300_000.0
private const val CALIBRATION_RIPPLE_COUNT = 3
private const val FLOW_WAVE_COUNT = 3
private const val FLOW_WAVE_STEPS = 36
private const val FLOW_CYCLE_MS = 12_000
private const val BREATH_HALF_CYCLE_MS = 3_600
private const val BREATH_MAX = 1f
private const val SETTLED_BREATH = 0.55f
private const val STATIC_FLOW_PHASE = 0.18f
private val ZEN_CALIBRATION_HEIGHT = 132.dp
private val ZEN_FLOW_HEIGHT = 168.dp
