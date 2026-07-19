package com.adsamcik.tracker.game.minigame

import java.util.Collections
import kotlin.math.abs

internal data class MiniGameSnapshot(
	val phase: MiniGamePhase,
	val signal: MiniGameSignal,
	val elapsedActiveTimeMs: Long,
	val goalProgress: MiniGameGoalProgress,
	val personalBest: MiniGamePersonalBest,
	val latestFeedback: MiniGameFeedback?,
	val visualPayload: MiniGameVisualPayload,
) {
	init {
		require(elapsedActiveTimeMs >= 0L) { "Active elapsed time cannot be negative" }
	}
}

internal enum class MiniGamePhase {
	WAITING_TO_START,
	ACQUIRING_SIGNAL,
	ACTIVE,
	WARNING,
	PAUSED,
	COMPLETED,
	FAILED,
}

internal data class MiniGameSignal(
	val quality: MiniGameSignalQuality,
	val ageMs: Long?,
	val isStale: Boolean,
) {
	init {
		require(ageMs == null || ageMs >= 0L) { "Signal age cannot be negative" }
		require(quality != MiniGameSignalQuality.UNAVAILABLE || ageMs == null) {
			"Unavailable signal cannot have a sample age"
		}
		require(!isStale || ageMs != null) { "Stale signal must include its age" }
	}

	companion object {
		val UNKNOWN: MiniGameSignal = MiniGameSignal(
			quality = MiniGameSignalQuality.UNKNOWN,
			ageMs = null,
			isStale = false,
		)
	}
}

internal enum class MiniGameSignalQuality {
	UNAVAILABLE,
	UNKNOWN,
	GOOD,
	FAIR,
	POOR,
}

internal sealed interface MiniGameGoalProgress {
	data object NotConfigured : MiniGameGoalProgress

	data class Tracked(
		val current: Double,
		val target: Double,
	) : MiniGameGoalProgress {
		init {
			require(current.isFinite() && current >= 0.0) {
				"Goal progress must be finite and non-negative"
			}
			require(target.isFinite() && target > 0.0) {
				"Goal target must be finite and positive"
			}
		}

		val fraction: Double get() = (current / target).coerceIn(0.0, 1.0)
		val isReached: Boolean get() = current >= target
	}
}

internal enum class MiniGamePersonalBestComparison {
	FIRST_RUN,
	BEHIND,
	TIED,
	AHEAD,
}

internal data class MiniGamePersonalBest(
	val scoreBeforeRun: Double?,
	val comparison: MiniGamePersonalBestComparison,
	val difference: Double,
) {
	init {
		require(scoreBeforeRun == null || scoreBeforeRun.isFinite() && scoreBeforeRun >= 0.0) {
			"Personal best must be null or a finite non-negative score"
		}
		require(difference.isFinite() && difference >= 0.0) {
			"Personal-best difference must be finite and non-negative"
		}
		require(
			(scoreBeforeRun == null) ==
				(comparison == MiniGamePersonalBestComparison.FIRST_RUN),
		) {
			"First-run comparison must match the absence of a prior score"
		}
		require(comparison != MiniGamePersonalBestComparison.FIRST_RUN || difference == 0.0) {
			"First-run comparison requires zero difference"
		}
		require(
			comparison == MiniGamePersonalBestComparison.FIRST_RUN ||
				(comparison == MiniGamePersonalBestComparison.TIED) == (difference == 0.0),
		) {
			"Tied comparisons require zero difference"
		}
	}

	companion object {
		private const val TIE_TOLERANCE: Double = 0.000_001

		fun compare(
			currentScore: Double,
			scoreBeforeRun: Double?,
		): MiniGamePersonalBest {
			require(currentScore.isFinite() && currentScore >= 0.0) {
				"Current score must be finite and non-negative"
			}
			require(scoreBeforeRun == null || scoreBeforeRun.isFinite() && scoreBeforeRun >= 0.0) {
				"Personal best must be null or a finite non-negative score"
			}
			if (scoreBeforeRun == null) {
				return MiniGamePersonalBest(
					scoreBeforeRun = null,
					comparison = MiniGamePersonalBestComparison.FIRST_RUN,
					difference = 0.0,
				)
			}

			val delta = currentScore - scoreBeforeRun
			val comparison = when {
				abs(delta) <= TIE_TOLERANCE -> MiniGamePersonalBestComparison.TIED
				delta > 0.0 -> MiniGamePersonalBestComparison.AHEAD
				else -> MiniGamePersonalBestComparison.BEHIND
			}
			return MiniGamePersonalBest(
				scoreBeforeRun = scoreBeforeRun,
				comparison = comparison,
				difference = if (comparison == MiniGamePersonalBestComparison.TIED) {
					0.0
				} else {
					abs(delta)
				},
			)
		}
	}
}

