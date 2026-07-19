package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.switchback.SwitchbackTurnDirection
import java.util.Collections

/**
 * Privacy-safe route-shape state for Switchback.
 *
 * Directions describe only turn handedness within this run. Coordinates,
 * absolute bearings, and globally stable location identifiers never leave
 * the session engine.
 */
internal class SwitchbackVisualPayload(
	val turnsCompleted: Int,
	val targetTurns: Int,
	val currentCombo: Int,
	val bestCombo: Int,
	val expectedTurn: SwitchbackTurnDirection?,
	recentTurns: List<SwitchbackTurnDirection>,
	val hasFirstLeg: Boolean,
	val minimumLegMeters: Double,
	val goalReached: Boolean,
) : MiniGameVisualPayload {
	val recentTurns: List<SwitchbackTurnDirection> =
		Collections.unmodifiableList(ArrayList(recentTurns))

	init {
		require(turnsCompleted >= 0) { "Completed turns cannot be negative" }
		require(targetTurns > 0) { "Target turns must be positive" }
		require(currentCombo in 0..turnsCompleted) {
			"Current combo must fit completed turns"
		}
		require(bestCombo in currentCombo..turnsCompleted) {
			"Best combo must fit completed turns and include the current combo"
		}
		require(this.recentTurns.size <= MAX_RECENT_TURNS) {
			"Recent turn history exceeds the visualization capacity"
		}
		require(minimumLegMeters.isFinite() && minimumLegMeters > 0.0) {
			"Minimum leg distance must be finite and positive"
		}
		require(!goalReached || turnsCompleted >= targetTurns) {
			"Goal completion requires reaching the target"
		}
	}

	override fun equals(other: Any?): Boolean =
		other is SwitchbackVisualPayload &&
			turnsCompleted == other.turnsCompleted &&
			targetTurns == other.targetTurns &&
			currentCombo == other.currentCombo &&
			bestCombo == other.bestCombo &&
			expectedTurn == other.expectedTurn &&
			recentTurns == other.recentTurns &&
			hasFirstLeg == other.hasFirstLeg &&
			minimumLegMeters == other.minimumLegMeters &&
			goalReached == other.goalReached

	override fun hashCode(): Int {
		var result = turnsCompleted
		result = 31 * result + targetTurns
		result = 31 * result + currentCombo
		result = 31 * result + bestCombo
		result = 31 * result + (expectedTurn?.hashCode() ?: 0)
		result = 31 * result + recentTurns.hashCode()
		result = 31 * result + hasFirstLeg.hashCode()
		result = 31 * result + minimumLegMeters.hashCode()
		result = 31 * result + goalReached.hashCode()
		return result
	}

	override fun toString(): String =
		"SwitchbackVisualPayload(turnsCompleted=$turnsCompleted, targetTurns=$targetTurns, " +
			"currentCombo=$currentCombo, bestCombo=$bestCombo, expectedTurn=$expectedTurn, " +
			"recentTurns=$recentTurns, hasFirstLeg=$hasFirstLeg, " +
			"minimumLegMeters=$minimumLegMeters, goalReached=$goalReached)"

	private companion object {
		const val MAX_RECENT_TURNS = 8
	}
}
