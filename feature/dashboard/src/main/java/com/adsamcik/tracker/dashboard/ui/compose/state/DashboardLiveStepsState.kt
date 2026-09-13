package com.adsamcik.tracker.dashboard.ui.compose.state

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause

/** Evidence-backed presentation selection for the currently active physical session segment. */
@Immutable
sealed interface DashboardLiveSessionPresentation {
	/** No persisted active session segment is currently published by the tracker. */
	data object Inactive : DashboardLiveSessionPresentation

	/** The active segment is known, but its durable capture authority is not resolved yet. */
	data class Resolving(
		val segmentId: Long,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}

	/** Exact history does not establish a supported source-only set; preserve the existing surface. */
	data class Standard(
		val segmentId: Long,
		/** Qualified Steps for this mixed-source segment, or null when Steps is not qualified. */
		val steps: DashboardLiveStepsValue? = null,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}

	/** Every retained capture revision contains Steps and no other persisted capture source. */
	data class StepsOnly(
		val segmentId: Long,
		val steps: DashboardLiveStepsValue,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}

	/** Exact Pressure-only capture with a truthful source-local product state. */
	data class PressureOnly(
		val segmentId: Long,
		val pressure: DashboardLivePressureValue,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}

	/** Exact Activity-only intent with source-local captured-product evidence. */
	data class ActivityOnly(
		val segmentId: Long,
		val activity: DashboardLiveActivityValue,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}

	/** The history stream failed or returned a different segment than the requested binding. */
	data class HistoryUnavailable(
		val segmentId: Long,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}
}

/** Direct Pressure values only; this is deliberately not elevation, ascent, or altitude. */
@Immutable
data class DashboardLivePressureMetrics(
	val latestHectopascals: Float,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val changeHectopascals: Float,
	val coverage: PressureHistoryCoverage,
	val zoneAuthorities: List<String>,
) {
	init {
		require(latestHectopascals.isFinite() && latestHectopascals > 0f)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(latestHectopascals in minimumHectopascals..maximumHectopascals)
		require(changeHectopascals.isFinite())
		require(zoneAuthorities.isNotEmpty() && zoneAuthorities.all(String::isNotBlank))
	}
}

/** Truthful durable Pressure state for an accepted exact-capture Pressure-only session. */
@Immutable
sealed interface DashboardLivePressureValue {
	data class Ready(val metrics: DashboardLivePressureMetrics) : DashboardLivePressureValue
	data class Partial(val metrics: DashboardLivePressureMetrics?) : DashboardLivePressureValue
	data class Materializing(val metrics: DashboardLivePressureMetrics?) : DashboardLivePressureValue
	data object Unavailable : DashboardLivePressureValue
	data object Failed : DashboardLivePressureValue
}

/** Captured Activity presentation; missing facts remain null and never become zero duration. */
@Immutable
data class DashboardLiveActivityValue(
	val state: ActivityHistoryProductState,
	val coverage: ActivityHistoryCoverage,
	val knownActiveDurationNanos: Long?,
	val latestMovementBand: ActivityHistoryType?,
	val gapCount: Int,
	val hasRetainedQualifiedEvidence: Boolean,
) {
	init {
		require(knownActiveDurationNanos == null || knownActiveDurationNanos >= 0L)
		require(gapCount >= 0)
		require(
			!hasRetainedQualifiedEvidence ||
				(state != ActivityHistoryProductState.UNAVAILABLE &&
					state != ActivityHistoryProductState.FAILED),
		) { "Unavailable or failed Activity cannot claim retained qualified evidence" }
		require(hasRetainedQualifiedEvidence ||
			(knownActiveDurationNanos == null && latestMovementBand == null)
		) { "Activity values require retained qualified band evidence" }
	}
}

internal fun ActivityHistoryEntry.toDashboardLiveActivityValue(): DashboardLiveActivityValue {
	val bands = fragments.filterIsInstance<ActivityHistoryFragment.Band>()
	val hasEvidence = bands.isNotEmpty() && activeTime != null
	return DashboardLiveActivityValue(
		state = state,
		coverage = coverage,
		knownActiveDurationNanos = activeTime?.knownActiveDurationNanos.takeIf { hasEvidence },
		latestMovementBand = bands.lastOrNull()?.activity.takeIf { hasEvidence },
		gapCount = fragments.count { it is ActivityHistoryFragment.Gap },
		hasRetainedQualifiedEvidence = hasEvidence,
	)
}

