package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameFeedback
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackEventId
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBest
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignal
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import io.kotest.matchers.shouldBe
import org.junit.Test

class GameSessionHapticGateTest {

	@Test
	fun `persisted opt out and duplicate event ids suppress feedback`() {
		val clock = MutableClock()
		val performer = RecordingHapticPerformer()
		val gate = GameSessionHapticGate(clock, performer)
		gate.updateSettings(GoalsSettingsState.defaults().copy(gameHapticsEnabled = false))

		gate.onState(activeState(1L, MiniGameFeedbackCue.GoalReached))
		performer.cues shouldBe emptyList()

		gate.updateSettings(GoalsSettingsState.defaults().copy(gameHapticsEnabled = true))
		gate.onState(activeState(1L, MiniGameFeedbackCue.GoalReached))
		performer.cues shouldBe emptyList()
		gate.onState(activeState(2L, MiniGameFeedbackCue.GoalReached))
		performer.cues shouldBe listOf(MiniGameFeedbackCue.GoalReached)
	}

	@Test
	fun `global debounce suppresses low priority cues but goal and personal best win`() {
		val clock = MutableClock()
		val performer = RecordingHapticPerformer()
		val gate = GameSessionHapticGate(clock, performer)
		gate.updateSettings(GoalsSettingsState.defaults())

		gate.onState(activeState(1L, MiniGameFeedbackCue.TerritoryCellClaimed))
		clock.elapsedMs = 100L
		gate.onState(activeState(2L, MiniGameFeedbackCue.OutrunDanger))
		gate.onState(activeState(3L, MiniGameFeedbackCue.PersonalBestCrossed))
		gate.onState(activeState(4L, MiniGameFeedbackCue.GoalReached))

		performer.cues shouldBe listOf(
			MiniGameFeedbackCue.TerritoryCellClaimed,
			MiniGameFeedbackCue.PersonalBestCrossed,
			MiniGameFeedbackCue.GoalReached,
		)
	}

	@Test
	fun `quiet coaching and long zen suppression affect only optional cues`() {
		val clock = MutableClock()
		val performer = RecordingHapticPerformer()
		val gate = GameSessionHapticGate(clock, performer)
		gate.updateSettings(GoalsSettingsState.defaults().copy(quietCoachingEnabled = false))

		gate.onState(activeState(1L, MiniGameFeedbackCue.ZenZoneEntered))
		gate.onState(activeState(2L, MiniGameFeedbackCue.GoalReached))
		performer.cues shouldBe listOf(MiniGameFeedbackCue.GoalReached)

		gate.updateSettings(GoalsSettingsState.defaults())
		clock.elapsedMs = 2_000L
		gate.onState(activeState(3L, MiniGameFeedbackCue.ZenZoneEntered))
		clock.elapsedMs = 10_000L
		gate.onState(activeState(4L, MiniGameFeedbackCue.ZenZoneExited))
		clock.elapsedMs = 32_000L
		gate.onState(activeState(5L, MiniGameFeedbackCue.ZenZoneExited))

		performer.cues shouldBe listOf(
			MiniGameFeedbackCue.GoalReached,
			MiniGameFeedbackCue.ZenZoneEntered,
			MiniGameFeedbackCue.ZenZoneExited,
		)
	}

	@Test
	fun `new game coaching cues respect quiet coaching`() {
		val clock = MutableClock()
		val performer = RecordingHapticPerformer()
		val gate = GameSessionHapticGate(clock, performer)
		gate.updateSettings(GoalsSettingsState.defaults().copy(quietCoachingEnabled = false))

		gate.onState(activeState(1L, MiniGameFeedbackCue.FuseCritical))
		gate.onState(activeState(2L, MiniGameFeedbackCue.FuseDefused))
		gate.onState(activeState(3L, MiniGameFeedbackCue.SwitchbackTurnCarved))
		gate.onState(activeState(4L, MiniGameFeedbackCue.GoalReached))

		performer.cues shouldBe listOf(MiniGameFeedbackCue.GoalReached)
	}

	private fun activeState(eventId: Long, cue: MiniGameFeedbackCue): GameSessionState.Active =
		GameSessionState.Active(
			sessionId = GameSessionId("session-1"),
			configuration = MiniGameConfigurations.DEFAULT_TERRITORY,
			snapshot = MiniGameSnapshot(
				phase = MiniGamePhase.ACTIVE,
				signal = MiniGameSignal.UNKNOWN,
				elapsedActiveTimeMs = 0L,
				goalProgress = MiniGameGoalProgress.Tracked(1.0, 5.0),
				personalBest = MiniGamePersonalBest.compare(1.0, null),
				latestFeedback = MiniGameFeedback(MiniGameFeedbackEventId(eventId), cue),
				visualPayload = MiniGameVisualPayload.Pending,
			),
		)

	private class MutableClock : GameSessionClock {
		var elapsedMs = 0L
		override fun elapsedRealtimeMs(): Long = elapsedMs
		override fun currentTimeMillis(): Long = 0L
	}

	private class RecordingHapticPerformer : GameSessionHapticPerformer {
		val cues = mutableListOf<MiniGameFeedbackCue>()
		override fun perform(cue: MiniGameFeedbackCue) {
			cues += cue
		}
	}
}
