package com.adsamcik.tracker.dashboard.ui.compose.visualization

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import kotlinx.coroutines.delay

private const val BAR_COUNT = 7
private const val MIN_BAR_FRACTION = 0.05f

/**
 * Seven-day bar chart showing daily activity levels with staggered spring animation.
 *
 * Today's bar is highlighted in primary color; other bars use surfaceVariant.
 * Fixed height of 80dp, responsive width.
 */
@Composable
internal fun WeeklyTrendBars(
	dailyValues: List<Float>,
	labels: List<String>,
	highlightIndex: Int?,
	modifier: Modifier = Modifier,
) {
	require(dailyValues.size == BAR_COUNT) { "dailyValues must contain exactly 7 elements" }
	require(labels.size == BAR_COUNT) { "labels must contain exactly 7 elements" }

	val primaryColor = MaterialTheme.colorScheme.primary
	val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
	val labelStyle = MaterialTheme.typography.labelSmall
	val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

	val barAnimations = remember(dailyValues) {
		List(BAR_COUNT) { Animatable(0f) }
	}

	LaunchedEffect(dailyValues) {
		barAnimations.forEachIndexed { index, animatable ->
			delay(index.coerceAtMost(MotionTokens.STAGGER_MAX_DEPTH) * MotionTokens.STAGGER_MS.toLong())
			animatable.animateTo(
				targetValue = dailyValues[index].coerceIn(0f, 1f),
				animationSpec = spring(
					dampingRatio = Spring.DampingRatioMediumBouncy,
					stiffness = Spring.StiffnessLow,
				),
			)
		}
	}

	val semanticDesc = buildString {
		append("Weekly activity: ")
		labels.forEachIndexed { i, label ->
			append("$label ${(dailyValues[i] * 100).toInt()}%")
			if (i < labels.size - 1) append(", ")
		}
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.semantics { contentDescription = semanticDesc },
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.defaultMinSize(minHeight = 80.dp),
		) {
			val totalWidth = size.width
			val chartHeight = size.height
			val barSpacing = totalWidth / (BAR_COUNT * 2f)
			val barWidth = totalWidth / (BAR_COUNT * 2f)
			val cornerRadiusPx = 4.dp.toPx()

			for (i in 0 until BAR_COUNT) {
				val animatedValue = barAnimations[i].value
					.coerceAtLeast(MIN_BAR_FRACTION)
				val barHeight = animatedValue * chartHeight
				val x = i * (barWidth + barSpacing) + barSpacing / 2f
				val color = if (i == highlightIndex) primaryColor else surfaceVariantColor

				drawRoundRect(
					color = color,
					topLeft = Offset(x, chartHeight - barHeight),
					size = Size(barWidth, barHeight),
					cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
				)
			}
		}

		// Day labels
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceEvenly,
		) {
			labels.forEachIndexed { index, label ->
				Text(
					text = label,
					style = labelStyle,
					color = if (index == highlightIndex) {
						primaryColor
					} else {
						onSurfaceVariant.copy(alpha = 0.6f)
					},
					textAlign = TextAlign.Center,
				)
			}
		}
	}
}

// ─── Previews ────────────────────────────────────────────────────────

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WeeklyTrendBarsPreview() {
	AppTheme {
		WeeklyTrendBars(
			dailyValues = listOf(0.2f, 0.5f, 0.8f, 0.6f, 0.3f, 0.9f, 0.0f),
			labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
			highlightIndex = 5,
		)
	}
}
