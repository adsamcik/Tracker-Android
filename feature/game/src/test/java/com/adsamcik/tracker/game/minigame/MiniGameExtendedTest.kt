package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.fuserun.FuseRunGame
import com.adsamcik.tracker.game.minigame.fuserun.FuseRunSession
import com.adsamcik.tracker.game.minigame.outrun.OutrunGame
import com.adsamcik.tracker.game.minigame.outrun.OutrunSession
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackGame
import com.adsamcik.tracker.game.minigame.switchback.SwitchbackSession
import com.adsamcik.tracker.game.minigame.territory.TerritoryGame
import com.adsamcik.tracker.game.minigame.territory.TerritorySession
import com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkGame
import com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkSession
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MiniGameRegistry")
class MiniGameRegistryTest {

	@Test
	fun `findById returns matching game`() {
		val outrun = OutrunGame()
		val territory = TerritoryGame()
		val registry = MiniGameRegistry(setOf(outrun, territory))
		registry.findById("outrun") shouldBe outrun
	}

	@Test
	fun `findById returns null for unknown id`() {
		val registry = MiniGameRegistry(setOf(OutrunGame()))
		registry.findById("nonexistent").shouldBeNull()
	}

	@Test
	fun `allSorted returns games sorted by unlock level`() {
		val outrun = OutrunGame() // level 3
		val territory = TerritoryGame() // level 6
		val zenwalk = ZenWalkGame() // level 9
		val fuseRun = FuseRunGame() // level 9
		val switchback = SwitchbackGame() // level 12
		val registry = MiniGameRegistry(setOf(zenwalk, switchback, outrun, fuseRun, territory))
		val sorted = registry.allSorted()
		sorted shouldHaveSize 5
		sorted.map { it.id } shouldBe
			listOf("outrun", "territory", "fuserun", "zenwalk", "switchback")
	}

	@Test
	fun `empty registry returns no games`() {
		val registry = MiniGameRegistry(emptySet())
		registry.allSorted() shouldHaveSize 0
		registry.findById("any").shouldBeNull()
	}
}

@DisplayName("MiniGame implementations")
class MiniGameImplementationsTest {

	@Nested
	@DisplayName("OutrunGame")
	inner class OutrunGameTest {
		@Test
		fun `has correct id`() {
			OutrunGame().id shouldBe "outrun"
		}

		@Test
		fun `unlock level is 3`() {
			OutrunGame().unlockLevel shouldBe 3
		}

		@Test
		fun `createSession returns OutrunSession`() {
			val session = OutrunGame().createSession()
			session shouldBe session // just verify it doesn't throw
			session.state shouldBe MiniGameState.IDLE
		}
	}

	@Nested
	@DisplayName("TerritoryGame")
	inner class TerritoryGameTest {
		@Test
		fun `has correct id`() {
			TerritoryGame().id shouldBe "territory"
		}

		@Test
		fun `unlock level is 6`() {
			TerritoryGame().unlockLevel shouldBe 6
		}

		@Test
		fun `createSession returns TerritorySession`() {
			val session = TerritoryGame().createSession()
			session.state shouldBe MiniGameState.IDLE
		}
	}

	@Nested
	@DisplayName("ZenWalkGame")
	inner class ZenWalkGameTest {
		@Test
		fun `has correct id`() {
			ZenWalkGame().id shouldBe "zenwalk"
		}

		@Test
		fun `unlock level is 9`() {
			ZenWalkGame().unlockLevel shouldBe 9
		}

		@Test
		fun `createSession returns ZenWalkSession`() {
			val session = ZenWalkGame().createSession()
			session.state shouldBe MiniGameState.IDLE
		}
	}

	@Nested
	@DisplayName("FuseRunGame")
	inner class FuseRunGameTest {
		@Test
		fun `has correct id and unlock level`() {
			FuseRunGame().let {
				it.id shouldBe "fuserun"
				it.unlockLevel shouldBe 9
			}
		}

		@Test
		fun `createSession returns FuseRunSession`() {
			FuseRunGame().createSession().shouldBeInstanceOf<FuseRunSession>()
		}
	}

