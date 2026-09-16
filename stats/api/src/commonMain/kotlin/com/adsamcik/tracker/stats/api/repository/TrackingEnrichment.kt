package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Read-only access to already-collected, product-compatible facts.
 *
 * This API deliberately exposes no broker, demand, registration, provider, writer, or retry-
 * acquisition operation. A caller receives a point-in-time answer or an explicit rejection.
 */
interface TrackingEnrichmentReader {
	suspend fun read(request: TrackingEnrichmentRequest): TrackingEnrichmentSnapshot
}

/** The only acquisition posture available to enrichment. */
enum class TrackingEnrichmentAccess {
	ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY,
}

/** Product purposes eligible for optional context. Control is intentionally absent. */
enum class TrackingEnrichmentPurpose {
	SESSION_CAPTURE,
	AMBIENT_PRODUCT,
}

/** Immutable purpose, consent, and membership authority retained with one compatible fact. */
data class TrackingEnrichmentEligibility(
	val purpose: TrackingEnrichmentPurpose,
	val consentEpoch: Long,
	val logicalSessionIdentity: TrackingProductIdentity? = null,
	val structuralDay: TrackingProductStructuralDay? = null,
) {
	init {
		require(consentEpoch >= 0L)
		when (purpose) {
			TrackingEnrichmentPurpose.SESSION_CAPTURE -> {
				require(logicalSessionIdentity != null)
				require(structuralDay == null)
			}
			TrackingEnrichmentPurpose.AMBIENT_PRODUCT -> {
				require(logicalSessionIdentity == null)
				require(structuralDay != null)
			}
		}
	}

	fun isCompatibleWith(origin: TrackingProductOrigin): Boolean = when (origin) {
		is TrackingProductOrigin.Session ->
			purpose == TrackingEnrichmentPurpose.SESSION_CAPTURE &&
				logicalSessionIdentity == origin.logicalSessionIdentity &&
				consentEpoch == origin.consentEpoch
		is TrackingProductOrigin.Ambient ->
			purpose == TrackingEnrichmentPurpose.AMBIENT_PRODUCT &&
				structuralDay == origin.structuralDay &&
				consentEpoch == origin.consentEpoch
		is TrackingProductOrigin.Imported -> false
	}
}

/** Source-issued identity and observed interval for one immutable compatible fact. */
data class TrackingEnrichmentFactAuthority(
	val identity: TrackingProductIdentity,
	val observedFromInclusive: EpochMs,
	val observedToExclusive: EpochMs,
	val eligibility: TrackingEnrichmentEligibility,
) {
	init {
		require(observedToExclusive > observedFromInclusive)
	}
}

data class TrackingEnrichmentRequest(
	val primary: TrackingProductSelectionAuthority,
	val observedRange: TrackingProductQueryScope.WallRange,
	val requestedSources: Set<HistorySource>,
	val access: TrackingEnrichmentAccess =
		TrackingEnrichmentAccess.ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY,
) {
	init {
		require(requestedSources.isNotEmpty())
		require(requestedSources.size < HistorySource.entries.size)
		require(primary.source !in requestedSources) {
			"A source cannot enrich itself through the cross-source contract"
		}
	}
}

/** A compatible retained Location fact; it does not authorize a live Location subscription. */
data class LocationEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val location: Location,
) {
	init {
		require(location.time >= authority.observedFromInclusive.raw)
		require(location.time < authority.observedToExclusive.raw)
		require(location.latitude.isFinite() && location.latitude in -90.0..90.0)
		require(location.longitude.isFinite() && location.longitude in -180.0..180.0)
	}
}

/** A compatible identity-free Wi-Fi product observation. */
data class WifiEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val observation: WifiHistoryObservation,
) {
	init {
		require(observation.intervalStartTime >= authority.observedFromInclusive)
		require(observation.observedTime < authority.observedToExclusive)
	}
}

