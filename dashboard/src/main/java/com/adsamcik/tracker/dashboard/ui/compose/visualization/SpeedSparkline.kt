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
	if (speedHistory.isEmpty()) return

	val primaryColor = MaterialTheme.colorScheme.primary
	val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
	val errorColor = MaterialTheme.colorScheme.error
	val textStyle = MaterialTheme.typography.labelSmall
	val textMeasurer = rememberTextMeasurer()

	val drawProgress = remember { Animatable(0f) }
	LaunchedEffect(speedHistory.size) {
		drawProgress.snapTo(0f)
		drawProgress.animateTo(
			targetValue = 1f,
			animationSpec = tween(
				durationMillis = MotionTokens.EXPRESSIVE_MS,
				easing = FastOutSlowInEasing,
			),
		)
	}

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

	val computedMax = maxSpeed ?: speedHistory.max()
	val effectiveMax = computedMax.coerceAtLeast(0.1f)
	val midValue = effectiveMax / 2f

	val needsScroll = speedHistory.size > SCROLL_THRESHOLD
	val scrollState = rememberScrollState()

	val contentDescription = stringResource(R.string.dashboard_cd_speed_sparkline, speedHistory.size)

	val scrollModifier = if (needsScroll) {
		Modifier.horizontalScroll(scrollState)
	} else {
		Modifier.fillMaxWidth()
	}

	Box(
		modifier = modifier.defaultMinSize(minHeight = 120.dp),
	) {
		Canvas(
			modifier = scrollModifier
				.then(
					if (needsScroll) {
						Modifier.defaultMinSize(
							minWidth = (speedHistory.size * POINT_SPACING_DP).dp,
							minHeight = 120.dp,
						)
					} else {
						Modifier
							.fillMaxWidth()
							.defaultMinSize(minHeight = 120.dp)
					},
				)
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
				val y = chartHeight - (speed / effectiveMax).coerceIn(0f, 1f) * chartHeight
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
	val labels = listOf(
		"0" to chartHeight - LABEL_PADDING_PX,
		"%.0f".format(midValue) to chartHeight / 2f,
		"%.0f".format(maxValue) to LABEL_PADDING_PX + 10f,
	)

	labels.forEach { (text, y) ->
		val result = textMeasurer.measure(
			text = text,
			style = textStyle,
		)
		drawText(
			textLayoutResult = result,
			color = color.copy(alpha = 0.6f),
			topLeft = Offset(
				x = leftPadding - result.size.width - LABEL_PADDING_PX,
				y = y - result.size.height / 2f,
			),
		)
	}
}
