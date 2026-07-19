package com.adsamcik.tracker.game.session

import android.content.Context
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.minigame.MiniGameFeedback
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameSnapshot
import com.adsamcik.tracker.game.ui.HapticFeedback

internal fun interface GameSessionHapticPerformer {
	fun perform(cue: MiniGameFeedbackCue)
}

internal class DefaultGameSessionHapticPerformer(
	context: Context,
) : GameSessionHapticPerformer {
	private val applicationContext = context.applicationContext

	override fun perform(cue: MiniGameFeedbackCue) {
		when (cue) {
			MiniGameFeedbackCue.OutrunDanger -> HapticFeedback.streakBroken(applicationContext)
			MiniGameFeedbackCue.PersonalBestCrossed -> HapticFeedback.recordBroken(applicationContext)
			MiniGameFeedbackCue.TerritoryCellClaimed -> HapticFeedback.tapConfirm(applicationContext)
			MiniGameFeedbackCue.FuseCritical -> HapticFeedback.streakBroken(applicationContext)
			MiniGameFeedbackCue.FuseDefused,
			MiniGameFeedbackCue.SwitchbackTurnCarved,
			-> HapticFeedback.tapConfirm(applicationContext)
			MiniGameFeedbackCue.GoalReached -> HapticFeedback.achievementComplete(applicationContext)
			MiniGameFeedbackCue.ZenZoneEntered,
			MiniGameFeedbackCue.ZenZoneExited,
			-> HapticFeedback.tapConfirm(applicationContext)
			is MiniGameFeedbackCue.SessionCompleted ->
				HapticFeedback.progressMilestone(applicationContext)
		}
	}
}

/**
 * Service-side edge gate. Settings start disabled until the persisted state is
 * observed, so an opt-out can never race service startup.
 */
internal class GameSessionHapticGate(
	private val clock: GameSessionClock,
	private val performer: GameSessionHapticPerformer,
) {
	private var settings: GoalsSettingsState? = null
	private var sessionId: GameSessionId? = null
	private var lastHandledEventId: Long = 0L
	private var lastPerformedAtMs: Long? = null
	private var lastPerformedPriority: Int = Int.MIN_VALUE
	private var lastZenCueAtMs: Long? = null

	fun updateSettings(settings: GoalsSettingsState) {
		this.settings = settings
	}

	fun onState(state: GameSessionState) {
		val stateSessionId: GameSessionId
		val snapshot: MiniGameSnapshot
		when (state) {
			is GameSessionState.Active -> {
				stateSessionId = state.sessionId
				snapshot = state.snapshot
			}
			is GameSessionState.Paused -> {
				stateSessionId = state.sessionId
				snapshot = state.snapshot
			}
			is GameSessionState.Finishing -> {
				stateSessionId = state.sessionId
				snapshot = state.snapshot
			}
			else -> return
		}
		if (sessionId != stateSessionId) {
			sessionId = stateSessionId
			lastHandledEventId = 0L
		}
		handle(snapshot.latestFeedback ?: return)
	}

	private fun handle(feedback: MiniGameFeedback) {
		val eventId = feedback.eventId.value
		if (eventId <= lastHandledEventId) return
		lastHandledEventId = eventId

		val currentSettings = settings ?: return
		if (!currentSettings.gameHapticsEnabled) return
		if (!currentSettings.quietCoachingEnabled && feedback.cue.isOptionalCoachingCue()) return

		val now = clock.elapsedRealtimeMs()
		if (feedback.cue.isZenCue()) {
			val previousZen = lastZenCueAtMs
			if (previousZen != null && now - previousZen < ZEN_SUPPRESSION_MS) return
		}

		val priority = feedback.cue.priority()
		val previousAt = lastPerformedAtMs
		if (
			previousAt != null &&
			now - previousAt < GLOBAL_DEBOUNCE_MS &&
			priority <= lastPerformedPriority
		) {
			return
		}

		performer.perform(feedback.cue)
		lastPerformedAtMs = now
		lastPerformedPriority = priority
		if (feedback.cue.isZenCue()) lastZenCueAtMs = now
	}

	private fun MiniGameFeedbackCue.priority(): Int = when (this) {
		MiniGameFeedbackCue.GoalReached -> 4
		MiniGameFeedbackCue.PersonalBestCrossed -> 3
		is MiniGameFeedbackCue.SessionCompleted -> 2
		MiniGameFeedbackCue.OutrunDanger,
		MiniGameFeedbackCue.TerritoryCellClaimed,
		MiniGameFeedbackCue.FuseCritical,
		MiniGameFeedbackCue.FuseDefused,
		MiniGameFeedbackCue.SwitchbackTurnCarved,
		MiniGameFeedbackCue.ZenZoneEntered,
		MiniGameFeedbackCue.ZenZoneExited,
		-> 1
	}

	private fun MiniGameFeedbackCue.isOptionalCoachingCue(): Boolean = when (this) {
		MiniGameFeedbackCue.OutrunDanger,
		MiniGameFeedbackCue.TerritoryCellClaimed,
		MiniGameFeedbackCue.FuseCritical,
		MiniGameFeedbackCue.FuseDefused,
		MiniGameFeedbackCue.SwitchbackTurnCarved,
		MiniGameFeedbackCue.ZenZoneEntered,
		MiniGameFeedbackCue.ZenZoneExited,
		-> true
		MiniGameFeedbackCue.PersonalBestCrossed,
		MiniGameFeedbackCue.GoalReached,
		is MiniGameFeedbackCue.SessionCompleted,
		-> false
	}

	private fun MiniGameFeedbackCue.isZenCue(): Boolean =
		this == MiniGameFeedbackCue.ZenZoneEntered ||
			this == MiniGameFeedbackCue.ZenZoneExited

	private companion object {
		const val GLOBAL_DEBOUNCE_MS: Long = 1_000L
		const val ZEN_SUPPRESSION_MS: Long = 30_000L
	}
}