/**
 * Privacy-safe visual data for the active game. Location coordinates and
 * globally stable cell identifiers are deliberately absent from every variant.
 */
internal sealed interface MiniGameVisualPayload {
	data object Pending : MiniGameVisualPayload

	data class Outrun(
		val currentGapMeters: Double,
		val warningThresholdMeters: Double,
		val bestThisRunMeters: Double,
		val personalBestMeters: Double?,
		val hasRaceStarted: Boolean = true,
	) : MiniGameVisualPayload {
		init {
			require(currentGapMeters.isFinite()) { "Outrun gap must be finite" }
			require(warningThresholdMeters.isFinite() && warningThresholdMeters >= 0.0) {
				"Warning threshold must be finite and non-negative"
			}
			require(bestThisRunMeters.isFinite() && bestThisRunMeters >= 0.0) {
				"Best-this-run value must be finite and non-negative"
			}
			require(bestThisRunMeters >= currentGapMeters) {
				"Best-this-run value cannot be lower than the current gap"
			}
			require(
				personalBestMeters == null ||
					personalBestMeters.isFinite() && personalBestMeters >= 0.0,
			) {
				"Personal best must be null or a finite non-negative distance"
			}
		}

		val isInWarningZone: Boolean
			get() = hasRaceStarted && currentGapMeters < warningThresholdMeters
	}

	class Territory(
		claimedCells: Set<TerritoryRelativeCell>,
		val currentCell: TerritoryRelativeCell?,
		recentTrail: List<TerritoryRelativeCell>,
	) : MiniGameVisualPayload {
		val claimedCells: Set<TerritoryRelativeCell> =
			Collections.unmodifiableSet(LinkedHashSet(claimedCells))
		val recentTrail: List<TerritoryRelativeCell> =
			Collections.unmodifiableList(ArrayList(recentTrail))

		init {
			require(this.claimedCells.size <= TerritoryRelativeCell.GRID_CELL_COUNT) {
				"Claimed cells cannot exceed the local grid"
			}
			require(this.recentTrail.size <= TerritoryRelativeCell.GRID_CELL_COUNT) {
				"Recent trail cannot exceed the local grid capacity"
			}
			require(currentCell == null || currentCell in this.claimedCells) {
				"Current cell must be claimed"
			}
		}

		override fun equals(other: Any?): Boolean =
			other is Territory &&
				claimedCells == other.claimedCells &&
				currentCell == other.currentCell &&
				recentTrail == other.recentTrail

		override fun hashCode(): Int {
			var result = claimedCells.hashCode()
			result = 31 * result + (currentCell?.hashCode() ?: 0)
			result = 31 * result + recentTrail.hashCode()
			return result
		}

		override fun toString(): String =
			"Territory(claimedCells=$claimedCells, currentCell=$currentCell, recentTrail=$recentTrail)"
	}

	data class ZenWalk(
		val calibrationProgress: Double,
		val currentSmoothedPaceMetersPerSecond: Double?,
		val targetPaceZone: ZenPaceZone?,
		val timeInZoneMs: Long,
	) : MiniGameVisualPayload {
		init {
			require(calibrationProgress.isFinite() && calibrationProgress in 0.0..1.0) {
				"Calibration progress must be between zero and one"
			}
			require(
				currentSmoothedPaceMetersPerSecond == null ||
					currentSmoothedPaceMetersPerSecond.isFinite() &&
					currentSmoothedPaceMetersPerSecond >= 0.0,
			) {
				"Smoothed pace must be null or finite and non-negative"
			}
			require(timeInZoneMs >= 0L) { "Time in zone cannot be negative" }
		}
	}
}

/**
 * A cell within a 7x7 visualization centered on the session's private origin.
 * No conversion back to a global grid key is provided.
 */
internal data class TerritoryRelativeCell(
	val rowOffset: Int,
	val columnOffset: Int,
) {
	init {
		require(rowOffset in MIN_OFFSET..MAX_OFFSET) {
			"Row offset must fit the origin-relative 7x7 grid"
		}
		require(columnOffset in MIN_OFFSET..MAX_OFFSET) {
			"Column offset must fit the origin-relative 7x7 grid"
		}
	}

	companion object {
		private const val MIN_OFFSET: Int = -3
		private const val MAX_OFFSET: Int = 3
		const val GRID_CELL_COUNT: Int = 49
	}
}

internal data class ZenPaceZone(
	val minimumMetersPerSecond: Double,
	val maximumMetersPerSecond: Double,
) {
	init {
		require(minimumMetersPerSecond.isFinite() && minimumMetersPerSecond >= 0.0) {
			"Minimum pace must be finite and non-negative"
		}
		require(maximumMetersPerSecond.isFinite() && maximumMetersPerSecond > minimumMetersPerSecond) {
			"Maximum pace must be finite and greater than minimum pace"
		}
	}
}