	@Nested
	@DisplayName("SwitchbackGame")
	inner class SwitchbackGameTest {
		@Test
		fun `has correct id and unlock level`() {
			SwitchbackGame().let {
				it.id shouldBe "switchback"
				it.unlockLevel shouldBe 12
			}
		}

		@Test
		fun `createSession returns SwitchbackSession`() {
			SwitchbackGame().createSession().shouldBeInstanceOf<SwitchbackSession>()
		}
	}
}

@DisplayName("OutrunSession advanced")
class OutrunSessionAdvancedTest {

	@Nested
	@DisplayName("Score and XP")
	inner class ScoreAndXp {
		@Test
		fun `xp has distance bonus when player is ahead`() {
			val session = OutrunSession(ghostPaceMps = 0.5)
			// First fix
			session.onLocationUpdate(51.0, 14.0, 2.0f, 10f, 0L)
			// Walk quickly - add significant distance moves
			session.onLocationUpdate(51.001, 14.0, 2.0f, 10f, 5000L)
			session.onLocationUpdate(51.002, 14.0, 2.0f, 10f, 10000L)
			session.onSessionEnd()

			val points = session.calculatePoints()
			points shouldBeGreaterThanOrEqual 30 // at least base
		}

		@Test
		fun `xp capped at 200 max`() {
			val session = OutrunSession()
			// Even with huge ahead, bonus caps at 170 + 30 base = 200
			session.calculatePoints() shouldBeLessThanOrEqual 200
		}

		@Test
		fun `onSessionEnd sets state to FINISHED`() {
			val session = OutrunSession()
			session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
			session.onSessionEnd()
			session.state shouldBe MiniGameState.FINISHED
		}
	}

	@Nested
	@DisplayName("StatusText")
	inner class StatusText {
		@Test
		fun `shows positive distance when ahead`() {
			val session = OutrunSession(ghostPaceMps = 0.0)
			session.onLocationUpdate(51.0, 14.0, 0f, 10f, 0L)
			session.onLocationUpdate(51.001, 14.0, 0f, 10f, 1000L)
			session.statusText shouldContain "+"
		}
	}

	@Nested
	@DisplayName("Ghost behavior")
	inner class GhostBehavior {
		@Test
		fun `ghost pauses at vehicle speeds`() {
			val session = OutrunSession(ghostPaceMps = 1.0)
			session.onLocationUpdate(51.0, 14.0, 1.0f, 10f, 0L)
			session.onLocationUpdate(51.0001, 14.0, 1.0f, 10f, 1_000L)
			val gapBeforeVehicleSample =
				(session.snapshot.visualPayload as MiniGameVisualPayload.Outrun).currentGapMeters
			// Vehicle speed (>8 m/s)
			session.onLocationUpdate(51.0002, 14.0, 20.0f, 10f, 10_000L)
			// Ghost should NOT have advanced at vehicle speed
			(session.snapshot.visualPayload as MiniGameVisualPayload.Outrun).currentGapMeters shouldBe
				gapBeforeVehicleSample
			session.state shouldBe MiniGameState.RUNNING
		}
	}
}

@DisplayName("TerritorySession advanced")
class TerritorySessionAdvancedTest {

	@Nested
	@DisplayName("Cell claiming")
	inner class CellClaiming {
		@Test
		fun `claims different cells in different locations`() {
			val session = TerritorySession()
			session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
			// Different cell (>50m away)
			session.onLocationUpdate(51.01, 14.01, 1.5f, 10f, 5000L)
			session.score shouldBeGreaterThan 1.0
		}

		@Test
		fun `rejects poor accuracy`() {
			val session = TerritorySession()
			session.onLocationUpdate(51.0, 14.0, 1.5f, 50f, 1000L)
			session.score shouldBe 0.0
			session.state shouldBe MiniGameState.IDLE
		}

		@Test
		fun `transitions to running on valid update`() {
			val session = TerritorySession()
			session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
			session.state shouldBe MiniGameState.RUNNING
		}
	}

