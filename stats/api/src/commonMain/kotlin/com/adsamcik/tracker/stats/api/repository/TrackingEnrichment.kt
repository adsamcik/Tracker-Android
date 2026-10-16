package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.value.EpochMs

/**
 * Read-only access to already-collected, product-compatible facts.
 *
 * This API exposes no broker, demand, registration, provider, writer, materializer, or acquisition
 * operation. A caller receives one immutable answer or an explicit rejection.
 */
interface TrackingEnrichmentReader {
	/**
	 * Reads independently qualified saved enrichment for the exact base selection without acquiring
	 * or retaining any sensor.
	 */
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

	/**
	 * Checks the proven origin identity, consent epoch and saved structural day against this
	 * enrichment eligibility.
	 */
	fun isCompatibleWith(origin: TrackingProductOrigin): Boolean = when (origin.kind) {
		TrackingProductOriginKind.SESSION ->
			purpose == TrackingEnrichmentPurpose.SESSION_CAPTURE &&
				logicalSessionIdentity == origin.identity &&
				consentEpoch == origin.consentEpoch
		TrackingProductOriginKind.AMBIENT ->
			purpose == TrackingEnrichmentPurpose.AMBIENT_PRODUCT &&
				structuralDay == origin.structuralDay &&
				consentEpoch == origin.consentEpoch
		TrackingProductOriginKind.IMPORTED -> false
	}
}

/** Source-issued identity and observed interval for one immutable compatible fact. */
data class TrackingEnrichmentFactAuthority(
	val identity: TrackingProductIdentity,
	val observedFromInclusive: EpochMs,
	val observedToExclusive: EpochMs,
	val eligibility: TrackingEnrichmentEligibility,
) {
	val wallRange: TrackingProductQueryScope.WallRange =
		TrackingProductQueryScope.WallRange(observedFromInclusive, observedToExclusive)

	init {
		require(observedToExclusive > observedFromInclusive)
	}

	/**
	 * Returns the rejection an implementation must emit before exposing this fact.
	 *
	 * The selected authority is the only request scope; callers cannot provide a wider interval.
	 */
	fun rejectionAgainst(
		primary: TrackingProductSelectionAuthority,
	): TrackingEnrichmentRejectionReason? {
		if (!eligibility.isCompatibleWith(primary.origin)) {
			return TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY
		}
		val factScope = TrackingProductAuthorityScope(
			wallRange = wallRange,
			structuralDays = setOfNotNull(eligibility.structuralDay),
		)
		return if (primary.authorityScope.contains(factScope)) {
			null
		} else {
			TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY
		}
	}
}

/**
 * Enrichment scope derives exclusively from [primary].
 *
 * [requestedSources] is snapshotted and the primary source cannot enrich itself.
 */
class TrackingEnrichmentRequest(
	val primary: TrackingProductSelectionAuthority,
	requestedSources: Set<HistorySource>,
	val access: TrackingEnrichmentAccess =
		TrackingEnrichmentAccess.ALREADY_COLLECTED_COMPATIBLE_FACTS_ONLY,
) {
	private val requestedSourcesSnapshot = requestedSources.toSet()
	val requestedSources: Set<HistorySource>
		get() = requestedSourcesSnapshot.toSet()
	val authorityScope: TrackingProductAuthorityScope
		get() = primary.authorityScope

	init {
		require(requestedSourcesSnapshot.isNotEmpty())
		require(requestedSourcesSnapshot.size < HistorySource.entries.size)
		require(primary.source !in requestedSourcesSnapshot) {
			"A source cannot enrich itself through the cross-source contract"
		}
	}
}

/** A compatible retained Location fact; it grants no live Location subscription. */
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

