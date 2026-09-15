package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LiveSessionHistorySnapshot
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory

/** Source-specific detail content selected from one authenticated history snapshot. */
sealed interface TripDetailSourcePresentation {
	/** Capture authority has not resolved yet, so Location affordances remain hidden. */
	data object Resolving : TripDetailSourcePresentation

	/** Captured-source history failed to resolve, so stale Location affordances remain hidden. */
	data object Failed : TripDetailSourcePresentation

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
		is TripDetailSourcePresentation.StepsOnly -> true
		TripDetailSourcePresentation.Failed,
		TripDetailSourcePresentation.LegacyUnverifiable,
		TripDetailSourcePresentation.Resolving,
		TripDetailSourcePresentation.Standard -> false
	}

internal fun LiveSessionHistorySnapshot.toTripDetailSourcePresentation(): TripDetailSourcePresentation {
	val sessionHistory = (session as? SessionHistoryQuery.Found)?.history
		?: return TripDetailSourcePresentation.Failed
	return when (val capture = sessionHistory.capture) {
		HistoryCapture.Unverifiable -> TripDetailSourcePresentation.LegacyUnverifiable
		is HistoryCapture.ImportedSteps -> TripDetailSourcePresentation.ImportedSteps(
			sessionHistory.steps,
		)
		is HistoryCapture.Exact -> {
			val sourceSets = capture.revisions.map { revision -> revision.capturedSources }
			when {
				sourceSets.all { it == setOf(HistorySource.ACTIVITY) } -> {
					val activityHistory = requireNotNull(
						(activity as? ActivityHistoryQuery.Found)?.entry,
					) { "Live Activity-only detail requires the common Activity product" }
					TripDetailSourcePresentation.ActivityOnly(activityHistory)
				}
				sourceSets.all { it == setOf(HistorySource.STEPS) } ->
					TripDetailSourcePresentation.StepsOnly(sessionHistory.steps)
				sourceSets.all { it == setOf(HistorySource.PRESSURE) } -> {
					val pressureHistory = requireNotNull(
						(pressure as? PressureSessionHistoryQuery.Found)?.history,
					) { "Live Pressure-only detail requires the common Pressure product" }
					TripDetailSourcePresentation.PressureOnly(pressureHistory.pressure)
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
