package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGameAccentRole
import com.adsamcik.tracker.game.minigame.MiniGameIcon
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.minigame.MiniGameShapeRole
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniGamePlayCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun unlockedOutrun(personalBest: Double? = null) = MiniGamePlayCardUi(
		id = "outrun",
		nameRes = R.string.minigame_outrun_name,
		goalLineRes = R.string.minigame_outrun_goal_line,
		icon = MiniGameIcon.GHOST,
		accentRole = MiniGameAccentRole.TERTIARY,
		shapeRole = MiniGameShapeRole.MOMENTUM,
		scoreUnit = MiniGameScoreUnit.DISTANCE_METERS,
		unlockLevel = 3,
		isUnlocked = true,
		personalBest = personalBest,
	)

	private fun lockedZen() = MiniGamePlayCardUi(
		id = "zenwalk",
		nameRes = R.string.minigame_zenwalk_name,
		goalLineRes = R.string.minigame_zenwalk_goal_line,
		icon = MiniGameIcon.PACE,
		accentRole = MiniGameAccentRole.SECONDARY,
		shapeRole = MiniGameShapeRole.WAYPOINT,
		scoreUnit = MiniGameScoreUnit.DURATION_SECONDS,
		unlockLevel = 9,
		isUnlocked = false,
		personalBest = null,
	)

	@Test
	fun unlockedCard_invokesPlayAndShowsPersonalBestInMeters() {
		var playedId: String? = null
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamePlayCard(game = unlockedOutrun(personalBest = 87.0), onPlay = { playedId = it })
			}
		}

		// Personal best rendered in the game's own unit (meters), not "pts".
		composeRule.onNodeWithText("PB 87 m", useUnmergedTree = true).assertIsDisplayed()

		composeRule.onNodeWithTag("minigame_play_card_outrun")
			.assertHasClickAction()
			.performClick()

		assert(playedId == "outrun")
	}

	@Test
	fun lockedCard_showsFullOpacityUnlockTextAndIsReadable() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamePlayCard(game = lockedZen(), onPlay = {})
			}
		}

		// Full-opacity, laid-out unlock requirement (never dimmed away).
		composeRule.onNodeWithText("Reach Level 9 to unlock", useUnmergedTree = true)
			.assertIsDisplayed()

		// Collapsed: TalkBack announces the requirement + how to expand.
		composeRule.onNodeWithTag("minigame_play_card_zenwalk")
			.assertHasClickAction()
			.assertContentDescriptionContains("Reach Level 9 to unlock", substring = true)
		composeRule.onNodeWithTag("minigame_play_card_zenwalk")
			.assertContentDescriptionContains("Double tap to see how to unlock", substring = true)
	}

	@Test
	fun lockedCard_tapExpandsUnlockDetailInlineWithoutToast() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MiniGamePlayCard(game = lockedZen(), onPlay = {})
			}
		}

		// A tap toggles the inline expansion (no Toast); the accessible
		// description now carries the full "how to unlock" detail.
		composeRule.onNodeWithTag("minigame_play_card_zenwalk").performClick()

		composeRule.onNodeWithTag("minigame_play_card_zenwalk")
			.assertContentDescriptionContains(
				"Earn XP by playing unlocked games",
				substring = true,
			)
	}
}