/** Immutable, identity-free Wi-Fi observation snapshot with a defensively copied band map. */
@Suppress("LongParameterList")
class WifiEnrichmentObservation(
	val intervalStartTime: EpochMs,
	val observedTime: EpochMs,
	val wallTimeUncertaintyMs: Long,
	val resultCompleteness: WifiHistoryResultCompleteness,
	val observationCount: Int,
	bandMix: Map<WifiHistoryBand, Int>,
	val signalQuality: WifiHistorySignalQuality,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val storedZoneId: String,
) {
	private val bandMixSnapshot = bandMix.toMap()
	val bandMix: Map<WifiHistoryBand, Int>
		get() = bandMixSnapshot.toMap()

	init {
		require(observedTime >= intervalStartTime)
		require(wallTimeUncertaintyMs >= 0L)
		require(observationCount > 0)
		require(bandMixSnapshot.values.all { it > 0 })
		require(bandMixSnapshot.values.sum() == observationCount)
		require(signalQuality.sampleCount == observationCount)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(storedZoneId.isNotBlank())
	}
}

/**
 * Qualified Wi-Fi observation paired with the original fact authority used for optional
 * enrichment.
 */
data class WifiEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val observation: WifiEnrichmentObservation,
) {
	init {
		require(observation.intervalStartTime >= authority.observedFromInclusive)
		require(observation.observedTime < authority.observedToExclusive)
	}
}

/** Immutable, identity-free Cell observation snapshot with a defensively copied technology map. */
@Suppress("LongParameterList")
class CellEnrichmentObservation(
	val intervalStartTime: EpochMs,
	val observedTime: EpochMs,
	val wallTimeUncertaintyMs: Long,
	val childCompleteness: CellHistoryChildCompleteness,
	val observationCount: Int,
	technologyMix: Map<CellHistoryTechnology, Int>,
	val signalQuality: CellHistorySignalQuality,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val storedZoneId: String,
) {
	private val technologyMixSnapshot = technologyMix.toMap()
	val technologyMix: Map<CellHistoryTechnology, Int>
		get() = technologyMixSnapshot.toMap()

	init {
		require(observedTime >= intervalStartTime)
		require(wallTimeUncertaintyMs >= 0L)
		require(observationCount > 0)
		require(technologyMixSnapshot.values.all { it > 0 })
		require(technologyMixSnapshot.values.sum() == observationCount)
		require(signalQuality.totalCount == observationCount)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(storedZoneId.isNotBlank())
	}
}

/**
 * Qualified Cell observation paired with its original saved-fact authority.
 */
