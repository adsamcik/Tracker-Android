package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery

/** Source-specific detail content selected from one authenticated history snapshot. */
sealed interface TripDetailSourcePresentation {
	/** Capture authority has not resolved yet, so Location affordances remain hidden. */
	data object Resolving : TripDetailSourcePresentation

	/** Captured-source history failed to resolve, so stale Location affordances remain hidden. */
	data object Failed : TripDetailSourcePresentation

	/** The composed source query failed with a typed, retryable boundary. */
	data class Unavailable(
		val reason: TrackingHistoryUnavailableReason,
		val source: HistorySource?,
	) : TripDetailSourcePresentation

	/** Existing Trip Detail behavior backed by exact capture history that includes Location. */
	data object Standard : TripDetailSourcePresentation

	/** Existing retained Trip Detail behavior where historical capture authority is unavailable. */
	data object LegacyUnverifiable : TripDetailSourcePresentation

	/** Retained Activity facts for an exact Activity-only capture history. */
	data class ActivityOnly(
		val activity: ActivityHistoryEntry,
	) : TripDetailSourcePresentation

	/** Retained Steps facts for an exact Steps-only capture history. */
	data class StepsOnly(
		val steps: StepsHistory,
	) : TripDetailSourcePresentation

	/** Contained direct Pressure facts for an exact Pressure-only capture history. */
	data class PressureOnly(
		val pressure: PressureHistory,
	) : TripDetailSourcePresentation

	/** Identity-free retained Wi-Fi facts for an exact Wi-Fi-only physical capture. */
	data class WifiOnly(
		val history: WifiHistoryEntry,
	) : TripDetailSourcePresentation

	/** Identity-free retained Cell facts for an exact Cell-only physical capture. */
	data class CellOnly(
		val history: CellHistoryEntry,
	) : TripDetailSourcePresentation

	/** Portable Steps membership, without any claim about the original full capture selection. */
	data class ImportedSteps(
		val steps: StepsHistory,
	) : TripDetailSourcePresentation

	/** Exact captured sources that do not include Location and lack a dedicated detail product. */
	data class CapturedWithoutLocation(
		val capturedSources: Set<HistorySource>,
	) : TripDetailSourcePresentation {
		init {
			require(capturedSources.isNotEmpty())
			require(HistorySource.LOCATION !in capturedSources)
		}
	}
}

/** Existing map, route, GPX, distance, speed, and elevation content is safe only here. */
internal val TripDetailState.Loaded.supportsLocationPresentation: Boolean
	get() = sourcePresentation == TripDetailSourcePresentation.Standard ||
		sourcePresentation == TripDetailSourcePresentation.LegacyUnverifiable

/** Resolved source-only rows expose only a typed unavailable action until source APIs bind one. */
internal val TripDetailState.Loaded.hasUnavailableSourceActions: Boolean
	get() = when (sourcePresentation) {
		is TripDetailSourcePresentation.ActivityOnly,
		is TripDetailSourcePresentation.CapturedWithoutLocation,
		is TripDetailSourcePresentation.ImportedSteps,
		is TripDetailSourcePresentation.PressureOnly,
		is TripDetailSourcePresentation.StepsOnly,
		is TripDetailSourcePresentation.WifiOnly,
		is TripDetailSourcePresentation.CellOnly -> true
		TripDetailSourcePresentation.Failed,
		is TripDetailSourcePresentation.Unavailable,
		TripDetailSourcePresentation.LegacyUnverifiable,
		TripDetailSourcePresentation.Resolving,
		TripDetailSourcePresentation.Standard -> false
	}

