package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion

private const val SCROLL_THRESHOLD = 30
private const val LABEL_PADDING_PX = 4f
private const val POINT_SPACING_DP = 12

/**
 * Canvas-drawn speed-over-time sparkline with gradient fill, bezier interpolation,
 * pulsing current-speed dot, and max-speed indicator.
 *
 * Horizontally scrollable when data exceeds [SCROLL_THRESHOLD] points.
 */
@Composable
internal fun SpeedSparkline(
	speedHistory: List<Float>,
	currentSpeed: Float?,
	maxSpeed: Float?,
	modifier: Modifier = Modifier,
) {
	// Pre-compute the a11y description from the *unfiltered* sample count so the
	// early-return placeholder ("not enough data to draw") and the rendered
	// sparkline both expose the same accessible label. Without this the
	// empty/single-point case rendered an unlabeled Box — invisible to screen
	// readers and impossible to assert against in Compose tests.
	val contentDescription = stringResource(
		R.string.dashboard_cd_speed_sparkline,
		speedHistory.size,
	)

	if (speedHistory.size < 2) {
		Box(
			modifier = modifier
				.defaultMinSize(minHeight = 120.dp)
				.semantics { this.contentDescription = contentDescription },
		)
		return
	}

	val primaryColor = MaterialTheme.colorScheme.primary
	val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
	val errorColor = MaterialTheme.colorScheme.error
	val textStyle = MaterialTheme.typography.labelSmall
	val textMeasurer = rememberTextMeasurer()

	// Start at 1f to prevent blank chart on recomposition after navigation
	val drawProgress = remember { Animatable(1f) }

	// Pulsing dot for current speed
	val reducedMotion = LocalReducedMotion.current
	val pulseRadius = if (reducedMotion) {
		4f
	} else {
		val pulseTransition = rememberInfiniteTransition(label = "speed_pulse")
		val animatedPulseRadius by pulseTransition.animateFloat(
			initialValue = 4f,
			targetValue = 7f,
			animationSpec = infiniteRepeatable(
				animation = tween(MotionTokens.AMBIENT_MS),
				repeatMode = RepeatMode.Reverse,
			),
			label = "pulse_radius",
		)
		animatedPulseRadius
	}

	// Auto-range Y from data min/max with padding for visible variation
	val dataMin = speedHistory.min()
	val dataMax = speedHistory.max()
	val dataRange = (dataMax - dataMin).coerceAtLeast(0.5f)
	val effectiveMin = (dataMin - dataRange * 0.1f).coerceAtLeast(0f)
	val effectiveMax = dataMax + dataRange * 0.1f
	val effectiveRange = (effectiveMax - effectiveMin).coerceAtLeast(0.01f)
	val midValue = (effectiveMin + effectiveMax) / 2f

	Box(
		modifier = modifier.defaultMinSize(minHeight = 120.dp),
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.defaultMinSize(minHeight = 120.dp)
				.semantics { this.contentDescription = contentDescription },
		) {
			val leftPadding = 40.dp.toPx()
			val chartWidth = size.width - leftPadding
			val chartHeight = size.height

			if (speedHistory.size < 2 || chartWidth <= 0f) return@Canvas

			val pointsToDraw = (speedHistory.size * drawProgress.value)
				.toInt()
				.coerceAtLeast(2)

			val points = speedHistory.take(pointsToDraw).mapIndexed { index, speed ->
				val x = leftPadding + (index.toFloat() / (speedHistory.size - 1).coerceAtLeast(1)) * chartWidth
				val normalized = ((speed - effectiveMin) / effectiveRange).coerceIn(0f, 1f)
				val y = chartHeight - normalized * chartHeight
				Offset(x, y)
			}

			if (points.isEmpty()) return@Canvas

			// Gradient fill below the curve
			val fillPath = buildBezierPath(points)
			val closedFillPath = Path().apply {
				addPath(fillPath)
				lineTo(points.last().x, chartHeight)
				lineTo(points.first().x, chartHeight)
				close()
			}

			drawPath(
				path = closedFillPath,
				brush = Brush.verticalGradient(
					colors = listOf(
						primaryColor.copy(alpha = 0.3f),
						Color.Transparent,
					),
					startY = 0f,
					endY = chartHeight,
				),
				style = Fill,
			)

			// Line
			drawPath(
				path = fillPath,
				color = primaryColor,
				style = Stroke(
					width = 2.dp.toPx(),
					cap = StrokeCap.Round,
					join = StrokeJoin.Round,
				),
			)

			// Max speed indicator
			val maxIndex = speedHistory.take(pointsToDraw)
				.indexOfFirst { it == speedHistory.take(pointsToDraw).max() }
			if (maxIndex >= 0 && maxIndex < points.size) {
				val maxPoint = points[maxIndex]
				drawCircle(
					color = errorColor,
					radius = 3.dp.toPx(),
					center = maxPoint,
				)
			}

			// Current speed pulsing dot
			if (currentSpeed != null && drawProgress.value > 0.9f) {
				val lastPoint = points.last()
				val alpha = ((drawProgress.value - 0.9f) / 0.1f).coerceIn(0f, 1f)
				drawCircle(
					color = primaryColor.copy(alpha = alpha * 0.3f),
					radius = pulseRadius.dp.toPx(),
					center = lastPoint,
				)
				drawCircle(
					color = primaryColor.copy(alpha = alpha),
					radius = 4.dp.toPx(),
					center = lastPoint,
				)
			}

			// Y-axis labels
			drawYAxisLabels(
				textMeasurer = textMeasurer,
				textStyle = textStyle,
				color = onSurfaceVariant,
				maxValue = effectiveMax,
				midValue = midValue,
				chartHeight = chartHeight,
				leftPadding = leftPadding,
			)
		}
	}
}

private fun buildBezierPath(points: List<Offset>): Path {
	val path = Path()
	if (points.isEmpty()) return path

	path.moveTo(points.first().x, points.first().y)

	if (points.size == 1) return path

	for (i in 0 until points.size - 1) {
		val p0 = points[i]
		val p1 = points[i + 1]
		val midX = (p0.x + p1.x) / 2f
		path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
	}

	return path
}

private fun DrawScope.drawYAxisLabels(
	textMeasurer: TextMeasurer,
	textStyle: androidx.compose.ui.text.TextStyle,
	color: Color,
	maxValue: Float,
	midValue: Float,
	chartHeight: Float,
	leftPadding: Float,
) {
	val fmt = if (maxValue < 10f) "%.1f" else "%.0f"
	val maxLabel = fmt.format(maxValue)
	val midLabel = fmt.format(midValue)

	// Max anchored to top
	val maxR = textMeasurer.measure(maxLabel, textStyle)
	drawText(maxR, color.copy(alpha = 0.6f), Offset(leftPadding - maxR.size.width - LABEL_PADDING_PX, 4f))

	// "0" anchored to bottom
	val zeroR = textMeasurer.measure("0", textStyle)
	drawText(zeroR, color.copy(alpha = 0.6f), Offset(leftPadding - zeroR.size.width - LABEL_PADDING_PX, chartHeight - zeroR.size.height - 4f))

	// Mid only if distinct and chart tall enough
	if (midLabel != "0" && midLabel != maxLabel && chartHeight > maxR.size.height * 5f) {
		val midR = textMeasurer.measure(midLabel, textStyle)
		drawText(midR, color.copy(alpha = 0.6f), Offset(leftPadding - midR.size.width - LABEL_PADDING_PX, chartHeight / 2f - midR.size.height / 2f))
	}
}