data class CellEnrichmentFact(
	val authority: TrackingEnrichmentFactAuthority,
	val observation: CellEnrichmentObservation,
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

/**
 * Count of compatible saved fixes and their newest measured time, not the time of the enrichment
 * read.
 */
data class LocationEnrichmentResultMetadata(
	val compatibleFixCount: Int,
	val newestObservedAt: EpochMs,
) {
	init {
		require(compatibleFixCount > 0)
	}
}

/**
 * Compatible Wi-Fi observation count and whether any accepted result set is partial.
 */
data class WifiEnrichmentResultMetadata(
	val compatibleObservationCount: Int,
	val hasPartialResultSet: Boolean,
) {
	init {
		require(compatibleObservationCount > 0)
	}
}

/**
 * Compatible Cell observation count and whether any represented child set is partial.
 */
data class CellEnrichmentResultMetadata(
	val compatibleObservationCount: Int,
	val hasPartialChildren: Boolean,
) {
	init {
		require(compatibleObservationCount > 0)
	}
}

/**
 * Compatible Activity band count and the retained coverage of those bands.
 */
data class ActivityEnrichmentResultMetadata(
	val compatibleBandCount: Int,
	val coverage: ActivityHistoryCoverage,
) {
	init {
		require(compatibleBandCount > 0)
		require(coverage != ActivityHistoryCoverage.NONE)
	}
}

/**
 * Whether retained aggregate windows cover the selected authority range completely, partly or with
 * a gap.
 */
enum class TrackingEnrichmentAggregateCoverage {
	COMPLETE,
	PARTIAL,
	GAP,
}

/**
 * Compatible real Steps window count with its original authority range and aggregate coverage.
 */
data class StepsEnrichmentResultMetadata(
	val compatibleWindowCount: Int,
	val authorityRange: TrackingProductQueryScope.WallRange,
	val aggregateCoverage: TrackingEnrichmentAggregateCoverage,
) {
	init {
		require(compatibleWindowCount > 0)
	}
}

/**
 * Compatible real Pressure window count with its original authority range and aggregate coverage.
 */
data class PressureEnrichmentResultMetadata(
	val compatibleWindowCount: Int,
	val authorityRange: TrackingProductQueryScope.WallRange,
	val aggregateCoverage: TrackingEnrichmentAggregateCoverage,
) {
	init {
		require(compatibleWindowCount > 0)
	}
}

/**
 * Nonempty half-open measured UTC millisecond interval eligible for optional enrichment.
 */
data class TrackingEnrichmentInterval(
	val fromInclusive: EpochMs,
	val toExclusive: EpochMs,
) {
	init {
		require(toExclusive > fromInclusive)
	}
}

/**
 * Bounded normalized interval union or a rejection that prevents selecting enrichment data.
 */
sealed interface TrackingEnrichmentIntervalUnionResult {
	/**
	 * Computed aggregate coverage for the bounded measured interval union.
	 */
	data class Covered(
		val coverage: TrackingEnrichmentAggregateCoverage,
	) : TrackingEnrichmentIntervalUnionResult

	/**
	 * Some selected measured interval falls outside the requested source authority.
	 */
	data object OutsideAuthority : TrackingEnrichmentIntervalUnionResult
	/**
	 * The measured interval union exceeds the finite permitted interval count.
	 */
	data object Overflow : TrackingEnrichmentIntervalUnionResult
}

/**
 * Computes a checked, sorted interval union against the entire authority range.
 *
 * COMPLETE requires continuous coverage from the exact start through the exact end. A contiguous
 * proper subset is PARTIAL; any internal hole is GAP. Overlapping and exactly adjacent intervals
 * merge into one covered interval. All bounds are epoch milliseconds.
 */
fun trackingEnrichmentIntervalUnionCoverage(
	authorityRange: TrackingProductQueryScope.WallRange,
	intervals: List<TrackingEnrichmentInterval>,
): TrackingEnrichmentIntervalUnionResult {
	val ordered = intervals.toList().sortedWith(
		compareBy(TrackingEnrichmentInterval::fromInclusive)
			.thenBy(TrackingEnrichmentInterval::toExclusive),
	)
	if (ordered.isEmpty()) {
		return TrackingEnrichmentIntervalUnionResult.Covered(
			TrackingEnrichmentAggregateCoverage.PARTIAL,
		)
	}
	if (ordered.any { interval ->
			interval.fromInclusive < authorityRange.fromInclusive ||
				interval.toExclusive > authorityRange.toExclusive
		}) {
		return TrackingEnrichmentIntervalUnionResult.OutsideAuthority
	}

	return orderedEnrichmentIntervalUnionCoverage(authorityRange, ordered)
}

/** Scans nonempty, ordered intervals already checked against the authority range. */
private fun orderedEnrichmentIntervalUnionCoverage(
	authorityRange: TrackingProductQueryScope.WallRange,
	ordered: List<TrackingEnrichmentInterval>,
): TrackingEnrichmentIntervalUnionResult {
	var mergedStart = ordered.first().fromInclusive.raw
	var mergedEnd = ordered.first().toExclusive.raw
	var coveredDuration = 0L
	var hasInternalGap = false
	for (interval in ordered.drop(1)) {
		if (interval.fromInclusive.raw > mergedEnd) {
			hasInternalGap = true
			val duration = mergedEnd - mergedStart
			if (Long.MAX_VALUE - coveredDuration < duration) {
				return TrackingEnrichmentIntervalUnionResult.Overflow
			}
			coveredDuration += duration
			mergedStart = interval.fromInclusive.raw
			mergedEnd = interval.toExclusive.raw
		} else if (interval.toExclusive.raw > mergedEnd) {
			mergedEnd = interval.toExclusive.raw
		}
	}
	val lastDuration = mergedEnd - mergedStart
	if (Long.MAX_VALUE - coveredDuration < lastDuration) {
		return TrackingEnrichmentIntervalUnionResult.Overflow
	}
	coveredDuration += lastDuration
	return enrichmentIntervalCoverage(authorityRange, ordered.first(), mergedEnd, coveredDuration, hasInternalGap)
}

private fun enrichmentIntervalCoverage(
	authorityRange: TrackingProductQueryScope.WallRange,
	firstInterval: TrackingEnrichmentInterval,
	mergedEnd: Long,
	coveredDuration: Long,
	hasInternalGap: Boolean,
): TrackingEnrichmentIntervalUnionResult.Covered {
	val authorityDuration =
		authorityRange.toExclusive.raw - authorityRange.fromInclusive.raw
	val complete =
		!hasInternalGap &&
			firstInterval.fromInclusive == authorityRange.fromInclusive &&
			mergedEnd == authorityRange.toExclusive.raw &&
			coveredDuration == authorityDuration
	val coverage = when {
		complete -> TrackingEnrichmentAggregateCoverage.COMPLETE
		hasInternalGap -> TrackingEnrichmentAggregateCoverage.GAP
		else -> TrackingEnrichmentAggregateCoverage.PARTIAL
	}
	return TrackingEnrichmentIntervalUnionResult.Covered(coverage)
}

/**
 * Source-specific enrichment answers.
 *
 * There is no universal payload, materializer, or deletion-marker language.
 */
sealed interface TrackingEnrichmentSourceResult {
	val source: HistorySource

	/**
	 * Snapshot of compatible Location facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Location(
		facts: List<LocationEnrichmentFact>,
		val metadata: LocationEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<LocationEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.LOCATION

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(LocationEnrichmentFact::authority))
			require(metadata.compatibleFixCount == factsSnapshot.size)
			require(metadata.newestObservedAt.raw == factsSnapshot.maxOf { it.location.time })
		}
	}

	/**
	 * Snapshot of compatible Wi-Fi facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Wifi(
		facts: List<WifiEnrichmentFact>,
		val metadata: WifiEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<WifiEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.WIFI

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(WifiEnrichmentFact::authority))
			require(metadata.compatibleObservationCount == factsSnapshot.size)
			require(
				metadata.hasPartialResultSet ==
					factsSnapshot.any {
						it.observation.resultCompleteness ==
							WifiHistoryResultCompleteness.PARTIAL
					},
			)
		}
	}

	/**
	 * Snapshot of compatible Cell facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Cell(
		facts: List<CellEnrichmentFact>,
		val metadata: CellEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<CellEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.CELL

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(CellEnrichmentFact::authority))
			require(metadata.compatibleObservationCount == factsSnapshot.size)
			require(
				metadata.hasPartialChildren ==
					factsSnapshot.any {
						it.observation.childCompleteness ==
							CellHistoryChildCompleteness.PARTIAL
					},
			)
		}
	}

	/**
	 * Snapshot of compatible Activity facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Activity(
		facts: List<ActivityEnrichmentFact>,
		val metadata: ActivityEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<ActivityEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.ACTIVITY

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(ActivityEnrichmentFact::authority))
			require(metadata.compatibleBandCount == factsSnapshot.size)
		}
	}

	/**
	 * Snapshot of compatible Steps facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Steps private constructor(
		facts: List<StepsEnrichmentFact>,
		val metadata: StepsEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<StepsEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.STEPS

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(StepsEnrichmentFact::authority))
			require(metadata.compatibleWindowCount == factsSnapshot.size)
		}

		/**
		 * Constructs optional Steps enrichment only from unique valid facts and the verified measured
		 * interval union.
		 */
		companion object {
			/**
			 * Snapshots unique Steps facts and checks their bounded interval-union coverage against
			 * metadata before exposing enrichment.
			 */
			fun create(
				facts: List<StepsEnrichmentFact>,
				metadata: StepsEnrichmentResultMetadata,
			): TrackingEnrichmentSourceResult {
				val factSnapshot = facts.toList()
				if (factSnapshot.isEmpty() ||
					factSnapshot.map { it.authority.identity }.distinct().size !=
					factSnapshot.size
				) {
					return TrackingEnrichmentSourceResult.Rejected(
						HistorySource.STEPS,
						TrackingEnrichmentRejectionReason.CONFLICT,
					)
				}
				val union = trackingEnrichmentIntervalUnionCoverage(
					metadata.authorityRange,
					factSnapshot.map { fact ->
						TrackingEnrichmentInterval(
							fact.authority.observedFromInclusive,
							fact.authority.observedToExclusive,
						)
					},
				)
				val coverage = union.aggregateCoverageOrNull()
					?: return union.rejectionResult(HistorySource.STEPS)
				val qualifiedCoverage =
					if (
						coverage == TrackingEnrichmentAggregateCoverage.COMPLETE &&
						factSnapshot.any { it.coverage != StepsHistoryCoverage.COMPLETE }
					) {
						TrackingEnrichmentAggregateCoverage.PARTIAL
					} else {
						coverage
					}
				if (
					metadata.compatibleWindowCount != factSnapshot.size ||
					metadata.aggregateCoverage != qualifiedCoverage
				) {
					return TrackingEnrichmentSourceResult.Rejected(
						HistorySource.STEPS,
						TrackingEnrichmentRejectionReason.CONFLICT,
					)
				}
				return Steps(factSnapshot, metadata)
			}
		}
	}

	/**
	 * Snapshot of compatible Pressure facts and source-specific coverage metadata; it never activates
	 * collection.
	 */
	class Pressure private constructor(
		facts: List<PressureEnrichmentFact>,
		val metadata: PressureEnrichmentResultMetadata,
	) : TrackingEnrichmentSourceResult {
		private val factsSnapshot = facts.toList()
		val facts: List<PressureEnrichmentFact>
			get() = factsSnapshot.toList()
		override val source: HistorySource = HistorySource.PRESSURE

		init {
			require(factsSnapshot.isNotEmpty())
			require(factsSnapshot.haveDistinctAuthorities(PressureEnrichmentFact::authority))
			require(metadata.compatibleWindowCount == factsSnapshot.size)
		}

		/**
		 * Constructs optional Pressure enrichment only from valid windows and the verified measured
		 * interval union.
		 */
		companion object {
			/**
			 * Snapshots valid unique Pressure windows and checks their bounded interval-union coverage
			 * against metadata before exposing enrichment.
			 */
			fun create(
				facts: List<PressureEnrichmentFact>,
				metadata: PressureEnrichmentResultMetadata,
			): TrackingEnrichmentSourceResult {
				val factSnapshot = facts.toList()
				if (factSnapshot.isEmpty() ||
					factSnapshot.any {
						it.window.intervalEndTime <= it.window.intervalStartTime
					}
				) {
					return TrackingEnrichmentSourceResult.Rejected(
						HistorySource.PRESSURE,
						TrackingEnrichmentRejectionReason.INVALID_INTERVAL,
					)
				}
				if (factSnapshot.map { it.authority.identity }.distinct().size !=
					factSnapshot.size
				) {
					return TrackingEnrichmentSourceResult.Rejected(
						HistorySource.PRESSURE,
						TrackingEnrichmentRejectionReason.CONFLICT,
					)
				}
				val union = trackingEnrichmentIntervalUnionCoverage(
					metadata.authorityRange,
					factSnapshot.map { fact ->
						TrackingEnrichmentInterval(
							fact.window.intervalStartTime,
							fact.window.intervalEndTime,
						)
					},
				)
				val coverage = union.aggregateCoverageOrNull()
					?: return union.rejectionResult(HistorySource.PRESSURE)
				val qualifiedCoverage =
					if (
						coverage == TrackingEnrichmentAggregateCoverage.COMPLETE &&
						factSnapshot.any {
							it.window.qualification != PressureWindowQualification.COMPLETE
						}
					) {
						TrackingEnrichmentAggregateCoverage.PARTIAL
					} else {
						coverage
					}
				return if (
					metadata.compatibleWindowCount != factSnapshot.size ||
					metadata.aggregateCoverage != qualifiedCoverage
				) {
					TrackingEnrichmentSourceResult.Rejected(
						HistorySource.PRESSURE,
						TrackingEnrichmentRejectionReason.CONFLICT,
					)
				} else {
					Pressure(factSnapshot, metadata)
				}
			}
		}
	}

	/**
	 * The optional source has no independently qualified compatible saved facts for this selection.
	 */
	data class NoCompatibleFacts(
		override val source: HistorySource,
	) : TrackingEnrichmentSourceResult

	/**
	 * Optional enrichment was rejected for the named authority, conflict or interval reason.
	 */
	data class Rejected(
		override val source: HistorySource,
		val reason: TrackingEnrichmentRejectionReason,
	) : TrackingEnrichmentSourceResult
}

