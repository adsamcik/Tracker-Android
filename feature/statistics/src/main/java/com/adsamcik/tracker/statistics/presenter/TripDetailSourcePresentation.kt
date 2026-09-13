package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory

/** Source-specific detail content selected from one authenticated history snapshot. */
sealed interface TripDetailSourcePresentation {
	/** Capture authority has not resolved yet, so Location affordances remain hidden. */
	data object Resolving : TripDetailSourcePresentation

	/** Captured-source history failed to resolve, so stale Location affordances remain hidden. */
	data object Failed : TripDetailSourcePresentation

	/** Existing Trip Detail behavior for mixed, Location, and legacy-unverifiable history. */
	data object Standard : TripDetailSourcePresentation

	/** Contained direct Pressure facts for an exact Pressure-only capture history. */
	data class PressureOnly(
		val pressure: PressureHistory,
	) : TripDetailSourcePresentation
}

/** Existing map, route, GPX, distance, speed, and elevation content is safe only here. */
internal val TripDetailState.Loaded.supportsLocationPresentation: Boolean
	get() = sourcePresentation == TripDetailSourcePresentation.Standard

internal fun PressureSessionHistory.toTripDetailSourcePresentation(): TripDetailSourcePresentation {
	val exactCapture = capture as? HistoryCapture.Exact
		?: return TripDetailSourcePresentation.Standard
	val isPressureOnly = exactCapture.revisions.all { revision ->
		revision.capturedSources == setOf(HistorySource.PRESSURE)
	}
	return if (isPressureOnly) {
		TripDetailSourcePresentation.PressureOnly(pressure)
	} else {
		TripDetailSourcePresentation.Standard
	}
}
