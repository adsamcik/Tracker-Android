package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.TerritoryRelativeCell
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for Territory's live visualization only. Owned exclusively by the
 * Territory work — no other game's tests live here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerritoryVisualizationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun territoryGrid_describesClaimedCellCountWithoutCoordinates() {
		val cells = setOf(
			TerritoryRelativeCell(0, 0),
			TerritoryRelativeCell(0, 1),
			TerritoryRelativeCell(1, 0),
		)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TerritoryGrid(
					snapshot = territorySnapshot(
						MiniGameVisualPayload.Territory(
							claimedCells = cells,
							currentCell = TerritoryRelativeCell(0, 0),
							recentTrail = listOf(TerritoryRelativeCell(1, 0)),
						),
					),
				)
			}
		}

		composeRule.onNodeWithContentDescription("3 nearby areas claimed", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun territoryGrid_describesFrontierCurrentAreaAndGoalWithoutCoordinates() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TerritoryGrid(
					snapshot = territorySnapshot(
						payload = MiniGameVisualPayload.Territory(
							claimedCells = setOf(TerritoryRelativeCell(0, 0)),
							currentCell = TerritoryRelativeCell(0, 0),
							recentTrail = emptyList(),
						),
						goalProgress = MiniGameGoalProgress.Tracked(1.0, 1.0),
					),
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			"4 frontier areas highlighted. Current area highlighted. Goal complete.",
			substring = true,
		).assertIsDisplayed()
	}

	@Test
	fun territoryFrontierCells_areCardinalUnclaimedNeighborsInsideLocalGrid() {
		val claimed = setOf(
			TerritoryRelativeCell(0, 0),
			TerritoryRelativeCell(0, 1),
		)

		assertEquals(
			setOf(
				TerritoryRelativeCell(1, 0),
				TerritoryRelativeCell(-1, 0),
				TerritoryRelativeCell(1, 1),
				TerritoryRelativeCell(-1, 1),
				TerritoryRelativeCell(0, -1),
				TerritoryRelativeCell(0, 2),
			),
			territoryFrontierCells(claimed),
		)
	}

	@Test
	fun territoryFrontierCells_clipAtPrivacySafeGridEdge() {
		assertEquals(
			setOf(
				TerritoryRelativeCell(2, 3),
				TerritoryRelativeCell(3, 2),
			),
			territoryFrontierCells(setOf(TerritoryRelativeCell(3, 3))),
		)
	}

	@Test
	fun territoryNewlyClaimedCell_detectsClaimWhenLargerFeedbackSupersedesClaimCue() {
		val previous = setOf(TerritoryRelativeCell(0, 0))
		val completedGoalCell = TerritoryRelativeCell(0, 1)

		assertEquals(
			completedGoalCell,
			territoryNewlyClaimedCell(
				previousClaimedCells = previous,
				claimedCells = previous + completedGoalCell,
				currentCell = completedGoalCell,
			),
		)
	}

	@Test
	fun territoryNewlyClaimedCell_doesNotReplayUnchangedClaimSet() {
		val claimed = setOf(TerritoryRelativeCell(0, 0))

		assertEquals(
			null,
			territoryNewlyClaimedCell(
				previousClaimedCells = claimed,
				claimedCells = claimed,
				currentCell = TerritoryRelativeCell(0, 0),
			),
		)
	}

	private fun territorySnapshot(
		payload: MiniGameVisualPayload.Territory,
		phase: MiniGamePhase = MiniGamePhase.ACTIVE,
		goalProgress: MiniGameGoalProgress = MiniGameGoalProgress.NotConfigured,
	): MiniGameSnapshot = MiniGameSnapshot(
		phase = phase,
		signal = MiniGameSignal(MiniGameSignalQuality.GOOD, ageMs = 1_000L, isStale = false),
		elapsedActiveTimeMs = 0L,
		goalProgress = goalProgress,
		personalBest = MiniGamePersonalBest.compare(payload.claimedCells.size.toDouble(), scoreBeforeRun = null),
		latestFeedback = null,
		visualPayload = payload,
	)
}
