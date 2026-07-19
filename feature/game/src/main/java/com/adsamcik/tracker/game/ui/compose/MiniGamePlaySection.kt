package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.adsamcik.tracker.game.minigame.MiniGameAccentRole
import com.adsamcik.tracker.game.minigame.MiniGameIcon
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.minigame.MiniGameShapeRole
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing

/**
 * Presentation model for one distinct mini-game play card.
 *
 * Carries the game's Material/Ridgeline identity tokens (icon/accent/shape),
 * a measurable goal line, unlock gate and — when present — a personal best in
 * the game's own unit.
 */
internal data class MiniGamePlayCardUi(
	val id: String,
	val nameRes: Int,
	val goalLineRes: Int,
	val icon: MiniGameIcon,
	val accentRole: MiniGameAccentRole,
	val shapeRole: MiniGameShapeRole,
	val scoreUnit: MiniGameScoreUnit,
	val unlockLevel: Int,
	val isUnlocked: Boolean,
	val personalBest: Double?,
)

/**
 * The "Play" surface: the primary reason to open the Game screen, rendered
 * first in the hub.
 *
 * Uses a two-column grid on comfortable widths, but collapses to a single
 * column when the viewport is narrow or the user has scaled fonts up — at
 * which point two columns would truncate the goal line or personal-best badge.
 */
@Composable
internal fun MiniGamePlaySection(
	games: List<MiniGamePlayCardUi>,
	onPlay: (gameId: String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val configuration = LocalConfiguration.current
	val fontScale = LocalDensity.current.fontScale
	val singleColumn = shouldUseSingleColumn(configuration.screenWidthDp, fontScale)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = RidgelineSpacing.Lg),
		verticalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
	) {
		if (singleColumn) {
			games.forEach { game ->
				MiniGamePlayCard(
					game = game,
					onPlay = onPlay,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		} else {
			games.chunked(2).forEach { rowGames ->
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(RidgelineSpacing.Md),
				) {
					rowGames.forEach { game ->
						MiniGamePlayCard(
							game = game,
							onPlay = onPlay,
							modifier = Modifier.weight(1f),
						)
					}
					if (rowGames.size == 1) {
						Spacer(modifier = Modifier.weight(1f))
					}
				}
			}
		}
	}
}

/**
 * Pure adaptive-layout decision, extracted for testability.
 *
 * @return `true` when the play cards must stack in a single column because
 *   two columns would be unsafe for the given [screenWidthDp] / [fontScale].
 */
internal fun shouldUseSingleColumn(screenWidthDp: Int, fontScale: Float): Boolean =
	screenWidthDp < TWO_COLUMN_MIN_WIDTH_DP || fontScale > TWO_COLUMN_MAX_FONT_SCALE

private const val TWO_COLUMN_MIN_WIDTH_DP = 360
private const val TWO_COLUMN_MAX_FONT_SCALE = 1.3f