/** Truthful durable Steps value for the active exact-capture Steps-only session. */
@Immutable
sealed interface DashboardLiveStepsValue {
	/** A positive, correction-safe value covering the complete session interval so far. */
	data class Complete(
		val count: Long,
	) : DashboardLiveStepsValue {
		init {
			require(count > 0L)
		}
	}

	/** A verified covered interval with no admitted positive delta. */
	data object CoveredZero : DashboardLiveStepsValue

	/** Incomplete coverage, optionally with a strictly positive correction-safe lower bound. */
	data class Partial(
		val lowerBound: Long? = null,
	) : DashboardLiveStepsValue {
		init {
			require(lowerBound == null || lowerBound > 0L)
		}
	}

	/** Durable projection has not reached a stable product result. */
	data object Materializing : DashboardLiveStepsValue

	/** Steps was captured, but no qualifying retained observation can provide a value yet. */
	data object Missing : DashboardLiveStepsValue

	/** Retained availability or product evidence cannot provide a trustworthy value. */
	data object Unavailable : DashboardLiveStepsValue
}

/** Never converts missing, unavailable, materializing, or zero-only partial evidence into zero. */
internal fun StepsHistory.toDashboardLiveStepsValue(): DashboardLiveStepsValue = when {
	availability != HistoryAvailability.AVAILABLE -> DashboardLiveStepsValue.Unavailable
	hasCompleteValue -> completeValue()
	isLowerBound -> DashboardLiveStepsValue.Partial(count?.takeIf { it > 0L })
	isMaterializing -> DashboardLiveStepsValue.Materializing
	hasMissingEvidence -> DashboardLiveStepsValue.Missing
	else -> fallbackValue()
}

private fun StepsHistory.completeValue(): DashboardLiveStepsValue = if (count == 0L) {
	DashboardLiveStepsValue.CoveredZero
} else {
	DashboardLiveStepsValue.Complete(requireNotNull(count))
}

private val StepsHistory.isMaterializing: Boolean
	get() = productState == HistoryProductState.MATERIALIZING || evidence == HistoryEvidence.STARTING

private val StepsHistory.hasMissingEvidence: Boolean
	get() = evidence == HistoryEvidence.NONE && causes.any(MISSING_EVIDENCE_CAUSES::contains)

private fun StepsHistory.fallbackValue(): DashboardLiveStepsValue = when (productState) {
	HistoryProductState.PARTIAL -> DashboardLiveStepsValue.Partial()
	HistoryProductState.DEGRADED,
	HistoryProductState.FAILED -> DashboardLiveStepsValue.Unavailable
	else -> DashboardLiveStepsValue.Missing
}

private val MISSING_EVIDENCE_CAUSES = setOf(
	StepsHistoryCause.BASELINE_ONLY,
	StepsHistoryCause.NO_OBSERVATION,
)

/** Missing or nonnumeric Pressure history remains nonnumeric all the way into Compose. */
internal fun PressureHistory.toDashboardLivePressureValue(): DashboardLivePressureValue {
	val metrics = summary?.let { summary ->
		DashboardLivePressureMetrics(
			latestHectopascals = summary.latestHectopascals,
			minimumHectopascals = summary.minimumHectopascals,
			maximumHectopascals = summary.maximumHectopascals,
			changeHectopascals = summary.latestHectopascals - summary.firstHectopascals,
			coverage = coverage,
			zoneAuthorities = zoneAuthorities.sorted(),
		)
	}
	return when (presentationState) {
		PressureHistoryPresentationState.READY ->
			DashboardLivePressureValue.Ready(requireNotNull(metrics))
		PressureHistoryPresentationState.PARTIAL -> DashboardLivePressureValue.Partial(metrics)
		PressureHistoryPresentationState.MATERIALIZING ->
			DashboardLivePressureValue.Materializing(metrics)
		PressureHistoryPresentationState.UNAVAILABLE -> DashboardLivePressureValue.Unavailable
		PressureHistoryPresentationState.FAILED -> DashboardLivePressureValue.Failed
	}
}