/** A compatible identity-free Cell product observation. */
data class CellEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val observation: CellHistoryObservation,
) {
	init {
		require(observation.intervalStartTime >= authority.observedFromInclusive)
		require(observation.observedTime < authority.observedToExclusive)
	}
}

/** A compatible observed Activity band. Known gaps are not enrichment facts. */
data class ActivityEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val band: ActivityHistoryFragment.Band,
) {
	init {
		require(band.startTime >= authority.observedFromInclusive)
		require(band.endTime >= band.startTime)
		require(band.endTime <= authority.observedToExclusive)
	}
}

/** A compatible Steps window; a count is present only with retained covered evidence. */
data class StepsEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val count: Long,
	val coverage: StepsHistoryCoverage,
) {
	init {
		require(count >= 0L)
		require(
			coverage == StepsHistoryCoverage.COMPLETE ||
				coverage == StepsHistoryCoverage.PARTIAL,
		)
	}
}

/** A compatible direct Pressure window; it is never reinterpreted as elevation. */
data class PressureEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val window: PressureHistoryWindow,
) {
	init {
		require(window.intervalStartTime >= authority.observedFromInclusive)
		require(window.intervalEndTime <= authority.observedToExclusive)
	}
}

data class LocationEnrichmentResultMetadata(
	val compatibleFixCount: Int,
	val newestObservedAt: EpochMs,
) {
	init {
		require(compatibleFixCount > 0)
	}
}

data class WifiEnrichmentResultMetadata(
	val compatibleObservationCount: Int,
	val hasPartialResultSet: Boolean,
) {
	init {
		require(compatibleObservationCount > 0)
	}
}

data class CellEnrichmentResultMetadata(
	val compatibleObservationCount: Int,
	val hasPartialChildren: Boolean,
) {
	init {
		require(compatibleObservationCount > 0)
	}
}

data class ActivityEnrichmentResultMetadata(
	val compatibleBandCount: Int,
	val coverage: ActivityHistoryCoverage,
) {
	init {
		require(compatibleBandCount > 0)
		require(coverage != ActivityHistoryCoverage.NONE)
	}
}

data class StepsEnrichmentResultMetadata(
	val compatibleWindowCount: Int,
	val coverage: StepsHistoryCoverage,
) {
	init {
		require(compatibleWindowCount > 0)
		require(
			coverage == StepsHistoryCoverage.COMPLETE ||
				coverage == StepsHistoryCoverage.PARTIAL,
		)
	}
}

data class PressureEnrichmentResultMetadata(
	val compatibleWindowCount: Int,
	val coverage: PressureHistoryCoverage,
) {
	init {
		require(compatibleWindowCount > 0)
		require(
			coverage == PressureHistoryCoverage.COMPLETE ||
				coverage == PressureHistoryCoverage.PARTIAL,
		)
	}
}

/**
 * Source-specific enrichment answers.
 *
 * There is no universal payload or materialization command. Each successful source retains its
 * own immutable fact and metadata shape.
 */
sealed interface TrackingEnrichmentSourceResult {
	val source: HistorySource

