package com.adsamcik.tracker.dashboard.ui.compose.tracking

/** Correction-safe milestone buckets scoped to one exact physical session segment. */
internal class SegmentMilestoneHighWater {
	private var activeSegmentId: Long? = null
	private var distanceKm = 0
	private var stepsThousand = 0L
	private var minutesTen = 0

	fun activate(segmentId: Long) {
		if (activeSegmentId == segmentId) {
			return
		}
		activeSegmentId = segmentId
		distanceKm = 0
		stepsThousand = 0L
		minutesTen = 0
	}

	fun clear() {
		activeSegmentId = null
		distanceKm = 0
		stepsThousand = 0L
		minutesTen = 0
	}

	fun record(
		currentDistanceKm: Int,
		currentStepsThousand: Long?,
		currentMinutesTen: Int,
	): MilestoneCrossings {
		check(activeSegmentId != null)
		val crossings = MilestoneCrossings(
			distance = crossed(distanceKm, currentDistanceKm),
			steps = currentStepsThousand?.let { crossed(stepsThousand, it) } == true,
			time = crossed(minutesTen, currentMinutesTen),
		)
		distanceKm = maxOf(distanceKm, currentDistanceKm)
		currentStepsThousand?.let { stepsThousand = maxOf(stepsThousand, it) }
		minutesTen = maxOf(minutesTen, currentMinutesTen)
		return crossings
	}

	private fun crossed(previousHighWater: Int, current: Int): Boolean =
		current > previousHighWater && previousHighWater > 0

	private fun crossed(previousHighWater: Long, current: Long): Boolean =
		current > previousHighWater && previousHighWater > 0L
}

internal data class MilestoneCrossings(
	val distance: Boolean,
	val steps: Boolean,
	val time: Boolean,
)
