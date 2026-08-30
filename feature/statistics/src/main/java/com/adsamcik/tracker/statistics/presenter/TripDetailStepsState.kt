package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause

/** Truthful Steps presentation for one selected session. */
sealed interface TripDetailStepsState {
	/** A correction-safe value covering the complete session interval. */
	data class Complete(val count: Long) : TripDetailStepsState

	/** A correction-safe lower bound covering only part of the session interval. */
	data class LowerBound(val count: Long) : TripDetailStepsState

	/** Retained legacy evidence whose completeness or provenance cannot be verified. */
	data class LegacyUnverified(val recordedCount: Long?) : TripDetailStepsState

	/** Durable projection work has not reached a stable product result. */
	data object Materializing : TripDetailStepsState

	/** The effective session manifest did not request captured Steps. */
	data object NotCaptured : TripDetailStepsState

	/** The effective session policy disabled Steps capture. */
	data object Disabled : TripDetailStepsState

	/** The device did not expose a supported Steps provider. */
	data object Unsupported : TripDetailStepsState

	/** Steps capture required a permission that was not available. */
	data object PermissionRequired : TripDetailStepsState

	/** Android limited Steps acquisition for this session. */
	data object OsLimited : TripDetailStepsState

	/** Retained evidence cannot establish a trustworthy Steps result. */
	data object Unavailable : TripDetailStepsState

	/** Steps was eligible, but no qualifying observation was retained. */
	data object NoObservation : TripDetailStepsState

	/** Retained Steps facts for this session were deleted. */
	data object Deleted : TripDetailStepsState

	/** Durable Steps history failed to materialize. */
	data object Failed : TripDetailStepsState
}

/**
 * Convert the durable history contract without inventing zero or presenting an unverified count
 * as complete. Explicit policy disablement wins over the coarse not-captured cause; other specific
 * causes take precedence over generic availability/product states.
 */
internal fun StepsHistory.toTripDetailStepsState(): TripDetailStepsState {
	val legacyPresentation = legacyState()
	return when {
		legacyPresentation != null -> legacyPresentation
		hasCompleteValue -> TripDetailStepsState.Complete(requireNotNull(count))
		isLowerBound -> TripDetailStepsState.LowerBound(requireNotNull(count))
		StepsHistoryCause.DELETED in causes -> TripDetailStepsState.Deleted
		availability == HistoryAvailability.DISABLED -> TripDetailStepsState.Disabled
		StepsHistoryCause.SOURCE_NOT_CAPTURED in causes -> TripDetailStepsState.NotCaptured
		availability != HistoryAvailability.AVAILABLE -> availability.toTripDetailStepsState()
		productState == HistoryProductState.MATERIALIZING -> TripDetailStepsState.Materializing
		else -> fallbackState()
	}
}

private fun StepsHistory.legacyState(): TripDetailStepsState? = when {
	StepsHistoryCause.LEGACY_UNVERIFIED in causes -> TripDetailStepsState.LegacyUnverified(count)
	else -> null
}

private fun StepsHistory.fallbackState(): TripDetailStepsState = when {
	productState == HistoryProductState.FAILED -> TripDetailStepsState.Failed
	productState.isIncompleteWithoutValue -> TripDetailStepsState.Unavailable
	else -> TripDetailStepsState.NoObservation
}

private fun HistoryAvailability.toTripDetailStepsState(): TripDetailStepsState = when (this) {
	HistoryAvailability.DISABLED -> TripDetailStepsState.Disabled
	HistoryAvailability.UNSUPPORTED -> TripDetailStepsState.Unsupported
	HistoryAvailability.PERMISSION_REQUIRED -> TripDetailStepsState.PermissionRequired
	HistoryAvailability.OS_LIMITED -> TripDetailStepsState.OsLimited
	HistoryAvailability.UNAVAILABLE -> TripDetailStepsState.Unavailable
	HistoryAvailability.AVAILABLE -> error("Available history must be mapped by its evidence state")
}

private val HistoryProductState.isIncompleteWithoutValue: Boolean
	get() = when (this) {
		HistoryProductState.DEGRADED,
		HistoryProductState.PARTIAL -> true
		else -> false
	}