	data class Location(
		val facts: List<LocationEnrichmentFact>,
		val metadata: LocationEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.LOCATION

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(LocationEnrichmentFact::authority))
			require(metadata.compatibleFixCount == facts.size)
			require(metadata.newestObservedAt.raw == facts.maxOf { it.location.time })
		}
	}

	data class Wifi(
		val facts: List<WifiEnrichmentFact>,
		val metadata: WifiEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.WIFI

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(WifiEnrichmentFact::authority))
			require(metadata.compatibleObservationCount == facts.size)
			require(metadata.hasPartialResultSet ==
				facts.any {
					it.observation.resultCompleteness == WifiHistoryResultCompleteness.PARTIAL
				})
		}
	}

	data class Cell(
		val facts: List<CellEnrichmentFact>,
		val metadata: CellEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.CELL

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(CellEnrichmentFact::authority))
			require(metadata.compatibleObservationCount == facts.size)
			require(metadata.hasPartialChildren ==
				facts.any {
					it.observation.childCompleteness == CellHistoryChildCompleteness.PARTIAL
				})
		}
	}

	data class Activity(
		val facts: List<ActivityEnrichmentFact>,
		val metadata: ActivityEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.ACTIVITY

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(ActivityEnrichmentFact::authority))
			require(metadata.compatibleBandCount == facts.size)
		}
	}

	data class Steps(
		val facts: List<StepsEnrichmentFact>,
		val metadata: StepsEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.STEPS

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(StepsEnrichmentFact::authority))
			require(metadata.compatibleWindowCount == facts.size)
			val expectedCoverage = if (
				facts.all { it.coverage == StepsHistoryCoverage.COMPLETE }
			) {
				StepsHistoryCoverage.COMPLETE
			} else {
				StepsHistoryCoverage.PARTIAL
			}
			require(metadata.coverage == expectedCoverage)
		}
	}

	data class Pressure(
		val facts: List<PressureEnrichmentFact>,
		val metadata: PressureEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		override val source: HistorySource = HistorySource.PRESSURE

		init {
			require(facts.isNotEmpty())
			require(facts.haveDistinctAuthorities(PressureEnrichmentFact::authority))
			require(metadata.compatibleWindowCount == facts.size)
			val expectedCoverage = if (
				facts.all { it.window.qualification == PressureWindowQualification.COMPLETE }
			) {
				PressureHistoryCoverage.COMPLETE
			} else {
				PressureHistoryCoverage.PARTIAL
			}
			require(metadata.coverage == expectedCoverage)
		}
	}

	/** No retained fact matches the requested interval and authority. */
	data class NoCompatibleFacts(
		override val source: HistorySource,
	) : TrackingEnrichmentSourceResult

	/** The requested result cannot be produced without crossing a hard authority boundary. */
	data class Rejected(
		override val source: HistorySource,
		val reason: TrackingEnrichmentRejectionReason,
	) : TrackingEnrichmentSourceResult
}

enum class TrackingEnrichmentRejectionReason {
	OUTSIDE_AUTHORITY,
	CONFLICT,
	ACQUISITION_REQUIRED,
}

/** One immutable enrichment answer bound to the primary product selection's exact snapshot. */
data class TrackingEnrichmentSnapshot(
	val request: TrackingEnrichmentRequest,
	val readSnapshot: TrackingProductReadSnapshot,
	val results: List<TrackingEnrichmentSourceResult>,
) {
	init {
		require(readSnapshot == request.primary.readSnapshot)
		require(results.map(TrackingEnrichmentSourceResult::source).distinct().size == results.size)
		require(results.mapTo(linkedSetOf(), TrackingEnrichmentSourceResult::source) ==
			request.requestedSources)
		results.flatMap(TrackingEnrichmentSourceResult::factAuthorities).forEach { authority ->
			require(authority.eligibility.isCompatibleWith(request.primary.origin)) {
				"Incompatible facts must be returned as OUTSIDE_AUTHORITY, not exposed"
			}
			require(authority.observedFromInclusive >= request.observedRange.fromInclusive)
			require(authority.observedToExclusive <= request.observedRange.toExclusive)
		}
	}
}

private fun TrackingEnrichmentSourceResult.factAuthorities():
	List<TrackingEnrichmentFactAuthority> = when (this) {
	is TrackingEnrichmentSourceResult.Location -> facts.map(LocationEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.Wifi -> facts.map(WifiEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.Cell -> facts.map(CellEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.Activity -> facts.map(ActivityEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.Steps -> facts.map(StepsEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.Pressure -> facts.map(PressureEnrichmentFact::authority)
	is TrackingEnrichmentSourceResult.NoCompatibleFacts,
	is TrackingEnrichmentSourceResult.Rejected,
	-> emptyList()
}

private fun <T> List<T>.haveDistinctAuthorities(
	authority: (T) -> TrackingEnrichmentFactAuthority,
): Boolean = map { authority(it).identity }.distinct().size == size
