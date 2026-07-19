package com.adsamcik.tracker.game.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.TerritoryRelativeCell
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineDurations
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineMotion
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import com.adsamcik.tracker.shared.utils.style.compose.tweenExpressive
import kotlin.math.min

/**
 * Territory's live visualization: an abstract 7x7 grid of session-origin-
 * relative cells. No raw coordinates or global cell identifiers ever reach
 * this composable — only [MiniGameVisualPayload.Territory]'s relative cells.
 */
@Composable
internal fun TerritoryGrid(
	snapshot: MiniGameSnapshot,
	modifier: Modifier = Modifier,
) {
	val payload = snapshot.visualPayload as MiniGameVisualPayload.Territory
	// Claims outside the privacy-safe local window still count toward score, but
	// are intentionally absent here because the engine publishes no global cell key.
	val reducedMotion = LocalReducedMotion.current
	val frontierCells = remember(payload.claimedCells) {
		territoryFrontierCells(payload.claimedCells)
	}
	val trailRecency = remember(payload.recentTrail) {
		buildMap {
			payload.recentTrail.forEachIndexed { index, cell ->
				put(cell, (index + 1f) / payload.recentTrail.size.coerceAtLeast(1))
			}
		}
	}

	val claimProgress = remember { Animatable(1f) }
	var claimedBurstCell by remember { mutableStateOf<TerritoryRelativeCell?>(null) }
	var previousClaimedCells by remember { mutableStateOf(payload.claimedCells) }
	LaunchedEffect(payload.claimedCells, reducedMotion) {
		if (reducedMotion) {
			claimProgress.snapTo(1f)
		}
		val newClaim = territoryNewlyClaimedCell(
			previousClaimedCells = previousClaimedCells,
			claimedCells = payload.claimedCells,
			currentCell = payload.currentCell,
		)
		previousClaimedCells = payload.claimedCells
		if (newClaim == null || reducedMotion) return@LaunchedEffect

		claimedBurstCell = newClaim
		claimProgress.snapTo(0f)
		claimProgress.animateTo(1f, RidgelineMotion.Crest)
	}

	val goalReached = (snapshot.goalProgress as? MiniGameGoalProgress.Tracked)?.isReached == true
	val goalProgress = remember { Animatable(if (goalReached) 1f else 0f) }
	var wasGoalReached by remember { mutableStateOf(goalReached) }
	LaunchedEffect(goalReached, reducedMotion) {
		if (goalReached && !wasGoalReached) {
			if (reducedMotion) {
				goalProgress.snapTo(1f)
			} else {
				goalProgress.snapTo(0f)
				goalProgress.animateTo(1f, tweenExpressive())
			}
		} else {
			goalProgress.snapTo(if (goalReached) 1f else 0f)
		}
		wasGoalReached = goalReached
	}

	val currentPulse = if (reducedMotion || payload.currentCell == null) {
		0.5f
	} else {
		val transition = rememberInfiniteTransition(label = "territory-current")
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
			label = "territory-current-pulse",
		)
		pulse
	}

	val currentStatus = stringResource(
		if (payload.currentCell == null) {
			R.string.minigame_territory_current_waiting
		} else {
			R.string.minigame_territory_current_highlighted
		},
	)
	val goalStatus = stringResource(
		if (goalReached) {
			R.string.minigame_territory_goal_complete
		} else {
			R.string.minigame_territory_goal_in_progress
		},
	)
	val description = stringResource(
		R.string.minigame_territory_grid_description_detailed,
		payload.claimedCells.size,
		frontierCells.size,
		currentStatus,
		goalStatus,
	)

	val colors = TerritoryColors(
		background = MaterialTheme.colorScheme.surfaceVariant,
		empty = MaterialTheme.colorScheme.surface,
		frontier = MaterialTheme.colorScheme.primary,
		claimed = MaterialTheme.colorScheme.primary,
		claimedHighlight = MaterialTheme.colorScheme.primaryContainer,
		trail = MaterialTheme.colorScheme.secondary,
		current = MaterialTheme.colorScheme.onPrimary,
		celebration = MaterialTheme.colorScheme.tertiary,
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) { contentDescription = description },
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Sm),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(1f)
				.clearAndSetSemantics { },
		) {
			drawTerritory(
				claimedCells = payload.claimedCells,
				currentCell = payload.currentCell,
				frontierCells = frontierCells,
				trailRecency = trailRecency,
				claimedBurstCell = claimedBurstCell,
				claimProgress = claimProgress.value,
				currentPulse = currentPulse,
				goalReached = goalReached,
				goalProgress = goalProgress.value,
				colors = colors,
			)
		}
		Text(
			text = stringResource(
				R.string.minigame_territory_legend,
				frontierCells.size,
			),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

internal fun territoryFrontierCells(
	claimedCells: Set<TerritoryRelativeCell>,
): Set<TerritoryRelativeCell> = buildSet {
	claimedCells.forEach { cell ->
		CARDINAL_NEIGHBORS.forEach { (rowDelta, columnDelta) ->
			val row = cell.rowOffset + rowDelta
			val column = cell.columnOffset + columnDelta
			if (row in GRID_MIN..GRID_MAX && column in GRID_MIN..GRID_MAX) {
				val neighbor = TerritoryRelativeCell(row, column)
				if (neighbor !in claimedCells) add(neighbor)
			}
		}
	}
}

internal fun territoryNewlyClaimedCell(
	previousClaimedCells: Set<TerritoryRelativeCell>,
	claimedCells: Set<TerritoryRelativeCell>,
	currentCell: TerritoryRelativeCell?,
): TerritoryRelativeCell? {
	val newClaims = claimedCells - previousClaimedCells
	return currentCell?.takeIf { it in newClaims } ?: newClaims.lastOrNull()
}

private fun DrawScope.drawTerritory(
	claimedCells: Set<TerritoryRelativeCell>,
	currentCell: TerritoryRelativeCell?,
	frontierCells: Set<TerritoryRelativeCell>,
	trailRecency: Map<TerritoryRelativeCell, Float>,
	claimedBurstCell: TerritoryRelativeCell?,
	claimProgress: Float,
	currentPulse: Float,
	goalReached: Boolean,
	goalProgress: Float,
	colors: TerritoryColors,
) {
	val gap = TERRITORY_CELL_GAP.toPx()
	val inset = TERRITORY_GRID_INSET.toPx()
	val usableGridSize = (
		min(size.width, size.height) -
			inset * 2f -
			gap * (GRID_DIMENSION - 1)
		).coerceAtLeast(0f)
	val cellSize = usableGridSize / GRID_DIMENSION
	val actualGridSize = cellSize * GRID_DIMENSION + gap * (GRID_DIMENSION - 1)
	val gridOrigin = Offset(
		x = (size.width - actualGridSize) / 2f,
		y = (size.height - actualGridSize) / 2f,
	)
	val cellCorner = CornerRadius(cellSize * 0.28f)

	drawRoundRect(
		color = colors.background.copy(alpha = 0.32f),
		topLeft = gridOrigin - Offset(inset / 2f, inset / 2f),
		size = Size(actualGridSize + inset, actualGridSize + inset),
		cornerRadius = CornerRadius(cellSize * 0.48f),
	)

	// Bridges are underpainted beneath translucent claimed tiles so adjacent
	// cells read as one territory; keep this relationship if tile opacity changes.
	for (row in GRID_MIN..GRID_MAX) {
		for (column in GRID_MIN..GRID_MAX) {
			val cell = TerritoryRelativeCell(row, column)
			if (cell !in claimedCells) continue
			val rect = territoryCellRect(cell, gridOrigin, cellSize, gap)
			if (
				column < GRID_MAX &&
				TerritoryRelativeCell(row, column + 1) in claimedCells
			) {
				drawRect(
					color = colors.claimedHighlight.copy(alpha = 0.82f),
					topLeft = Offset(rect.center.x, rect.top + cellSize * 0.2f),
					size = Size(cellSize + gap, cellSize * 0.6f),
				)
			}
			if (
				row > GRID_MIN &&
				TerritoryRelativeCell(row - 1, column) in claimedCells
			) {
				drawRect(
					color = colors.claimedHighlight.copy(alpha = 0.82f),
					topLeft = Offset(rect.left + cellSize * 0.2f, rect.center.y),
					size = Size(cellSize * 0.6f, cellSize + gap),
				)
			}
		}
	}

	for (row in GRID_MAX downTo GRID_MIN) {
		for (column in GRID_MIN..GRID_MAX) {
			val cell = TerritoryRelativeCell(row, column)
			val rect = territoryCellRect(cell, gridOrigin, cellSize, gap)
			when {
				cell in claimedCells -> {
					val burstScale = if (cell == claimedBurstCell) {
						0.74f + claimProgress * 0.26f
					} else {
						1f
					}
					val claimedRect = rect.scaleFromCenter(burstScale.coerceAtLeast(0.72f))
					drawRoundRect(
						color = colors.claimed.copy(alpha = 0.76f),
						topLeft = claimedRect.topLeft,
						size = claimedRect.size,
						cornerRadius = cellCorner,
					)
					drawRoundRect(
						color = colors.claimedHighlight.copy(alpha = 0.34f),
						topLeft = claimedRect.topLeft + Offset(cellSize * 0.08f, cellSize * 0.08f),
						size = Size(claimedRect.width * 0.58f, claimedRect.height * 0.34f),
						cornerRadius = CornerRadius(cellSize * 0.16f),
					)
					trailRecency[cell]?.let { recency ->
						drawRoundRect(
							color = colors.trail.copy(alpha = 0.12f + recency * 0.38f),
							topLeft = claimedRect.topLeft,
							size = claimedRect.size,
							cornerRadius = cellCorner,
							style = Stroke(width = cellSize * (0.035f + recency * 0.035f)),
						)
					}
				}

				cell in frontierCells -> {
					drawRoundRect(
						color = colors.empty.copy(alpha = 0.72f),
						topLeft = rect.topLeft,
						size = rect.size,
						cornerRadius = cellCorner,
					)
					drawRoundRect(
						color = colors.frontier.copy(alpha = 0.42f),
						topLeft = rect.topLeft,
						size = rect.size,
						cornerRadius = cellCorner,
						style = Stroke(width = cellSize * 0.045f),
					)
					drawCircle(
						color = colors.frontier.copy(alpha = 0.42f),
						radius = cellSize * 0.065f,
						center = rect.center,
					)
				}

				else -> drawRoundRect(
					color = colors.empty.copy(alpha = 0.42f),
					topLeft = rect.topLeft,
					size = rect.size,
					cornerRadius = cellCorner,
				)
			}
		}
	}

	currentCell?.let { cell ->
		val rect = territoryCellRect(cell, gridOrigin, cellSize, gap)
		val pulse = currentPulse.coerceIn(0f, 1f)
		drawCircle(
			color = colors.claimed.copy(alpha = 0.1f + pulse * 0.12f),
			radius = cellSize * (0.54f + pulse * 0.09f),
			center = rect.center,
		)
		drawRoundRect(
			color = colors.current.copy(alpha = 0.88f),
			topLeft = rect.topLeft + Offset(cellSize * 0.1f, cellSize * 0.1f),
			size = Size(cellSize * 0.8f, cellSize * 0.8f),
			cornerRadius = CornerRadius(cellSize * 0.23f),
			style = Stroke(width = cellSize * (0.055f + pulse * 0.018f)),
		)
		drawCircle(
			color = colors.current,
			radius = cellSize * (0.1f + pulse * 0.015f),
			center = rect.center,
		)
	}

	if (claimedBurstCell != null && claimProgress < CLAIM_ANIMATION_END) {
		val progress = claimProgress.coerceIn(0f, 1f)
		val rect = territoryCellRect(claimedBurstCell, gridOrigin, cellSize, gap)
		drawCircle(
			color = colors.celebration.copy(alpha = (1f - progress) * 0.62f),
			radius = cellSize * (0.3f + progress * 0.62f),
			center = rect.center,
			style = Stroke(width = cellSize * (0.12f - progress * 0.07f)),
		)
	}

	if (goalReached) {
		drawRoundRect(
			color = colors.celebration.copy(alpha = 0.9f),
			topLeft = gridOrigin - Offset(gap, gap),
			size = Size(actualGridSize + gap * 2f, actualGridSize + gap * 2f),
			cornerRadius = CornerRadius(cellSize * 0.5f),
			style = Stroke(width = cellSize * 0.07f),
		)
		val progress = goalProgress.coerceIn(0f, 1f)
		if (progress < GOAL_ANIMATION_END) {
			drawCircle(
				color = colors.celebration.copy(alpha = (1f - progress) * 0.7f),
				radius = actualGridSize * (0.14f + progress * 0.7f),
				center = Offset(size.width / 2f, size.height / 2f),
				style = Stroke(width = cellSize * (0.16f - progress * 0.1f)),
			)
			drawRoundRect(
				color = colors.celebration.copy(alpha = (1f - progress) * 0.12f),
				topLeft = gridOrigin,
				size = Size(actualGridSize, actualGridSize),
				cornerRadius = CornerRadius(cellSize * 0.4f),
			)
		}
	}
}

private fun territoryCellRect(
	cell: TerritoryRelativeCell,
	gridOrigin: Offset,
	cellSize: Float,
	gap: Float,
): Rect {
	val rowIndex = GRID_MAX - cell.rowOffset
	val columnIndex = cell.columnOffset - GRID_MIN
	val topLeft = Offset(
		x = gridOrigin.x + columnIndex * (cellSize + gap),
		y = gridOrigin.y + rowIndex * (cellSize + gap),
	)
	return Rect(topLeft, Size(cellSize, cellSize))
}

private fun Rect.scaleFromCenter(scale: Float): Rect {
	val scaledSize = Size(width * scale, height * scale)
	return Rect(
		center - Offset(scaledSize.width / 2f, scaledSize.height / 2f),
		scaledSize,
	)
}

private data class TerritoryColors(
	val background: Color,
	val empty: Color,
	val frontier: Color,
	val claimed: Color,
	val claimedHighlight: Color,
	val trail: Color,
	val current: Color,
	val celebration: Color,
)

private val CARDINAL_NEIGHBORS = listOf(
	1 to 0,
	-1 to 0,
	0 to 1,
	0 to -1,
)

private val TERRITORY_CELL_GAP = 4.dp
private val TERRITORY_GRID_INSET = 8.dp
private const val GRID_MIN = -3
private const val GRID_MAX = 3
private const val GRID_DIMENSION = 7
private const val CLAIM_ANIMATION_END = 0.999f
private const val GOAL_ANIMATION_END = 0.999f
