package com.adsamcik.tracker.game.ui.compose

import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * PB / score unit-mapping contract: each game's raw score maps to its own unit,
 * never a generic point value.
 */
class MiniGameScoreDisplayTest {

	@Test
	fun outrunMapsToWholeMeters() {
		miniGameScoreDisplay(MiniGameScoreUnit.DISTANCE_METERS, 87.4) shouldBe
			MiniGameScoreDisplay.Meters(87)
		miniGameScoreDisplay(MiniGameScoreUnit.DISTANCE_METERS, 87.6) shouldBe
			MiniGameScoreDisplay.Meters(88)
	}

	@Test
	fun territoryMapsToCells() {
		miniGameScoreDisplay(MiniGameScoreUnit.CELL_COUNT, 12.0) shouldBe
			MiniGameScoreDisplay.Cells(12)
	}

	@Test
	fun zenWalkMapsToFormattedDuration() {
		val display = miniGameScoreDisplay(MiniGameScoreUnit.DURATION_SECONDS, 510.0)
		display shouldBe MiniGameScoreDisplay.Duration(510L, "8:30")
	}

	@Test
	fun fuseRunMapsToDefusedCharges() {
		miniGameScoreDisplay(MiniGameScoreUnit.DEFUSAL_COUNT, 5.0) shouldBe
			MiniGameScoreDisplay.Charges(5)
	}

	@Test
	fun switchbackMapsToTurns() {
		miniGameScoreDisplay(MiniGameScoreUnit.TURN_COUNT, 6.0) shouldBe
			MiniGameScoreDisplay.Turns(6)
	}

	@Test
	fun unknownGameFallsBackToRaw() {
		miniGameScoreDisplay(null, 10.0) shouldBe MiniGameScoreDisplay.Raw(10L)
	}

	@Test
	fun negativeOrNonFiniteScoreClampsToZero() {
		miniGameScoreDisplay(MiniGameScoreUnit.DISTANCE_METERS, -5.0) shouldBe
			MiniGameScoreDisplay.Meters(0)
		miniGameScoreDisplay(MiniGameScoreUnit.DURATION_SECONDS, Double.NaN) shouldBe
			MiniGameScoreDisplay.Duration(0L, "0:00")
	}

	@Test
	fun durationFormattingPadsSeconds() {
		formatDurationSeconds(0L) shouldBe "0:00"
		formatDurationSeconds(5L) shouldBe "0:05"
		formatDurationSeconds(65L) shouldBe "1:05"
		formatDurationSeconds(600L) shouldBe "10:00"
	}
}
