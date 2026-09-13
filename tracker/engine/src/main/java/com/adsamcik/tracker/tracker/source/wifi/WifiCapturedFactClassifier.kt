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
 * Source-boundary input. [providerObservationElapsedRealtimeNanos] is used only for a provider-
 * confirmed empty result and must come from a source-native clock/identity contract. Receipt time
 * is never substituted for it.
 */
internal data class WifiObservationInput(
	val origin: WifiObservationOrigin,
	val outcome: WifiProviderOutcome,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val clockDomainId: String?,
	val providerObservationElapsedRealtimeNanos: Long?,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long?,
	val wallTimeUncertaintyMs: Long?,
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
			if (semanticRevision == 1L && priorFact?.reference?.identity == identity) {
				return if (priorFact.authority == authority &&
					priorFact.availability == WifiAvailability.CONFIRMED_EMPTY
				) {
					WifiCapturedFactClassification.Absent
				} else {
					WifiCapturedFactClassification.Rejected(
						WifiFactRejection.DELIVERY_IDENTITY_COLLISION,
					)
				}
			}
			return classifyEmpty(
				input,
				authority,
				mutation,
				wallTimeMs,
				wallTimeUncertaintyMs,
				correctionBase,
			)
		}

		val itemClassifications = input.accessPoints.map { accessPoint ->
			accessPoint to authority.classifyProviderTime(
				accessPoint.providerTimestampNanos,
				input.receivedElapsedRealtimeNanos,
			)
		}
		val accepted = itemClassifications.filter { it.second == ProviderTimeClassification.FRESH }
			.map(Pair<WifiAccessPointEvidence, ProviderTimeClassification>::first)
		val staleCount = itemClassifications.count { it.second == ProviderTimeClassification.STALE }
		val unverifiableCount = itemClassifications.count {
			it.second == ProviderTimeClassification.CLOCK_UNVERIFIABLE
		}
		if (accepted.isEmpty()) {
			return if (unverifiableCount > 0) {
				WifiCapturedFactClassification.ClockUnverifiable
			} else {
				WifiCapturedFactClassification.Stale
			}
		}

		val providerTimes = accepted.map { requireNotNull(it.providerTimestampNanos) }
		val coverage = WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = requireNotNull(providerTimes.minOrNull()),
			providerIntervalEndElapsedRealtimeNanos = requireNotNull(providerTimes.maxOrNull()),
			submittedResultCount = input.accessPoints.size,
			acceptedResultCount = accepted.size,
			staleResultCount = staleCount,
			clockUnverifiableResultCount = unverifiableCount,
			completeness = if (staleCount == 0 && unverifiableCount == 0) {
				WifiCoverageCompleteness.COMPLETE
			} else {
				WifiCoverageCompleteness.PARTIAL
			},
		)
		val aggregate = identityFreeAggregate(accepted)
		if (semanticRevision == 1L && priorFact?.reference?.identity == identity) {
			return if (priorFact.authority == authority &&
				priorFact.availability == WifiAvailability.AVAILABLE &&
				priorFact.aggregate == aggregate
			) {
				WifiCapturedFactClassification.Absent
			} else {
				WifiCapturedFactClassification.Rejected(
					WifiFactRejection.DELIVERY_IDENTITY_COLLISION,
				)
			}
		}
		if (correctionBase != null && correctionBase.availability == WifiAvailability.AVAILABLE &&
			correctionBase.aggregate == aggregate
		) {
			return WifiCapturedFactClassification.Absent
		}
		val reusablePrior = priorFact?.takeIf { prior ->
			prior.reference.identity != identity &&
				prior.authority == authority &&
				prior.availability == WifiAvailability.AVAILABLE &&
				prior.aggregate == aggregate
		}
		return if (reusablePrior != null) {
			WifiCapturedFactClassification.FreshUnchanged(
				WifiCapturedFact.CoverageOnly(
					mutation = mutation,
					authority = authority,
					observedWallTimeMs = wallTimeMs,
					wallTimeUncertaintyMs = wallTimeUncertaintyMs,
					availability = WifiAvailability.AVAILABLE,
					coverage = coverage,
					reusesAggregate = reusablePrior.reference,
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
		correctionBase: WifiReusableFact?,
	): WifiCapturedFactClassification {
		val providerTime = input.providerObservationElapsedRealtimeNanos
			?: return WifiCapturedFactClassification.ClockUnverifiable
		return when (authority.classifyProviderTime(providerTime, input.receivedElapsedRealtimeNanos)) {
			ProviderTimeClassification.STALE -> WifiCapturedFactClassification.Stale
			ProviderTimeClassification.CLOCK_UNVERIFIABLE ->
				WifiCapturedFactClassification.ClockUnverifiable
			ProviderTimeClassification.FRESH -> {
				if (correctionBase?.availability == WifiAvailability.CONFIRMED_EMPTY) {
					return WifiCapturedFactClassification.Absent
				}
				WifiCapturedFactClassification.FreshConfirmedEmpty(
					WifiCapturedFact.CoverageOnly(
						mutation = mutation,
						authority = authority,
						observedWallTimeMs = wallTimeMs,
						wallTimeUncertaintyMs = wallTimeUncertaintyMs,
						availability = WifiAvailability.CONFIRMED_EMPTY,
						coverage = WifiCoverageEvidence(
							providerIntervalStartElapsedRealtimeNanos = providerTime,
							providerIntervalEndElapsedRealtimeNanos = providerTime,
							submittedResultCount = 0,
							acceptedResultCount = 0,
							staleResultCount = 0,
							clockUnverifiableResultCount = 0,
							completeness = WifiCoverageCompleteness.COMPLETE,
						),
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
		return if (receivedElapsedRealtimeNanos - providerTimeNanos > maximumObservationAgeNanos) {
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

	private enum class ProviderTimeClassification { FRESH, STALE, CLOCK_UNVERIFIABLE }
	private enum class WifiBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

	private const val WIFI_24_GHZ_MIN_MHZ = 2_400
	private const val WIFI_24_GHZ_MAX_MHZ = 2_500
	private const val WIFI_5_GHZ_MIN_MHZ = 4_900
	private const val WIFI_5_GHZ_MAX_MHZ = 5_900
	private const val WIFI_6_GHZ_MIN_MHZ = 5_925
	private const val WIFI_6_GHZ_MAX_MHZ = 7_125
}
