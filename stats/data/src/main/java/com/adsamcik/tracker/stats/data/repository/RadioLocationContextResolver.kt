package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.RadioContextSource

/** Exact retained capture ownership. Organization membership never relaxes this scope. */
internal data class SessionContextScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
	val sessionSegmentId: Long,
	val bootId: String,
	val clockDomainId: String,
	val collectedDataEpoch: Long,
) {
	init {
		require(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		require(sessionSegmentId > 0L && bootId.isNotBlank() && clockDomainId.isNotBlank())
		require(collectedDataEpoch >= 0L)
	}
}

/** Both ambient purposes must independently qualify; their consent epoch numbers need not match. */
internal data class AmbientContextScope(
	val bootId: String,
	val clockDomainId: String,
	val collectedDataEpoch: Long,
) {
	init {
		require(bootId.isNotBlank() && clockDomainId.isNotBlank() && collectedDataEpoch >= 0L)
	}
}

internal sealed interface RadioContextScope {
	data class Session(val owner: SessionContextScope) : RadioContextScope
	data class Ambient(val owner: AmbientContextScope) : RadioContextScope
}

/** Qualified retained radio evidence. Its reference is not itself read/acquisition authority. */
internal data class RadioContextInput(
	val source: RadioContextSource,
	val sourceEventId: String,
	val scope: RadioContextScope,
	val observedElapsedNanos: Long,
	val observedWallTimeMs: Long,
	val eligibleForMap: Boolean,
	val observedFromElapsedNanos: Long = observedElapsedNanos,
	val wallTimeUncertaintyMs: Long = 0L,
) {
	init {
		require(source == RadioContextSource.WIFI || source == RadioContextSource.CELL)
		require(sourceEventId.isNotBlank() && observedWallTimeMs >= 0L)
		require(observedFromElapsedNanos >= 0L && observedElapsedNanos >= observedFromElapsedNanos)
		require(wallTimeUncertaintyMs >= 0L)
	}
}

/** Qualified, retained canonical endpoint in its independently authorized capture/ambient purpose. */
internal data class LocationContextInput(
	val sourceEventId: String,
	val scope: RadioContextScope,
	val observedElapsedNanos: Long,
	val latitudeE7: Int,
	val longitudeE7: Int,
	val horizontalAccuracyMeters: Float,
	val eligibleForContext: Boolean,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long = 0L,
) {
	init {
		require(sourceEventId.isNotBlank() && observedElapsedNanos >= 0L && observedWallTimeMs >= 0L)
		require(latitudeE7 in -900_000_000..900_000_000)
		require(longitudeE7 in -1_800_000_000..1_800_000_000)
		require(wallTimeUncertaintyMs >= 0L)
	}
}

/**
 * An actual observed Location endpoint used as optional temporal context, never an AP/tower estimate
 * or an interpolated device position at the radio time. Both wall-time uncertainties are retained.
 */
internal data class RadioLocationContext(
	val source: RadioContextSource,
	val scope: RadioContextScope,
	val radioSourceEventId: String,
	val locationSourceEventId: String,
	val latitude: Double,
	val longitude: Double,
	val observedWallTimeMs: Long,
	val locationObservedWallTimeMs: Long,
	val radioWallTimeUncertaintyMs: Long,
	val locationWallTimeUncertaintyMs: Long,
	val separationMillis: Long,
	val horizontalAccuracyMeters: Float,
)

/**
 * Read-derived context only. Callers must authenticate both retained inputs in one bounded snapshot;
 * no provider, broker, materialization or acquisition operation is available here.
 *
 * Wi-Fi needs an exact instant or two qualified endpoints bracketing its entire observation interval
 * within 30 seconds; use the nearest observed endpoint, earlier on a tie. Cell needs a preceding or
 * equal qualified endpoint within 60 seconds of the entire interval. No interpolation, added accuracy
 * cutoff, purpose borrowing, cross-boot matching or wall-time-only join is permitted.
 */
internal object RadioLocationContextResolver {
	private const val WIFI_MAX_SEPARATION_NANOS = 30_000_000_000L
	private const val CELL_MAX_SEPARATION_NANOS = 60_000_000_000L
	private const val NANOS_PER_MILLISECOND = 1_000_000L
	private const val E7_DIVISOR = 10_000_000.0

	fun resolve(
		radio: List<RadioContextInput>,
		locations: List<LocationContextInput>,
	): List<RadioLocationContext> {
		val byScope = locations.asSequence()
			.filter { it.eligibleForContext && it.horizontalAccuracyMeters.isFinite() &&
				it.horizontalAccuracyMeters >= 0f }
			.groupBy(LocationContextInput::scope)
			.mapValues { (_, fixes) -> fixes.sortedWith(
				compareBy<LocationContextInput> { it.observedElapsedNanos }.thenBy { it.sourceEventId },
			) }
		return radio.mapNotNull { fact ->
			if (!fact.eligibleForMap) return@mapNotNull null
			val fixes = byScope[fact.scope].orEmpty()
			if (fixes.isEmpty()) return@mapNotNull null
			val previousIndex = upperBound(fixes, fact.observedFromElapsedNanos) - 1
			val previous = endpointAt(fixes, previousIndex) ?: return@mapNotNull null
			val previousDistance = fact.observedElapsedNanos - previous.observedElapsedNanos
			val chosen = when (fact.source) {
				RadioContextSource.CELL -> previous.takeIf { previousDistance <= CELL_MAX_SEPARATION_NANOS }
				RadioContextSource.WIFI -> {
					val next = endpointAt(fixes, lowerBound(fixes, fact.observedElapsedNanos))
						?: return@mapNotNull null
					val nextDistance = next.observedElapsedNanos - fact.observedFromElapsedNanos
					if (previousDistance > WIFI_MAX_SEPARATION_NANOS ||
						nextDistance > WIFI_MAX_SEPARATION_NANOS
					) null else if (previousDistance <= nextDistance) previous else next
				}
			} ?: return@mapNotNull null
			val separationNanos = if (chosen.observedElapsedNanos <= fact.observedFromElapsedNanos) {
				fact.observedElapsedNanos - chosen.observedElapsedNanos
			} else chosen.observedElapsedNanos - fact.observedFromElapsedNanos
			RadioLocationContext(
				source = fact.source,
				scope = fact.scope,
				radioSourceEventId = fact.sourceEventId,
				locationSourceEventId = chosen.sourceEventId,
				latitude = chosen.latitudeE7 / E7_DIVISOR,
				longitude = chosen.longitudeE7 / E7_DIVISOR,
				observedWallTimeMs = fact.observedWallTimeMs,
				locationObservedWallTimeMs = chosen.observedWallTimeMs,
				radioWallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
				locationWallTimeUncertaintyMs = chosen.wallTimeUncertaintyMs,
				separationMillis = separationNanos / NANOS_PER_MILLISECOND +
					if (separationNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L,
				horizontalAccuracyMeters = chosen.horizontalAccuracyMeters,
			)
		}
	}

	/** First endpoint >= time. Stable source identity ordering selects equal-time endpoints. */
	private fun lowerBound(fixes: List<LocationContextInput>, time: Long): Int {
		var low = 0
		var high = fixes.size
		while (low < high) {
			val middle = low + (high - low) / 2
			if (fixes[middle].observedElapsedNanos < time) low = middle + 1 else high = middle
		}
		return low
	}

	private fun upperBound(fixes: List<LocationContextInput>, time: Long): Int {
		var low = 0
		var high = fixes.size
		while (low < high) {
			val middle = low + (high - low) / 2
			if (fixes[middle].observedElapsedNanos <= time) low = middle + 1 else high = middle
		}
		return low
	}

	private fun endpointAt(fixes: List<LocationContextInput>, index: Int): LocationContextInput? {
		val endpoint = fixes.getOrNull(index) ?: return null
		return fixes[lowerBound(fixes, endpoint.observedElapsedNanos)]
	}
}
