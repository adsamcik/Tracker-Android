package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens

private const val LABEL_PADDING_PX = 4f

/**
 * Canvas-drawn altitude profile with area fill, bezier interpolation,
 * and left-to-right animated entrance.
 *
 * Shows a "No altitude data" placeholder when [altitudeHistory] is empty.
 */
@Composable
internal fun AltitudeSparkline(
	altitudeHistory: List<Float>,
	currentAltitude: Float?,
	modifier: Modifier = Modifier,
) {
	val tertiaryColor = MaterialTheme.colorScheme.tertiary
	val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
	val textStyle = MaterialTheme.typography.labelSmall
	val textMeasurer = rememberTextMeasurer()

	if (altitudeHistory.isEmpty()) {
		val noDataDesc = stringResource(R.string.dashboard_cd_no_altitude_data)
		val noDataText = stringResource(R.string.dashboard_no_altitude_data)
		Box(
			modifier = modifier
				.fillMaxWidth()
				.defaultMinSize(minHeight = 120.dp),
			contentAlignment = Alignment.Center,
		) {
			// Flat line placeholder
			Canvas(
				modifier = Modifier
					.fillMaxWidth()
					.defaultMinSize(minHeight = 120.dp)
					.semantics {
						contentDescription = noDataDesc
					},
			) {
				val y = size.height / 2f
				drawLine(
					color = onSurfaceVariant.copy(alpha = 0.3f),
					start = Offset(0f, y),
					end = Offset(size.width, y),
					strokeWidth = 1.dp.toPx(),
				)
			}
			Text(
				text = noDataText,
				style = MaterialTheme.typography.bodySmall,
				color = onSurfaceVariant.copy(alpha = 0.5f),
			)
		}
		return
	}

	val drawProgress = remember { Animatable(0f) }
	LaunchedEffect(altitudeHistory.size) {
		drawProgress.snapTo(0f)
		drawProgress.animateTo(
			targetValue = 1f,
			animationSpec = tween(
				durationMillis = MotionTokens.EXPRESSIVE_MS,
				easing = FastOutSlowInEasing,
			),
		)
	}

	val minAlt = altitudeHistory.min()
	val maxAlt = altitudeHistory.max()
	val altRange = (maxAlt - minAlt).coerceAtLeast(1f)

	val semanticDesc = stringResource(R.string.dashboard_format_altitude_range, minAlt, maxAlt)

	Canvas(
		modifier = modifier
			.fillMaxWidth()
			.defaultMinSize(minHeight = 120.dp)
			.semantics { contentDescription = semanticDesc },
	) {
		val leftPadding = 40.dp.toPx()
		val chartWidth = size.width - leftPadding
		val chartHeight = size.height

		if (altitudeHistory.size < 2 || chartWidth <= 0f) return@Canvas

		val points = altitudeHistory.mapIndexed { index, alt ->
			val x = leftPadding + (index.toFloat() / (altitudeHistory.size - 1)) * chartWidth
			val y = chartHeight - ((alt - minAlt) / altRange) * chartHeight
			Offset(x, y)
		}

		if (points.isEmpty()) return@Canvas

		// Left-to-right clip for wipe animation
		val clipRight = leftPadding + chartWidth * drawProgress.value

		clipRect(
			left = 0f,
			top = 0f,
			right = clipRight,
			bottom = chartHeight,
		) {
			val linePath = buildAltitudeBezierPath(points)

			// Area fill
			val fillPath = Path().apply {
				addPath(linePath)
				lineTo(points.last().x, chartHeight)
				lineTo(points.first().x, chartHeight)
				close()
			}

			drawPath(
				path = fillPath,
				brush = Brush.verticalGradient(
					colors = listOf(
						tertiaryColor.copy(alpha = 0.25f),
						Color.Transparent,
					),
					startY = 0f,
					endY = chartHeight,
				),
				style = Fill,
			)

			// Stroke
			drawPath(
				path = linePath,
				color = tertiaryColor,
				style = Stroke(
					width = 2.dp.toPx(),
					cap = StrokeCap.Round,
					join = StrokeJoin.Round,
				),
			)

			// Current altitude dot
			if (currentAltitude != null) {
				val lastPoint = points.last()
				drawCircle(
					color = tertiaryColor,
					radius = 4.dp.toPx(),
					center = lastPoint,
				)
				drawCircle(
					color = Color.White,
					radius = 2.dp.toPx(),
					center = lastPoint,
				)
			}
		}

		// Y-axis labels (min and max altitude)
		drawAltitudeLabels(
			textMeasurer = textMeasurer,
			textStyle = textStyle,
			color = onSurfaceVariant,
			minAlt = minAlt,
			maxAlt = maxAlt,
			chartHeight = chartHeight,
			leftPadding = leftPadding,
		)
	}
}

private fun buildAltitudeBezierPath(points: List<Offset>): Path {
	val path = Path()
	if (points.isEmpty()) return path

	path.moveTo(points.first().x, points.first().y)

	for (i in 0 until points.size - 1) {
		val p0 = points[i]
		val p1 = points[i + 1]
		val midX = (p0.x + p1.x) / 2f
		path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
	}

	return path
}

private fun DrawScope.drawAltitudeLabels(
	textMeasurer: TextMeasurer,
	textStyle: androidx.compose.ui.text.TextStyle,
	color: Color,
	minAlt: Float,
	maxAlt: Float,
	chartHeight: Float,
	leftPadding: Float,
) {
	val labels = listOf(
		"%.0f m".format(minAlt) to chartHeight - LABEL_PADDING_PX,
		"%.0f m".format(maxAlt) to LABEL_PADDING_PX + 10f,
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