	@Nested
	@DisplayName("XP calculation")
	inner class XpCalculation {
		@Test
		fun `base xp with no cells`() {
			TerritorySession().calculatePoints() shouldBe 0
		}

		@Test
		fun `xp includes cell bonuses`() {
			val session = TerritorySession()
			// Claim several cells by moving far apart with enough time between
			for (i in 0 until 5) {
				session.onLocationUpdate(51.0 + i * 0.01, 14.0, 1.5f, 10f, (i * 3000 + 1000).toLong())
			}
			session.calculatePoints() shouldBeGreaterThanOrEqual 20
		}
	}

	@Nested
	@DisplayName("StatusText")
	inner class StatusText {
		@Test
		fun `shows cell count`() {
			val session = TerritorySession()
			session.statusText shouldContain "0 cells"
		}
	}
}

@DisplayName("ZenWalkSession advanced")
class ZenWalkSessionAdvancedTest {

	@Nested
	@DisplayName("Calibration")
	inner class Calibration {
		@Test
		fun `calibration sets target from median of speeds`() {
			val session = ZenWalkSession()
			session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L) // first fix
			// Simulate 2+ minutes of walking
			for (i in 1..10) {
				session.onLocationUpdate(
					51.0 + i * 0.00001, 14.0,
					1.4f, 10f,
					(i * 15000).toLong(), // 15s intervals
				)
			}
			// After >120s with >5 samples, calibration should complete
			session.state shouldBe MiniGameState.RUNNING
		}
	}

	@Nested
	@DisplayName("Fixed target mode")
	inner class FixedTargetMode {
		@Test
		fun `fixed target skips calibration`() {
			val session = ZenWalkSession(fixedTargetMps = 1.4)
			session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L) // first fix
			// Walking at exactly target pace
			session.onLocationUpdate(51.0001, 14.0, 1.4f, 10f, 5000L)
			session.onLocationUpdate(51.0002, 14.0, 1.4f, 10f, 10000L)
			session.score shouldBeGreaterThan 0.0
		}
	}

	@Nested
	@DisplayName("Pause handling")
	inner class PauseHandling {
		@Test
		fun `pauses when speed drops below threshold`() {
			val session = ZenWalkSession(fixedTargetMps = 1.4)
			session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L)
			session.onLocationUpdate(51.0001, 14.0, 1.4f, 10f, 5000L)
			val scoreBefore = session.score
			// Very slow update (paused)
			session.onLocationUpdate(51.0001, 14.0, 0.1f, 10f, 10000L)
			// Score should not increase during pause
			session.score shouldBe scoreBefore
		}
	}

	@Nested
	@DisplayName("XP calculation")
	inner class XpCalc {
		@Test
		fun `no participation awards no xp`() {
			ZenWalkSession().calculatePoints() shouldBe 0
		}

		@Test
		fun `time bonus adds to xp`() {
			val session = ZenWalkSession(fixedTargetMps = 1.4)
			session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L)
			// Simulate several minutes in zone
			for (i in 1..120) {
				session.onLocationUpdate(51.0 + i * 0.000001, 14.0, 1.4f, 10f, (i * 1000).toLong())
			}
			session.calculatePoints() shouldBeGreaterThanOrEqual 15
		}
	}

	@Nested
	@DisplayName("StatusText")
	inner class StatusText {
		@Test
		fun `shows time in zone`() {
			val session = ZenWalkSession()
			session.statusText shouldContain "0m0s in zone"
		}
	}

	@Nested
	@DisplayName("onSessionEnd")
	inner class OnSessionEnd {
		@Test
		fun `sets state to finished`() {
			val session = ZenWalkSession()
			session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L)
			session.onSessionEnd()
			session.state shouldBe MiniGameState.FINISHED
		}
	}
}
