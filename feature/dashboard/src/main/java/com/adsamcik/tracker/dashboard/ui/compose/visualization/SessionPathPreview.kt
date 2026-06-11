package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.shared.model.Location

/**
 * Canvas-drawn route path preview with animated drawing progress and start/end markers.
 *
 * Ported from TrackerDashboard's SessionPathPreview to the dashboard module,
 * using dashboard MotionTokens for animation timing.
 */
@Composable
internal fun SessionPathPreview(
	points: List<Location>,
	modifier: Modifier = Modifier,
) {
	val primaryColor = MaterialTheme.colorScheme.primary
	val startColor = MaterialTheme.colorScheme.tertiary
	val endColor = MaterialTheme.colorScheme.error
	val pathDescription = stringResource(R.string.dashboard_cd_session_path, points.size)

	val pathProgress = remember { Animatable(0f) }

	LaunchedEffect(points) {
		pathProgress.snapTo(0f)
		pathProgress.animateTo(
			targetValue = 1f,
			animationSpec = tween(
				durationMillis = MotionTokens.EXPRESSIVE_MS,
				easing = FastOutSlowInEasing,
			),
		)
	}

	Canvas(
		modifier = modifier.semantics {
			contentDescription = pathDescription
		},
	) {
		if (points.size < 2) return@Canvas

		var minLat = Double.MAX_VALUE
		var maxLat = Double.MIN_VALUE
		var minLon = Double.MAX_VALUE
		var maxLon = Double.MIN_VALUE

		points.forEach { p ->
			minLat = minOf(minLat, p.latitude)
			maxLat = maxOf(maxLat, p.latitude)
			minLon = minOf(minLon, p.longitude)
			maxLon = maxOf(maxLon, p.longitude)
		}

		val latRange = maxLat - minLat
		val lonRange = maxLon - minLon
		if (latRange == 0.0 && lonRange == 0.0) return@Canvas

		val latPadding = if (latRange == 0.0) 0.001 else latRange * 0.1
		val lonPadding = if (lonRange == 0.0) 0.001 else lonRange * 0.1

		val drawMinLat = minLat - latPadding
		val drawMaxLat = maxLat + latPadding
		val drawMinLon = minLon - lonPadding
		val drawMaxLon = maxLon + lonPadding

		val drawLatRange = drawMaxLat - drawMinLat
		val drawLonRange = drawMaxLon - drawMinLon

		val width = size.width
		val height = size.height

		val screenPoints = points.map { p ->
			val x = ((p.longitude - drawMinLon) / drawLonRange).toFloat() * width
			val y = (1 - ((p.latitude - drawMinLat) / drawLatRange)).toFloat() * height
			Offset(x, y)
		}

		val pointsToDraw = (screenPoints.size * pathProgress.value).toInt().coerceAtLeast(2)

		val path = Path()
		screenPoints.take(pointsToDraw).forEachIndexed { index, offset ->
			if (index == 0) {
				path.moveTo(offset.x, offset.y)
			} else {
				path.lineTo(offset.x, offset.y)
			}
		}

		drawPath(
			path = path,
			color = primaryColor,
			style = Stroke(
				width = 3.dp.toPx(),
				cap = StrokeCap.Round,
				join = StrokeJoin.Round,
			),
			alpha = 0.6f,
		)

		// Start marker
		val startPoint = screenPoints.firstOrNull()
		if (startPoint != null && pathProgress.value > 0.05f) {
			drawCircle(
				color = startColor,
				radius = 6.dp.toPx(),
				center = startPoint,
				alpha = pathProgress.value,
			)
			drawCircle(
				color = Color.White,
				radius = 3.dp.toPx(),
				center = startPoint,
				alpha = pathProgress.value,
			)
		}

		// End marker — fades in near completion
		val endPoint = screenPoints.lastOrNull()
		if (endPoint != null && pathProgress.value > 0.9f) {
			val endAlpha = ((pathProgress.value - 0.9f) / 0.1f).coerceIn(0f, 1f)
			drawCircle(
				color = endColor,
				radius = 6.dp.toPx(),
				center = endPoint,
				alpha = endAlpha,
			)
			drawCircle(
				color = Color.White,
				radius = 3.dp.toPx(),
				center = endPoint,
				alpha = endAlpha,
			)
		}
	}
}