/**
 * Authority, conflict, acquisition or interval problems that reject optional enrichment without
 * invalidating the base data.
 */
enum class TrackingEnrichmentRejectionReason {
	OUTSIDE_AUTHORITY,
	CONFLICT,
	ACQUISITION_REQUIRED,
	INTERVAL_OVERFLOW,
	INVALID_INTERVAL,
}

/** One immutable enrichment answer bound to the primary selection's exact snapshot and scope. */
class TrackingEnrichmentSnapshot(
	val request: TrackingEnrichmentRequest,
	val readSnapshot: TrackingProductReadSnapshot,
	results: List<TrackingEnrichmentSourceResult>,
) {
	private val resultsSnapshot = results.toList()
	val results: List<TrackingEnrichmentSourceResult>
		get() = resultsSnapshot.toList()

	init {
		require(readSnapshot == request.primary.readSnapshot)
		require(
			resultsSnapshot.map(TrackingEnrichmentSourceResult::source).distinct().size ==
				resultsSnapshot.size,
		)
		require(
			resultsSnapshot.mapTo(linkedSetOf(), TrackingEnrichmentSourceResult::source) ==
				request.requestedSources,
		)
		resultsSnapshot
			.flatMap(TrackingEnrichmentSourceResult::factAuthorities)
			.forEach { authority ->
				require(authority.rejectionAgainst(request.primary) == null) {
					"Outside-authority facts must be returned as OUTSIDE_AUTHORITY"
				}
			}
		resultsSnapshot.forEach { result ->
			when (result) {
				is TrackingEnrichmentSourceResult.Steps ->
					require(result.metadata.authorityRange == request.authorityScope.wallRange)
				is TrackingEnrichmentSourceResult.Pressure ->
					require(result.metadata.authorityRange == request.authorityScope.wallRange)
				else -> Unit
			}
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

private fun TrackingEnrichmentIntervalUnionResult.aggregateCoverageOrNull(): TrackingEnrichmentAggregateCoverage? =
	(this as? TrackingEnrichmentIntervalUnionResult.Covered)?.coverage

private fun TrackingEnrichmentIntervalUnionResult.rejectionResult(
	source: HistorySource,
): TrackingEnrichmentSourceResult.Rejected = when (this) {
	TrackingEnrichmentIntervalUnionResult.OutsideAuthority ->
		TrackingEnrichmentSourceResult.Rejected(
			source,
			TrackingEnrichmentRejectionReason.OUTSIDE_AUTHORITY,
		)
	TrackingEnrichmentIntervalUnionResult.Overflow ->
		TrackingEnrichmentSourceResult.Rejected(
			source,
			TrackingEnrichmentRejectionReason.INTERVAL_OVERFLOW,
		)
	is TrackingEnrichmentIntervalUnionResult.Covered ->
		TrackingEnrichmentSourceResult.Rejected(
			source,
			TrackingEnrichmentRejectionReason.CONFLICT,
		)
}
