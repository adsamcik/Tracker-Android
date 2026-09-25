package com.adsamcik.tracker.map.data

import kotlin.math.abs

/** Exact, retained session ownership shared by a radio fact and a canonical Location fix. */
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

/** Sessionless ambient radio evidence never borrows a session Location fix. */
internal sealed interface RadioContextScope {
	data class Session(val owner: SessionContextScope) : RadioContextScope
	data object Ambient : RadioContextScope
}

/** An identity-free, already retained radio fact; this model cannot acquire a provider. */
internal data class RadioContextInput(
	val source: GeoSource,
	val sourceEventId: String,
	val scope: RadioContextScope,
	val observedElapsedNanos: Long,
	val observedWallTimeMs: Long,
	val eligibleForMap: Boolean,
) {
	init {
		require(source == GeoSource.WIFI || source == GeoSource.CELL)
		require(sourceEventId.isNotBlank() && observedElapsedNanos >= 0L && observedWallTimeMs >= 0L)
	}
}

/** A retained, accepted canonical fix with authenticated physical-session provenance. */
internal data class LocationContextInput(
	val sourceEventId: String,
	val scope: SessionContextScope,
	val observedElapsedNanos: Long,
	val latitudeE7: Int,
	val longitudeE7: Int,
	val horizontalAccuracyMeters: Float,
	val eligibleForContext: Boolean,
) {
	init {
		require(sourceEventId.isNotBlank() && observedElapsedNanos >= 0L)
		require(latitudeE7 in -900_000_000..900_000_000)
		require(longitudeE7 in -1_800_000_000..1_800_000_000)
	}
}

/** A derived display point. Radio and Location facts remain separate immutable observations. */
internal data class RadioLocationContext(
	val radioSourceEventId: String,
	val locationSourceEventId: String,
	val latitude: Double,
	val longitude: Double,
	val observedWallTimeMs: Long,
	val separationMillis: Long,
	val horizontalAccuracyMeters: Float,
)

/**
 * Matches only accepted Location fixes from the exact same physical session, boot, clock, and data
 * epoch. It never turns context into a provider demand and never matches sessionless ambient radio.
 * Inputs must be read from authoritative retained radio facts and canonical Location receipts;
 * neither a timestamp-only join nor a prunable WAL row is sufficient provenance.
 */
internal object RadioLocationContextResolver {
	private const val MAX_SEPARATION_NANOS = 30_000_000_000L
	private const val MAX_HORIZONTAL_ACCURACY_METERS = 100f
	private const val E7_DIVISOR = 10_000_000.0

	fun resolve(
		radio: List<RadioContextInput>,
		locations: List<LocationContextInput>,
	): List<RadioLocationContext> {
		val byScope = locations.asSequence()
			.filter { it.eligibleForContext && it.horizontalAccuracyMeters.isFinite() &&
				it.horizontalAccuracyMeters in 0f..MAX_HORIZONTAL_ACCURACY_METERS }
			.groupBy(LocationContextInput::scope)
			.mapValues { (_, fixes) -> fixes.sortedWith(
				compareBy<LocationContextInput> { it.observedElapsedNanos }.thenBy { it.sourceEventId },
			) }
		return radio.mapNotNull { fact ->
			if (!fact.eligibleForMap) return@mapNotNull null
			val scope = (fact.scope as? RadioContextScope.Session)?.owner ?: return@mapNotNull null
			val fixes = byScope[scope].orEmpty()
			if (fixes.isEmpty()) return@mapNotNull null
			val insertion = fixes.binarySearchBy(fact.observedElapsedNanos) { it.observedElapsedNanos }
			val right = if (insertion >= 0) insertion else -insertion - 1
			val nearest = listOfNotNull(fixes.getOrNull(right - 1), fixes.getOrNull(right))
				.minWithOrNull(compareBy<LocationContextInput> {
					abs(it.observedElapsedNanos - fact.observedElapsedNanos)
				}.thenByDescending { it.observedElapsedNanos <= fact.observedElapsedNanos }
					.thenBy { it.sourceEventId }) ?: return@mapNotNull null
			val separationNanos = abs(nearest.observedElapsedNanos - fact.observedElapsedNanos)
			if (separationNanos > MAX_SEPARATION_NANOS) return@mapNotNull null
			RadioLocationContext(
				radioSourceEventId = fact.sourceEventId,
				locationSourceEventId = nearest.sourceEventId,
				latitude = nearest.latitudeE7 / E7_DIVISOR,
				longitude = nearest.longitudeE7 / E7_DIVISOR,
				observedWallTimeMs = fact.observedWallTimeMs,
				separationMillis = separationNanos / 1_000_000L,
				horizontalAccuracyMeters = nearest.horizontalAccuracyMeters,
			)
		}
	}
}
