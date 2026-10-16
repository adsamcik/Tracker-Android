package com.adsamcik.tracker.shared.base.database.data

/**
 * Canonical wall-time bounds used when an acknowledged retention floor is compared with captured
 * source evidence. A bound equal to the floor remains eligible; only an earlier bound is expired.
 */
object CanonicalRetentionBounds {
	/**
	 * Returns observed UTC milliseconds minus uncertainty; invalid or negative bounds return null.
	 */
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

	/**
	 * Returns the uncertainty-adjusted UTC millisecond interval start, saturating at zero for valid
	 * uncertainty.
	 */
	fun pressure(
		intervalStartTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): Long? = walPoint(
		intervalStartTimeMs,
		wallTimeUncertaintyMs,
	)

	/**
	 * Returns the uncertainty-adjusted UTC observation time with a zero lower bound; invalid inputs
	 * return null.
	 */
	fun walPoint(
		observedWallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): Long? = saturatingUncertaintyAdjustedPoint(
		observedWallTimeMs,
		wallTimeUncertaintyMs,
	)

	/**
	 * Returns the earliest uncertainty-adjusted interval start across the lineage; an empty or
	 * invalid lineage returns null.
	 */
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
		return if (found) {
			minimum
		} else {
			null
		}
	}

	/**
	 * Converts the elapsed coverage span to a conservative UTC start, rounding fractional
	 * milliseconds outward and applying uncertainty.
	 */
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

	/**
	 * Anchors elapsed Cell coverage to the saved UTC observation time and applies uncertainty;
	 * invalid bounds return null.
	 */
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

	/**
	 * Anchors the elapsed Cell coverage start to UTC milliseconds without adding clock uncertainty.
	 */
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

	/**
	 * Returns the earliest uncertainty-adjusted bound of recorded Activity bands; missing band times
	 * or an empty band set return null.
	 */
	fun activityLineage(
		fragments: Iterable<ActivityCapturedFragmentEntity>,
	): Long? {
		var minimum: Long? = null
		var foundBand = false
		for (fragment in fragments) {
			if (fragment.fragmentKind != ActivityCapturedFragmentEntity.KIND_BAND) {
				continue
			}
			foundBand = true
			val start = saturatingUncertaintyAdjustedPoint(
				fragment.startWallTimeMs,
				fragment.startWallTimeUncertaintyMs,
			) ?: return null
			val end = saturatingUncertaintyAdjustedPoint(
				fragment.endWallTimeMs,
				fragment.endWallTimeUncertaintyMs,
			) ?: return null
			val fragmentMinimum = minOf(start, end)
			minimum = minimum?.let { minOf(it, fragmentMinimum) } ?: fragmentMinimum
		}
		return if (foundBand) {
			minimum
		} else {
			null
		}
	}

	/**
	 * Treats an unknown lower bound or a time before the nonnegative retention floor as crossing it.
	 */
	fun crossesFloor(lowerBoundMs: Long?, retainedFromMs: Long): Boolean {
		require(retainedFromMs >= 0L)
		return lowerBoundMs == null || lowerBoundMs < retainedFromMs
	}

	/**
	 * Treats an unknown Steps window end or an end before the retention floor as crossing it; a
	 * boundary equal to the floor stays retained.
	 */
	fun stepsWindowCrossesFloor(intervalEndTimeMs: Long?, retainedFromMs: Long): Boolean {
		require(retainedFromMs >= 0L)
		return intervalEndTimeMs == null || intervalEndTimeMs < retainedFromMs
	}
}

private fun saturatingUncertaintyAdjustedPoint(
	wallTimeMs: Long?,
	uncertaintyMs: Long?,
): Long? {
	if (wallTimeMs == null || uncertaintyMs == null || wallTimeMs < 0L || uncertaintyMs < 0L) {
		return null
	}
	return if (uncertaintyMs >= wallTimeMs) {
		0L
	} else {
		wallTimeMs - uncertaintyMs
	}
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
	val conservativeFraction = if (ageNanos % NANOS_PER_MILLISECOND == 0L) {
		0L
	} else {
		1L
	}
	val ageMs = Math.addExact(wholeMilliseconds, conservativeFraction)
	Math.subtractExact(
		Math.subtractExact(walObservedWallTimeMs, ageMs),
		wallTimeUncertaintyMs,
	).takeIf { it >= 0L }
}.getOrNull()

private const val NANOS_PER_MILLISECOND = 1_000_000L
