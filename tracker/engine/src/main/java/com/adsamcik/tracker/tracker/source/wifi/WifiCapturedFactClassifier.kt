package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence

internal enum class WifiObservationOrigin {
	PROVIDER_RESULTS_CALLBACK,
	CACHE_READ,
	TIMER,
	REQUEST_ATTEMPT,
}

internal enum class WifiProviderOutcome {
	RESULTS_UPDATED,
	RESULTS_NOT_UPDATED,
	ABSENT,
	FAILED,
	PERMISSION_LIMITED,
	OS_THROTTLED,
}

/**
 * Explicit provider-bound proof required before an empty callback can become a captured zero.
 * The future source adapter must supply the same durable delivery and all observed-time fields;
 * receipt time alone is not a provider observation.
 */
internal data class WifiConfirmedEmptyProviderProof private constructor(
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val clockDomainId: String,
	val providerObservationElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
) {
	init {
		require(clockDomainId.isNotBlank())
		require(providerObservationElapsedRealtimeNanos >= 0L)
		require(receivedElapsedRealtimeNanos >= providerObservationElapsedRealtimeNanos)
		require(observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs >= 0L)
	}

	companion object {
		/** Trust boundary for a source adapter with real provider-native empty-result evidence. */
		fun fromProviderCallback(
			sourceDeliveryIdentity: SourceDeliveryIdentity,
			clockDomainId: String,
			providerObservationElapsedRealtimeNanos: Long,
			receivedElapsedRealtimeNanos: Long,
			observedWallTimeMs: Long,
			wallTimeUncertaintyMs: Long,
		) = WifiConfirmedEmptyProviderProof(
			sourceDeliveryIdentity = sourceDeliveryIdentity,
			clockDomainId = clockDomainId,
			providerObservationElapsedRealtimeNanos = providerObservationElapsedRealtimeNanos,
			receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
			observedWallTimeMs = observedWallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		)
	}
}

/** Source-boundary input. Receipt time is never substituted for provider observation time. */
internal data class WifiObservationInput(
	val origin: WifiObservationOrigin,
	val outcome: WifiProviderOutcome,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val clockDomainId: String?,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long?,
	val wallTimeUncertaintyMs: Long?,
	val confirmedEmptyProof: WifiConfirmedEmptyProviderProof?,
	val accessPoints: List<WifiAccessPointEvidence>,
) {
	init {
		require(receivedElapsedRealtimeNanos >= 0L)
		require(observedWallTimeMs == null || observedWallTimeMs >= 0L)
		require(wallTimeUncertaintyMs == null || wallTimeUncertaintyMs >= 0L)
	}
}

internal enum class WifiFactRejection {
	IDENTITY_BEARING_INPUT,
	DELIVERY_IDENTITY_UNVERIFIABLE,
	DELIVERY_IDENTITY_COLLISION,
	INVALID_CORRECTION_BASE,
	CONFIRMED_EMPTY_PROOF_MISMATCH,
	TOO_MANY_ACCESS_POINTS,
	MALFORMED_ACCESS_POINT,
}

internal sealed interface WifiCapturedFactClassification {
	data class FreshChanged(val fact: WifiCapturedFact.Aggregate) : WifiCapturedFactClassification
	data class FreshUnchanged(val fact: WifiCapturedFact.CoverageOnly) : WifiCapturedFactClassification
	data class FreshConfirmedEmpty(val fact: WifiCapturedFact.CoverageOnly) : WifiCapturedFactClassification
	data object Absent : WifiCapturedFactClassification
	data object Stale : WifiCapturedFactClassification
	data object Failed : WifiCapturedFactClassification
	data object PermissionLimited : WifiCapturedFactClassification
	data object OsThrottled : WifiCapturedFactClassification
	data object ClockUnverifiable : WifiCapturedFactClassification
	data class Rejected(val reason: WifiFactRejection) : WifiCapturedFactClassification
}

