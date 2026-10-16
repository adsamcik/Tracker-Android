package com.adsamcik.tracker.stats.api.repository

/** Local retained radio context only. Imports never obtain native Location authority through this API. */
enum class RadioContextSource { WIFI, CELL }

/**
 * Independently qualified capture or all-day association of one physical radio reading.
 */
enum class RadioContextPurpose { CAPTURE, AMBIENT }

/** Existing native radio product bound; callers must report overflow rather than truncate evidence. */
const val MAX_RADIO_CONTEXT_OBSERVATIONS = 4_096

/**
 * Half-open UTC millisecond window and finite radio-observation limit for optional saved Location
 * context.
 */
data class RadioLocationContextRequest(
	val source: RadioContextSource,
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
	val limit: Int = MAX_RADIO_CONTEXT_OBSERVATIONS,
) {
	init {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(limit in 1..MAX_RADIO_CONTEXT_OBSERVATIONS)
	}
}

/** Preserve the original source's signal meaning; Cell quality bins are not inferred Wi-Fi dBm. */
sealed interface RadioContextSignalQuality {
	/**
	 * Original Wi-Fi signal summary in dBm, preserving its represented sample count.
	 */
	data class Wifi(val value: WifiHistorySignalQuality) : RadioContextSignalQuality
	/**
	 * Original Android Cell quality bins, with no inferred Wi-Fi-style dBm value.
	 */
	data class Cell(val value: CellHistorySignalQuality) : RadioContextSignalQuality
}

/** Actual observed canonical endpoint, not an inferred network position or interpolated radio fix. */
data class RetainedLocationContext(
	val sourceEventId: String,
	val latitudeE7: Int,
	val longitudeE7: Int,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val horizontalAccuracyMeters: Float,
	/** Conservative maximum elapsed separation across the radio observation interval, rounded up. */
	val separationMillis: Long,
) {
	init {
		require(sourceEventId.isNotBlank())
		require(latitudeE7 in -900_000_000..900_000_000 && longitudeE7 in -1_800_000_000..1_800_000_000)
		require(observedWallTimeMs >= 0L && wallTimeUncertaintyMs >= 0L && separationMillis >= 0L)
		require(horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0f)
	}
}

/**
 * Why a radio reading has no qualified compatible Location point; the radio reading remains usable
 * independently.
 */
enum class RadioLocationContextGap {
	NO_COMPATIBLE_LOCATION,
	LOCATION_PROVENANCE_UNVERIFIABLE,
	LOCATION_AUTHORITY_UNAVAILABLE,
	LOCATION_DEPENDENCY_LIMIT,
}

/**
 * A real qualified device Location point or the named reason no compatible context was found.
 */
sealed interface OptionalRetainedLocationContext {
	/**
	 * A compatible original device fix with its measured accuracy and radio separation.
	 */
	data class Located(val value: RetainedLocationContext) : OptionalRetainedLocationContext
	/**
	 * No compatible qualified Location is available; the radio reading remains independently usable.
	 */
	data class Unlocated(val reason: RadioLocationContextGap) : OptionalRetainedLocationContext
}

/**
 * One qualified purpose association. A dual-purpose event can appear once per purpose; it is still
 * one physical radio observation, so consumers must not count the associations as distinct scans.
 * The event reference is neither destructive nor acquisition authority.
 */
data class RadioContextObservation(
	val sourceEventId: String,
	val purpose: RadioContextPurpose,
	val intervalStartWallTimeMs: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
	val signalQuality: RadioContextSignalQuality,
	val location: OptionalRetainedLocationContext,
) {
	init {
		require(sourceEventId.isNotBlank())
		require(intervalStartWallTimeMs >= 0L && observedWallTimeMs >= intervalStartWallTimeMs)
		require(wallTimeUncertaintyMs >= 0L)
	}
}

/**
 * Source-specific dependency, acquisition, projection, authority or retention gap while reading
 * retained radio context.
 */
enum class RadioContextReadGapReason {
	DEPENDENCY_LIMIT,
	ACQUISITION_PARTIAL,
	MATERIALIZATION_BEHIND,
	SOURCE_AUTHORITY_UNAVAILABLE,
	SOURCE_PROVENANCE_UNVERIFIABLE,
	RETENTION_BOUNDARY,
}

/**
 * A read gap attached to one independent purpose, without invalidating healthy sibling purposes.
 */
data class RadioContextReadGap(val purpose: RadioContextPurpose, val reason: RadioContextReadGapReason)

/**
 * Qualified radio associations and independent gaps, a whole-request limit, or unavailable
 * storage.
 */
sealed interface RadioLocationContextReadResult {
	/** Valid independent siblings survive unavailable Location context or another purpose's gap. */
	data class Ready(
		val source: RadioContextSource,
		val observations: List<RadioContextObservation>,
		val gaps: Set<RadioContextReadGap> = emptySet(),
	) : RadioLocationContextReadResult {
		init {
			require(observations.size <= MAX_RADIO_CONTEXT_OBSERVATIONS)
			require(observations.map { it.sourceEventId to it.purpose }.distinct().size == observations.size)
			require(observations.all {
				when (source) {
					RadioContextSource.WIFI -> it.signalQuality is RadioContextSignalQuality.Wifi
					RadioContextSource.CELL -> it.signalQuality is RadioContextSignalQuality.Cell
				}
			})
		}
	}

	/** Whole-request limit; a single purpose's limit belongs in Ready.gaps when siblings survive. */
	data object DependencyLimit : RadioLocationContextReadResult
	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : RadioLocationContextReadResult
}

/**
 * Read-only paired source snapshot. Implementations qualify each radio fact and Location purpose
 * independently, authenticate retained lineage/receipts, and report bounded-read gaps explicitly.
 * Missing Location never discards qualified radio observations. Reads never start tracking, repair
 * projections, infer consent, manufacture identities, or grant local authority to imported records.
 */
interface RadioLocationContextRepository {
	/**
	 * Reads qualified radio and optional Location context from one bounded saved snapshot; missing
	 * context preserves independent readings.
	 */
	suspend fun read(request: RadioLocationContextRequest): RadioLocationContextReadResult
}
