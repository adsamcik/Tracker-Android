package com.adsamcik.tracker.shared.base.database.data

/**
 * Canonical wall-time bounds used when an acknowledged retention floor is compared with captured
 * source evidence. A bound equal to the floor remains eligible; only an earlier bound is expired.
 */
object CanonicalRetentionBounds {
	fun location(
		observedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): Long? {
		if (observedWallTimeMs < 0L ||
			wallTimeUncertaintyMs < 0L ||
			wallTimeUncertaintyMs > observedWallTimeMs
		) {
			return null
		}
		return observedWallTimeMs - wallTimeUncertaintyMs
	}

	fun pressure(
		intervalStartTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): Long? = walPoint(
		intervalStartTimeMs,
		wallTimeUncertaintyMs,
	)

	fun walPoint(
		observedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): Long? = saturatingUncertaintyAdjustedPoint(
		observedWallTimeMs,
		wallTimeUncertaintyMs,
	)

	fun pressureLineage(revisions: Iterable<PressureFactRevisionEntity>): Long? {
		var minimum: Long? = null
		var found = false
		for (revision in revisions) {
			found = true
			val lowerBound = pressure(
				revision.intervalStartTimeMs,
				revision.wallTimeUncertaintyMs,
			) ?: return null
			minimum = minimum?.let { minOf(it, lowerBound) } ?: lowerBound
		}
		return if (found) minimum else null
	}

	fun wifiCoverage(
		coverageEndWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		coverageStartElapsedRealtimeNanos: Long,
		coverageEndElapsedRealtimeNanos: Long,
	): Long? = coverageLowerBound(
		coverageEndWallTimeMs = coverageEndWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		coverageStartElapsedRealtimeNanos = coverageStartElapsedRealtimeNanos,
		coverageEndElapsedRealtimeNanos = coverageEndElapsedRealtimeNanos,
		roundPartialMillisecondUp = true,
	)

	fun cellCoverage(
		walObservedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		walObservedElapsedRealtimeNanos: Long,
		coverageStartElapsedRealtimeNanos: Long,
	): Long? = anchoredCoveragePoint(
		walObservedWallTimeMs = walObservedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		walObservedElapsedRealtimeNanos = walObservedElapsedRealtimeNanos,
		coverageElapsedRealtimeNanos = coverageStartElapsedRealtimeNanos,
	)

	fun cellCoverageStartWallTime(
		walObservedWallTimeMs: Long,
		walObservedElapsedRealtimeNanos: Long,
		coverageStartElapsedRealtimeNanos: Long,
	): Long? = anchoredCoveragePoint(
		walObservedWallTimeMs = walObservedWallTimeMs,
		wallTimeUncertaintyMs = 0L,
		walObservedElapsedRealtimeNanos = walObservedElapsedRealtimeNanos,
		coverageElapsedRealtimeNanos = coverageStartElapsedRealtimeNanos,
	)

	fun activityLineage(
		fragments: Iterable<ActivityCapturedFragmentEntity>,
	): Long? {
		var minimum: Long? = null
		var foundBand = false
		for (fragment in fragments) {
			if (fragment.fragmentKind != ActivityCapturedFragmentEntity.KIND_BAND) continue
			foundBand = true
			val start = saturatingUncertaintyAdjustedPoint(
				fragment.startWallTimeMs ?: return null,
				fragment.startWallTimeUncertaintyMs ?: return null,
			) ?: return null
			val end = saturatingUncertaintyAdjustedPoint(
				fragment.endWallTimeMs ?: return null,
				fragment.endWallTimeUncertaintyMs ?: return null,
			) ?: return null
			val fragmentMinimum = minOf(start, end)
			minimum = minimum?.let { minOf(it, fragmentMinimum) } ?: fragmentMinimum
		}
		return if (foundBand) minimum else null
	}

	fun crossesFloor(lowerBoundMs: Long?, retainedFromMs: Long): Boolean {
		require(retainedFromMs >= 0L)
		return lowerBoundMs == null || lowerBoundMs < retainedFromMs
	}

	fun stepsWindowCrossesFloor(intervalEndTimeMs: Long?, retainedFromMs: Long): Boolean {
		require(retainedFromMs >= 0L)
		return intervalEndTimeMs == null || intervalEndTimeMs < retainedFromMs
	}

	private fun saturatingUncertaintyAdjustedPoint(
		wallTimeMs: Long,
		uncertaintyMs: Long,
	): Long? {
		if (wallTimeMs < 0L || uncertaintyMs < 0L) return null
		return if (uncertaintyMs >= wallTimeMs) 0L else wallTimeMs - uncertaintyMs
	}

	private fun coverageLowerBound(
		coverageEndWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		coverageStartElapsedRealtimeNanos: Long,
		coverageEndElapsedRealtimeNanos: Long,
		roundPartialMillisecondUp: Boolean,
	): Long? = runCatching {
		require(coverageEndWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		val spanNanos = Math.subtractExact(
			coverageEndElapsedRealtimeNanos,
			coverageStartElapsedRealtimeNanos,
		)
		require(spanNanos >= 0L)
		val spanMs = spanNanos / NANOS_PER_MILLISECOND
		val roundingMs = if (
			roundPartialMillisecondUp && spanNanos % NANOS_PER_MILLISECOND != 0L
		) {
			1L
		} else {
			0L
		}
		Math.subtractExact(
			Math.subtractExact(coverageEndWallTimeMs, spanMs),
			Math.addExact(wallTimeUncertaintyMs, roundingMs),
		).takeIf { it >= 0L }
	}.getOrNull()

	private fun anchoredCoveragePoint(
		walObservedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		walObservedElapsedRealtimeNanos: Long,
		coverageElapsedRealtimeNanos: Long,
	): Long? = runCatching {
		require(walObservedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
		val ageNanos = Math.subtractExact(
			walObservedElapsedRealtimeNanos,
			coverageElapsedRealtimeNanos,
		)
		require(ageNanos >= 0L)
		val wholeMilliseconds = ageNanos / NANOS_PER_MILLISECOND
		val conservativeFraction = if (ageNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
		val ageMs = Math.addExact(wholeMilliseconds, conservativeFraction)
		Math.subtractExact(
			Math.subtractExact(walObservedWallTimeMs, ageMs),
			wallTimeUncertaintyMs,
		).takeIf { it >= 0L }
	}.getOrNull()

	private const val NANOS_PER_MILLISECOND = 1_000_000L
}