/** Deterministic, identity-free product classification before Wi-Fi fact persistence. */
internal object WifiCapturedFactClassifier {
	fun classify(
		input: WifiObservationInput,
		authority: WifiCaptureAuthority,
		semanticRevision: Long = 1L,
		supersedesSemanticRevision: Long? = null,
		priorFact: WifiReusableFact? = null,
		correctionBase: WifiReusableFact? = null,
	): WifiCapturedFactClassification {
		when (input.outcome) {
			WifiProviderOutcome.ABSENT -> return WifiCapturedFactClassification.Absent
			WifiProviderOutcome.FAILED,
			WifiProviderOutcome.RESULTS_NOT_UPDATED,
			-> return WifiCapturedFactClassification.Failed
			WifiProviderOutcome.PERMISSION_LIMITED ->
				return WifiCapturedFactClassification.PermissionLimited
			WifiProviderOutcome.OS_THROTTLED -> return WifiCapturedFactClassification.OsThrottled
			WifiProviderOutcome.RESULTS_UPDATED -> Unit
		}
		if (input.origin != WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK) {
			return WifiCapturedFactClassification.Absent
		}
		if (input.clockDomainId != authority.clockDomainId) {
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (input.accessPoints.size >
			authority.acquisitionConfiguration.maximumAccessPointCount
		) {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.TOO_MANY_ACCESS_POINTS,
			)
		}
		if (input.accessPoints.any { it.identifierToken.isNotEmpty() }) {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.IDENTITY_BEARING_INPUT,
			)
		}
		val deliveryIdentity = input.sourceDeliveryIdentity
			?: return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE,
			)
		val identity = authority.factIdentity(deliveryIdentity)
		val mutation = runCatching {
			WifiCapturedFactMutation(identity, semanticRevision, supersedesSemanticRevision)
		}.getOrElse {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.INVALID_CORRECTION_BASE,
			)
		}
		if (!hasValidCorrectionBase(mutation, authority, correctionBase)) {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.INVALID_CORRECTION_BASE,
			)
		}
		val wallTimeMs = input.observedWallTimeMs
			?: return WifiCapturedFactClassification.ClockUnverifiable
		val wallTimeUncertaintyMs = input.wallTimeUncertaintyMs
			?: return WifiCapturedFactClassification.ClockUnverifiable

		if (input.accessPoints.isEmpty()) {
			return classifyEmpty(
				input,
				authority,
				mutation,
				wallTimeMs,
				wallTimeUncertaintyMs,
				priorFact,
				correctionBase,
			)
		}
		if (input.confirmedEmptyProof != null) {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.CONFIRMED_EMPTY_PROOF_MISMATCH,
			)
		}

		val itemClassifications = input.accessPoints.map { accessPoint ->
			accessPoint to if (authority.acquisitionConfiguration.accepts(
					accessPoint.frequencyMhz,
					accessPoint.signalLevelDbm,
				)
			) {
				authority.classifyProviderTime(
					accessPoint.providerTimestampNanos,
					input.receivedElapsedRealtimeNanos,
				)
			} else {
				ProviderTimeClassification.MALFORMED
			}
		}
		val accepted = itemClassifications.filter { it.second == ProviderTimeClassification.FRESH }
			.map(Pair<WifiAccessPointEvidence, ProviderTimeClassification>::first)
		val staleCount = itemClassifications.count { it.second == ProviderTimeClassification.STALE }
		val unverifiableCount = itemClassifications.count {
			it.second == ProviderTimeClassification.CLOCK_UNVERIFIABLE
		}
		val malformedCount = itemClassifications.count {
			it.second == ProviderTimeClassification.MALFORMED
		}
		if (accepted.isEmpty()) {
			return when {
				malformedCount > 0 -> WifiCapturedFactClassification.Rejected(
					WifiFactRejection.MALFORMED_ACCESS_POINT,
				)
				unverifiableCount > 0 -> WifiCapturedFactClassification.ClockUnverifiable
				else -> WifiCapturedFactClassification.Stale
			}
		}

		val providerTimes = accepted.map { requireNotNull(it.providerTimestampNanos) }
		val coverage = WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = requireNotNull(providerTimes.minOrNull()),
			providerIntervalEndElapsedRealtimeNanos = requireNotNull(providerTimes.maxOrNull()),
			receivedElapsedRealtimeNanos = input.receivedElapsedRealtimeNanos,
			submittedResultCount = input.accessPoints.size,
			acceptedResultCount = accepted.size,
			staleResultCount = staleCount,
			clockUnverifiableResultCount = unverifiableCount,
			malformedResultCount = malformedCount,
			completeness = if (
				staleCount == 0 && unverifiableCount == 0 && malformedCount == 0
			) {
				WifiCoverageCompleteness.COMPLETE
			} else {
				WifiCoverageCompleteness.PARTIAL
			},
		)
		val aggregate = identityFreeAggregate(accepted)
		val productEffect = WifiCapturedProductEffect(
			observedWallTimeMs = wallTimeMs,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			availability = WifiAvailability.AVAILABLE,
			coverage = coverage,
			aggregate = aggregate,
		)
		if (semanticRevision == 1L && priorFact?.reference?.identity == identity) {
			return if (priorFact.authority == authority &&
				priorFact.productEffect == productEffect
			) {
				WifiCapturedFactClassification.Absent
			} else {
				WifiCapturedFactClassification.Rejected(
					WifiFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			}
		}
		if (correctionBase?.productEffect == productEffect) {
			return WifiCapturedFactClassification.Absent
		}
		val directAggregateOwner = (priorFact as? WifiReusableFact.DirectAggregateOwner)
			?.takeIf { prior ->
			prior.reference.identity != identity &&
				prior.authority == authority &&
				prior.productEffect.aggregate == aggregate
			} ?: (correctionBase as? WifiReusableFact.DirectAggregateOwner)
			?.takeIf { base ->
				base.authority == authority && base.productEffect.aggregate == aggregate
			}
		return if (directAggregateOwner != null) {
			WifiCapturedFactClassification.FreshUnchanged(
				WifiCapturedFact.CoverageOnly(
					mutation = mutation,
					authority = authority,
					observedWallTimeMs = wallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = WifiAvailability.AVAILABLE,
					coverage = coverage,
					reusesAggregate = directAggregateOwner,
				),
			)
		} else {
			WifiCapturedFactClassification.FreshChanged(
				WifiCapturedFact.Aggregate(
					mutation = mutation,
					authority = authority,
					observedWallTimeMs = wallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = WifiAvailability.AVAILABLE,
					coverage = coverage,
					aggregate = aggregate,
				),
			)
		}
	}

	private fun classifyEmpty(
		input: WifiObservationInput,
		authority: WifiCaptureAuthority,
		mutation: WifiCapturedFactMutation,
		wallTimeMs: Long,
		wallTimeUncertaintyMs: Long,
		priorFact: WifiReusableFact?,
		correctionBase: WifiReusableFact?,
	): WifiCapturedFactClassification {
		val deliveryIdentity = requireNotNull(input.sourceDeliveryIdentity)
		val proof = input.confirmedEmptyProof
			?: return WifiCapturedFactClassification.ClockUnverifiable
		if (
			proof.sourceDeliveryIdentity != deliveryIdentity ||
			proof.clockDomainId != input.clockDomainId ||
			proof.receivedElapsedRealtimeNanos != input.receivedElapsedRealtimeNanos ||
			proof.observedWallTimeMs != wallTimeMs ||
			proof.wallTimeUncertaintyMs != wallTimeUncertaintyMs
		) {
			return WifiCapturedFactClassification.Rejected(
				WifiFactRejection.CONFIRMED_EMPTY_PROOF_MISMATCH,
			)
		}
		val providerTime = proof.providerObservationElapsedRealtimeNanos
		return when (authority.classifyProviderTime(providerTime, input.receivedElapsedRealtimeNanos)) {
			ProviderTimeClassification.STALE -> WifiCapturedFactClassification.Stale
			ProviderTimeClassification.CLOCK_UNVERIFIABLE ->
				WifiCapturedFactClassification.ClockUnverifiable
			ProviderTimeClassification.MALFORMED -> error("Provider time is not child data")
			ProviderTimeClassification.FRESH -> {
				val coverage = WifiCoverageEvidence(
					providerIntervalStartElapsedRealtimeNanos = providerTime,
					providerIntervalEndElapsedRealtimeNanos = providerTime,
					receivedElapsedRealtimeNanos = input.receivedElapsedRealtimeNanos,
					submittedResultCount = 0,
					acceptedResultCount = 0,
					staleResultCount = 0,
					clockUnverifiableResultCount = 0,
					malformedResultCount = 0,
					completeness = WifiCoverageCompleteness.COMPLETE,
				)
				val productEffect = WifiCapturedProductEffect(
					observedWallTimeMs = wallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = WifiAvailability.CONFIRMED_EMPTY,
					coverage = coverage,
					aggregate = null,
				)
				if (
					mutation.semanticRevision == 1L &&
					priorFact?.reference?.identity == mutation.identity
				) {
					return if (
						priorFact.authority == authority &&
						priorFact.productEffect == productEffect
					) {
						WifiCapturedFactClassification.Absent
					} else {
						WifiCapturedFactClassification.Rejected(
							WifiFactRejection.DELIVERY_IDENTITY_COLLISION,
						)
					}
				}
				if (correctionBase?.productEffect == productEffect) {
					return WifiCapturedFactClassification.Absent
				}
				WifiCapturedFactClassification.FreshConfirmedEmpty(
					WifiCapturedFact.CoverageOnly(
						mutation = mutation,
						authority = authority,
						observedWallTimeMs = wallTimeMs,
						wallTimeUncertaintyMs = wallTimeUncertaintyMs,
						availability = WifiAvailability.CONFIRMED_EMPTY,
						coverage = coverage,
						reusesAggregate = null,
					),
				)
			}
		}
	}

	private fun hasValidCorrectionBase(
		mutation: WifiCapturedFactMutation,
		authority: WifiCaptureAuthority,
		correctionBase: WifiReusableFact?,
	): Boolean = when {
		mutation.semanticRevision == 1L -> correctionBase == null
		correctionBase == null -> false
		correctionBase.reference.identity != mutation.identity -> false
		correctionBase.reference.semanticRevision != mutation.supersedesSemanticRevision -> false
		correctionBase.authority != authority -> false
		else -> true
	}

	private fun WifiCaptureAuthority.factIdentity(
		deliveryIdentity: SourceDeliveryIdentity,
	) = WifiCapturedFactIdentity(
		sourceDeliveryIdentity = deliveryIdentity,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sessionSegmentId = sessionSegmentId,
		sessionManifestRevision = sessionManifestRevision,
		collectedDataEpoch = collectedDataEpoch,
	)

	private fun WifiCaptureAuthority.classifyProviderTime(
		providerTimeNanos: Long?,
		receivedElapsedRealtimeNanos: Long,
	): ProviderTimeClassification {
		if (providerTimeNanos == null || providerTimeNanos < 0L ||
			providerTimeNanos > receivedElapsedRealtimeNanos
		) return ProviderTimeClassification.CLOCK_UNVERIFIABLE
		if (!temporalAuthority.contains(providerTimeNanos)) {
			return ProviderTimeClassification.STALE
		}
		return if (
			receivedElapsedRealtimeNanos - providerTimeNanos >
				acquisitionConfiguration.maximumObservationAgeNanos
		) {
			ProviderTimeClassification.STALE
		} else {
			ProviderTimeClassification.FRESH
		}
	}

	private fun identityFreeAggregate(
		accessPoints: List<WifiAccessPointEvidence>,
	): WifiIdentityFreeAggregate {
		val bands = accessPoints.groupingBy { accessPoint ->
			wifiBand(accessPoint.frequencyMhz)
		}.eachCount()
		val signalLevels = accessPoints.map(WifiAccessPointEvidence::signalLevelDbm)
		return WifiIdentityFreeAggregate(
			observationCount = accessPoints.size,
			bandMix = WifiBandMix(
				twoPointFourGhzCount = bands[WifiBand.TWO_POINT_FOUR_GHZ] ?: 0,
				fiveGhzCount = bands[WifiBand.FIVE_GHZ] ?: 0,
				sixGhzCount = bands[WifiBand.SIX_GHZ] ?: 0,
				otherCount = bands[WifiBand.OTHER] ?: 0,
			),
			signalQuality = WifiSignalQualitySummary(
				observationCount = signalLevels.size,
				strongestSignalLevelDbm = signalLevels.max(),
				weakestSignalLevelDbm = signalLevels.min(),
				signalLevelSumDbm = signalLevels.sumOf { it.toLong() },
			),
		)
	}

	private fun wifiBand(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
		in WIFI_24_GHZ_MIN_MHZ..WIFI_24_GHZ_MAX_MHZ -> WifiBand.TWO_POINT_FOUR_GHZ
		in WIFI_5_GHZ_MIN_MHZ..WIFI_5_GHZ_MAX_MHZ -> WifiBand.FIVE_GHZ
		in WIFI_6_GHZ_MIN_MHZ..WIFI_6_GHZ_MAX_MHZ -> WifiBand.SIX_GHZ
		else -> WifiBand.OTHER
	}

	private enum class ProviderTimeClassification { FRESH, STALE, CLOCK_UNVERIFIABLE, MALFORMED }
	private enum class WifiBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

	private const val WIFI_24_GHZ_MIN_MHZ = 2_400
	private const val WIFI_24_GHZ_MAX_MHZ = 2_500
	private const val WIFI_5_GHZ_MIN_MHZ = 4_900
	private const val WIFI_5_GHZ_MAX_MHZ = 5_900
	private const val WIFI_6_GHZ_MIN_MHZ = 5_925
	private const val WIFI_6_GHZ_MAX_MHZ = 7_125
}
