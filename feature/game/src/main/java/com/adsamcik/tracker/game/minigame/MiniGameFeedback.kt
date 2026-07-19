package com.adsamcik.tracker.game.minigame

@JvmInline
internal value class MiniGameFeedbackEventId(
	val value: Long,
) {
	init {
		require(value > 0L) { "Feedback event ids must be positive" }
	}
}

/**
 * Latest one-shot feedback emitted by a session. Consumers handle an event only
 * when [eventId] is newer than the last id they handled.
 */
internal data class MiniGameFeedback(
	val eventId: MiniGameFeedbackEventId,
	val cue: MiniGameFeedbackCue,
)

internal sealed interface MiniGameFeedbackCue {
	data object OutrunDanger : MiniGameFeedbackCue
	data object PersonalBestCrossed : MiniGameFeedbackCue
	data object TerritoryCellClaimed : MiniGameFeedbackCue
	data object FuseCritical : MiniGameFeedbackCue
	data object FuseDefused : MiniGameFeedbackCue
	data object SwitchbackTurnCarved : MiniGameFeedbackCue
	data object GoalReached : MiniGameFeedbackCue
	data object ZenZoneEntered : MiniGameFeedbackCue
	data object ZenZoneExited : MiniGameFeedbackCue
	data class SessionCompleted(
		val outcome: MiniGameCompletionOutcome,
	) : MiniGameFeedbackCue
}

internal sealed interface MiniGameCompletionOutcome {
	data object FirstRun : MiniGameCompletionOutcome

	data class PersonalBest(
		val improvement: Double,
	) : MiniGameCompletionOutcome {
		init {
			require(improvement.isFinite() && improvement > 0.0) {
				"Personal-best improvement must be finite and positive"
			}
		}
	}

	data object TiedBest : MiniGameCompletionOutcome

	data class BelowBest(
		val distanceFromBest: Double,
	) : MiniGameCompletionOutcome {
		init {
			require(distanceFromBest.isFinite() && distanceFromBest > 0.0) {
				"Distance from best must be finite and positive"
			}
		}
	}
}
