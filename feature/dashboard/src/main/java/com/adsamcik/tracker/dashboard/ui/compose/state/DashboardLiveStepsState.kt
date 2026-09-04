package com.adsamcik.tracker.dashboard.ui.compose.state

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
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

	/** Exact history does not establish a Steps-only capture set; preserve the existing surface. */
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

	/** The history stream failed or returned a different segment than the requested binding. */
	data class HistoryUnavailable(
		val segmentId: Long,
	) : DashboardLiveSessionPresentation {
		init {
			require(segmentId > 0L)
		}
	}
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