internal fun LiveSessionHistorySnapshot.toTripDetailSourcePresentation(): TripDetailSourcePresentation {
	val sessionHistory = when (val query = session) {
		is SessionHistoryQuery.Found -> query.history
		is SessionHistoryQuery.Unavailable -> return TripDetailSourcePresentation.Unavailable(
			reason = query.reason,
			source = query.source,
		)
		SessionHistoryQuery.NotFound -> return TripDetailSourcePresentation.Failed
	}
	return when (val capture = sessionHistory.capture) {
		HistoryCapture.Unverifiable -> TripDetailSourcePresentation.LegacyUnverifiable
		is HistoryCapture.ImportedSteps -> TripDetailSourcePresentation.ImportedSteps(
			sessionHistory.steps,
		)
		is HistoryCapture.Exact -> {
			val sourceSets = capture.revisions.map { revision -> revision.capturedSources }
			when {
				sourceSets.all { it == setOf(HistorySource.WIFI) } -> when (
					val query = sessionHistory.sourceProducts?.wifi ?: wifi
				) {
					is WifiHistoryQuery.Found -> query.entry.historyUnavailableReasonOrNull()?.let {
						reason -> TripDetailSourcePresentation.Unavailable(
							reason = reason,
							source = HistorySource.WIFI,
						)
					} ?: TripDetailSourcePresentation.WifiOnly(query.entry)
					is WifiHistoryQuery.Failed -> TripDetailSourcePresentation.Unavailable(
						reason = query.cause.toHistoryUnavailableReason(),
						source = HistorySource.WIFI,
					)
					null,
					WifiHistoryQuery.NotFound -> TripDetailSourcePresentation.Unavailable(
						reason = TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						source = HistorySource.WIFI,
					)
				}
				sourceSets.all { it == setOf(HistorySource.CELL) } -> when (
					val query = sessionHistory.sourceProducts?.cell ?: cell
				) {
					is CellHistoryQuery.Found -> query.entry.historyUnavailableReasonOrNull()?.let {
						reason -> TripDetailSourcePresentation.Unavailable(
							reason = reason,
							source = HistorySource.CELL,
						)
					} ?: TripDetailSourcePresentation.CellOnly(query.entry)
					null,
					CellHistoryQuery.NotFound -> TripDetailSourcePresentation.Unavailable(
						reason = TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						source = HistorySource.CELL,
					)
				}
				sourceSets.all { it == setOf(HistorySource.ACTIVITY) } -> {
					val activityHistory = requireNotNull(
						(
							sessionHistory.sourceProducts?.activity ?: activity
						) as? ActivityHistoryQuery.Found,
					) { "Live Activity-only detail requires the common Activity product" }
					activityHistory.entry.historyUnavailableReasonOrNull()?.let {
						reason -> TripDetailSourcePresentation.Unavailable(
							reason = reason,
							source = HistorySource.ACTIVITY,
						)
					} ?: TripDetailSourcePresentation.ActivityOnly(activityHistory.entry)
				}
				sourceSets.all { it == setOf(HistorySource.STEPS) } ->
					TripDetailSourcePresentation.StepsOnly(sessionHistory.steps)
				sourceSets.all { it == setOf(HistorySource.PRESSURE) } -> {
					val pressureHistory = requireNotNull(
						(
							sessionHistory.sourceProducts?.pressure ?: pressure
						) as? PressureSessionHistoryQuery.Found,
					) { "Live Pressure-only detail requires the common Pressure product" }
					pressureHistory.history.pressure.historyUnavailableReasonOrNull()?.let {
						reason -> TripDetailSourcePresentation.Unavailable(
							reason = reason,
							source = HistorySource.PRESSURE,
						)
					} ?: TripDetailSourcePresentation.PressureOnly(
						pressureHistory.history.pressure,
					)
				}
				sourceSets.any { HistorySource.LOCATION in it } ->
					TripDetailSourcePresentation.Standard
				else -> TripDetailSourcePresentation.CapturedWithoutLocation(
					capturedSources = sourceSets.flatten().toSet(),
				)
			}
		}
	}
}

private fun WifiHistoryEntry.historyUnavailableReasonOrNull(): TrackingHistoryUnavailableReason? =
	if (state == WifiHistoryProductState.FAILED ||
		causes.any(WifiHistoryCause::isIntegrityFailure)
	) {
		causes.firstOrNull(WifiHistoryCause::isIntegrityFailure)?.toHistoryUnavailableReason()
			?: TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
	} else {
		null
	}

private fun WifiHistoryCause.toHistoryUnavailableReason(): TrackingHistoryUnavailableReason =
	if (this == WifiHistoryCause.READ_BUDGET_EXCEEDED) {
		TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
	} else {
		TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
	}

private fun CellHistoryEntry.historyUnavailableReasonOrNull(): TrackingHistoryUnavailableReason? =
	if (state == CellHistoryProductState.UNVERIFIABLE ||
		state == CellHistoryProductState.FAILED ||
		causes.any(CellHistoryCause::isIntegrityFailure)
	) {
		if (CellHistoryCause.READ_BUDGET_EXCEEDED in causes) {
			TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		} else {
			TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
		}
	} else {
		null
	}

private fun ActivityHistoryEntry.historyUnavailableReasonOrNull():
	TrackingHistoryUnavailableReason? =
	if (state == ActivityHistoryProductState.FAILED ||
		causes.any(ActivityHistoryCause::isIntegrityFailure)
	) {
		if (ActivityHistoryCause.READ_BUDGET_EXCEEDED in causes) {
			TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		} else {
			TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
		}
	} else {
		null
	}

private fun PressureHistory.historyUnavailableReasonOrNull(): TrackingHistoryUnavailableReason? =
	if (productState == HistoryProductState.FAILED) {
		if (PressureHistoryCause.BATCH_DEPENDENCY_OVERFLOW in causes ||
			PressureHistoryCause.IMPORTED_DEPENDENCY_OVERFLOW in causes
		) {
			TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
		} else {
			TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE
		}
	} else {
		null
	}
