package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import java.security.MessageDigest

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

/** Durable receipt-clock anchor. Observation wall time is derived from provider time, never input. */
internal data class WifiDurableClockEvidence(
	val clockDomainId: String,
	val observedIntervalStartElapsedRealtimeNanos: Long,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
)

/** Exact immutable admitted WAL unit from which the identity-free fact is derived. */
internal data class WifiWalObservationEvidence(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val capturedAuthority: WifiCaptureAuthority,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val capturedCollectedDataEpoch: Long,
	val clock: WifiDurableClockEvidence,
	val payloadVersion: Int,
	val payloadBytes: List<Byte>,
	val payloadChecksum: String,
)

/**
 * Provider-bound proof required before an empty callback can become a captured zero.
 *
 * The proof can only be created around one complete WAL evidence value, so it cannot be retargeted
 * to another delivery, authority, payload, or receipt clock after replay.
 */
internal data class WifiConfirmedEmptyProviderProof private constructor(
	val sourceEventId: SourceEventId,
	val sourceAdmissionOrdinal: Long,
	val walIntegrityIdentity: String,
	val payloadChecksum: String,
	val sourceDeliveryIdentity: SourceDeliveryIdentity,
	val clockDomainId: String,
	val providerObservationElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val observedWallTimeMs: Long,
	val wallTimeUncertaintyMs: Long,
) {
	companion object {
		/** Trust boundary for an adapter holding a real provider-confirmed empty callback. */
		fun fromProviderCallback(
			evidence: WifiWalObservationEvidence,
			providerObservationElapsedRealtimeNanos: Long,
		): WifiConfirmedEmptyProviderProof? {
			val deliveryIdentity = evidence.sourceDeliveryIdentity ?: return null
			return WifiConfirmedEmptyProviderProof(
				sourceEventId = evidence.sourceEventId,
				sourceAdmissionOrdinal = evidence.sourceAdmissionOrdinal,
				walIntegrityIdentity = evidence.walIntegrityIdentity,
				payloadChecksum = evidence.payloadChecksum,
				sourceDeliveryIdentity = deliveryIdentity,
				clockDomainId = evidence.clock.clockDomainId,
				providerObservationElapsedRealtimeNanos = providerObservationElapsedRealtimeNanos,
				receivedElapsedRealtimeNanos = evidence.clock.receivedElapsedRealtimeNanos,
				observedWallTimeMs = evidence.clock.observedWallTimeMs,
				wallTimeUncertaintyMs = evidence.clock.wallTimeUncertaintyMs,
			)
		}
	}
}

/** Source-boundary input. Updated results must be recovered from one immutable WAL unit. */
internal data class WifiObservationInput(
	val origin: WifiObservationOrigin,
	val outcome: WifiProviderOutcome,
	val walEvidence: WifiWalObservationEvidence?,
	val confirmedEmptyProof: WifiConfirmedEmptyProviderProof?,
)

internal enum class WifiFactRejection {
	IDENTITY_BEARING_INPUT,
	DELIVERY_IDENTITY_UNVERIFIABLE,
	DELIVERY_IDENTITY_COLLISION,
	INVALID_CORRECTION_BASE,
	RAW_EVIDENCE_CHANGED,
	CAPTURE_AUTHORITY_MISMATCH,
	DELETION_AUTHORITY_MISMATCH,
	WAL_PROVENANCE_UNVERIFIABLE,
	WAL_INTEGRITY_UNVERIFIABLE,
	PAYLOAD_INTEGRITY_UNVERIFIABLE,
	PAYLOAD_DECODE_FAILED,
	ACQUISITION_PLAN_UNVERIFIABLE,
	APPLIED_REGISTRATION_MISMATCH,
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
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()

	fun classify(
		input: WifiObservationInput,
		expectedAuthority: WifiCaptureAuthority,
		currentDeletionAuthority: WifiDeletionAuthority,
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
		val wal = input.walEvidence ?: return rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		if (wal.capturedAuthority != expectedAuthority || !wal.matchesCapturedAuthority(expectedAuthority)) {
			return rejected(WifiFactRejection.CAPTURE_AUTHORITY_MISMATCH)
		}
		if (currentDeletionAuthority.currentCollectedDataEpoch != expectedAuthority.collectedDataEpoch) {
			return rejected(WifiFactRejection.DELETION_AUTHORITY_MISMATCH)
		}
		val qualifiedPlan = qualifyAcquisitionPlan(expectedAuthority)
			?: return rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)
		if (!appliedRegistrationMatches(expectedAuthority, qualifiedPlan.plan)) {
			return rejected(WifiFactRejection.APPLIED_REGISTRATION_MISMATCH)
		}
		val payload = decodeWalPayload(wal) ?: return walPayloadRejection(wal)
		if (!wal.clock.isValidFor(expectedAuthority)) {
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (!payload.providerTimelineMatches(wal.clock)) {
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (payload.accessPoints.size >
			expectedAuthority.acquisitionConfiguration.maximumAccessPointCount
		) {
			return rejected(WifiFactRejection.TOO_MANY_ACCESS_POINTS)
		}
		if (payload.accessPoints.any { it.identifierToken.isNotEmpty() }) {
			return rejected(WifiFactRejection.IDENTITY_BEARING_INPUT)
		}
		if (payload.platformTimestampMs?.let { it < 0L } == true ||
			payload.resultAgeMs?.let { it < 0L } == true
		) {
			return rejected(WifiFactRejection.PAYLOAD_DECODE_FAILED)
		}
		val deliveryIdentity = wal.sourceDeliveryIdentity
			?: return rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
		val evidenceBinding = wal.evidenceBinding(deliveryIdentity, expectedAuthority)
		val identity = expectedAuthority.factIdentity(wal, deliveryIdentity)
		val mutation = runCatching {
			WifiCapturedFactMutation(identity, semanticRevision, supersedesSemanticRevision)
		}.getOrElse {
			return rejected(WifiFactRejection.INVALID_CORRECTION_BASE)
		}
		if (semanticRevision > 1L && correctionBase != null &&
			correctionBase.reference.identity.sourceDeliveryIdentity == deliveryIdentity &&
			(
				correctionBase.reference.identity != identity ||
				correctionBase.productEffect.evidenceBinding != evidenceBinding
			)
		) {
			return rejected(WifiFactRejection.RAW_EVIDENCE_CHANGED)
		}
		if (!hasValidCorrectionBase(mutation, expectedAuthority, correctionBase)) {
			return rejected(WifiFactRejection.INVALID_CORRECTION_BASE)
		}

		return if (payload.accessPoints.isEmpty()) {
			classifyEmpty(
				input = input,
				authority = expectedAuthority,
				deletionAuthority = currentDeletionAuthority,
				wal = wal,
				mutation = mutation,
				evidenceBinding = evidenceBinding,
				priorFact = priorFact,
				correctionBase = correctionBase,
			)
		} else {
			classifyNonEmpty(
				input = input,
				authority = expectedAuthority,
				deletionAuthority = currentDeletionAuthority,
				wal = wal,
				payload = payload,
				maximumObservationAgeNanos = qualifiedPlan.maximumObservationAgeNanos,
				mutation = mutation,
				evidenceBinding = evidenceBinding,
				priorFact = priorFact,
				correctionBase = correctionBase,
			)
		}
	}

	private fun classifyNonEmpty(
		input: WifiObservationInput,
		authority: WifiCaptureAuthority,
		deletionAuthority: WifiDeletionAuthority,
		wal: WifiWalObservationEvidence,
		payload: WifiResultSnapshotPayload,
		maximumObservationAgeNanos: Long,
		mutation: WifiCapturedFactMutation,
		evidenceBinding: WifiCapturedEvidenceBinding,
		priorFact: WifiReusableFact?,
		correctionBase: WifiReusableFact?,
	): WifiCapturedFactClassification {
		if (input.confirmedEmptyProof != null) {
			return rejected(WifiFactRejection.CONFIRMED_EMPTY_PROOF_MISMATCH)
		}
		val itemClassifications = payload.accessPoints.map { accessPoint ->
			accessPoint to if (authority.acquisitionConfiguration.accepts(
					accessPoint.frequencyMhz,
					accessPoint.signalLevelDbm,
				)
			) {
				authority.classifyProviderTime(
					accessPoint.providerTimestampNanos,
					wal.clock.receivedElapsedRealtimeNanos,
					maximumObservationAgeNanos,
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
				malformedCount > 0 -> rejected(WifiFactRejection.MALFORMED_ACCESS_POINT)
				unverifiableCount > 0 -> WifiCapturedFactClassification.ClockUnverifiable
				else -> WifiCapturedFactClassification.Stale
			}
		}

		val providerTimes = accepted.map { requireNotNull(it.providerTimestampNanos) }
		val providerStart = requireNotNull(providerTimes.minOrNull())
		val providerEnd = requireNotNull(providerTimes.maxOrNull())
		val wall = deriveObservedWall(wal.clock, providerEnd)
			?: return WifiCapturedFactClassification.ClockUnverifiable
		if (wall.crossesRetentionFloor(deletionAuthority)) {
			return WifiCapturedFactClassification.Stale
		}
		val coverage = WifiCoverageEvidence(
			providerIntervalStartElapsedRealtimeNanos = providerStart,
			providerIntervalEndElapsedRealtimeNanos = providerEnd,
			receivedElapsedRealtimeNanos = wal.clock.receivedElapsedRealtimeNanos,
			submittedResultCount = payload.accessPoints.size,
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
			evidenceBinding = evidenceBinding,
			observedWallTimeMs = wall.observedWallTimeMs,
			wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
			availability = WifiAvailability.AVAILABLE,
			coverage = coverage,
			aggregate = aggregate,
		)
		replayOrNoOp(mutation, authority, productEffect, priorFact, correctionBase)?.let {
			return it
		}
		val directAggregateOwner = (priorFact as? WifiReusableFact.DirectAggregateOwner)
			?.takeIf { prior ->
				prior.reference.identity != mutation.identity &&
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
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = wall.observedWallTimeMs,
					wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
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
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = wall.observedWallTimeMs,
					wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
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
		deletionAuthority: WifiDeletionAuthority,
		wal: WifiWalObservationEvidence,
		mutation: WifiCapturedFactMutation,
		evidenceBinding: WifiCapturedEvidenceBinding,
		priorFact: WifiReusableFact?,
		correctionBase: WifiReusableFact?,
	): WifiCapturedFactClassification {
		val proof = input.confirmedEmptyProof ?: return WifiCapturedFactClassification.ClockUnverifiable
		if (!proof.matches(wal)) {
			return rejected(WifiFactRejection.CONFIRMED_EMPTY_PROOF_MISMATCH)
		}
		val providerTime = proof.providerObservationElapsedRealtimeNanos
		val planAge = authority.acquisitionConfiguration.maximumObservationAgeNanos
		return when (authority.classifyProviderTime(
			providerTime,
			wal.clock.receivedElapsedRealtimeNanos,
			planAge,
		)) {
			ProviderTimeClassification.STALE -> WifiCapturedFactClassification.Stale
			ProviderTimeClassification.CLOCK_UNVERIFIABLE ->
				WifiCapturedFactClassification.ClockUnverifiable
			ProviderTimeClassification.MALFORMED -> error("Provider time is not child data")
			ProviderTimeClassification.FRESH -> {
				val wall = deriveObservedWall(wal.clock, providerTime)
					?: return WifiCapturedFactClassification.ClockUnverifiable
				if (wall.crossesRetentionFloor(deletionAuthority)) {
					return WifiCapturedFactClassification.Stale
				}
				val coverage = WifiCoverageEvidence(
					providerIntervalStartElapsedRealtimeNanos = providerTime,
					providerIntervalEndElapsedRealtimeNanos = providerTime,
					receivedElapsedRealtimeNanos = wal.clock.receivedElapsedRealtimeNanos,
					submittedResultCount = 0,
					acceptedResultCount = 0,
					staleResultCount = 0,
					clockUnverifiableResultCount = 0,
					malformedResultCount = 0,
					comteness = WifiCoverageCompleteness.COMPLETE,
				)
				val productEffect = WifiCapturedProductEffect(
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = wall.observedWallTimeMs,
					wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
					availability = WifiAvailability.CONFIRMED_EMPTY,
					coverage = coverage,
					aggregate = null,
				)
				replayOrNoOp(
					mutation,
					authority,
					productEffect,
					priorFact,
					correctionBase,
				)?.let { return it }
				WifiCapturedFactClassification.FreshConfirmedEmpty(
					WifiCapturedFact.CoverageOnly(
						mutation = mutation,
						authority = authority,
						evidenceBinding = evidenceBinding,
						observedWallTimeMs = wall.observedWallTimeMs,
						wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
						availability = WifiAvailability.CONFIRMED_EMPTY,
						coverage = coverage,
						reusesAggregate = null,
					),
				)
			}
		}
	}

	private fun replayOrNoOp(
		mutation: WifiCapturedFactMutation,
		authority: WifiCaptureAuthority,
		productEffect: WifiCapturedProductEffect,
		priorFact: WifiReusableFact?,
		correctionBase: WifiReusableFact?,
	): WifiCapturedFactClassification? {
		if (mutation.semanticRevision == 1L &&
			priorFact?.reference?.identity?.sourceDeliveryIdentity ==
			mutation.identity.sourceDeliveryIdentity
		) {
			return if (
				priorFact.reference.identity == mutation.identity &&
				priorFact.authority == authority &&
				priorFact.productEffect == productEffect
			) {
				WifiCapturedFactClassification.Absent
			} else {
				rejected(WifiFactRejection.DELIVERY_IDENTITY_COLLISION)
			}
		}
		return if (correctionBase?.productEffect == productEffect) {
			WifiCapturedFactClassification.Absent
		} else {
			null
		}
	}

	private fun decodeWalPayload(wal: WifiWalObservationEvidence): WifiResultSnapshotPayload? {
		if (wal.sourceAdmissionOrdinal <= 0L || wal.deliveryUnitCount <= 0 ||
			wal.deliveryUnitIndex !in 0 until wal.deliveryUnitCount
		) return null
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity) ||
			!LOWERCASE_SHA_256.matches(wal.payloadChecksum)
		) return null
		val bytes = wal.payloadBytes.toByteArray()
		if (bytes.sha256() != wal.payloadChecksum || wal.payloadVersion != WIFI_PAYLOAD_VERSION) {
			return null
		}
		return runCatching {
			payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, bytes)
				as? WifiResultSnapshotPayload
		}.getOrNull()
	}

	private fun walPayloadRejection(wal: WifiWalObservationEvidence): WifiCapturedFactClassification {
		if (wal.sourceAdmissionOrdinal <= 0L || wal.deliveryUnitCount <= 0 ||
			wal.deliveryUnitIndex !in 0 until wal.deliveryUnitCount
		) return rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity)) {
			return rejected(WifiFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
		}
		val bytes = wal.payloadBytes.toByteArray()
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) || bytes.sha256() != wal.payloadChecksum) {
			return rejected(WifiFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE)
		}
		return rejected(WifiFactRejection.PAYLOAD_DECODE_FAILED)
	}

	private fun qualifyAcquisitionPlan(authority: WifiCaptureAuthority): QualifiedWifiPlan? {
		val persisted = authority.serializedAcquisitionPlan
		if (persisted.payloadVersion != SOURCE_PLAN_PAYLOAD_VERSION ||
			!LOWERCASE_SHA_256.matches(persisted.payloadChecksum)
		) return null
		val bytes = persisted.payloadBytes.toByteArray()
		if (bytes.sha256() != persisted.payloadChecksum) return null
		val plan = runCatching { planCodec.decode(bytes) as? WifiPlan }.getOrNull() ?: return null
		if (plan.revision != authority.configurationRevision || !plan.enabled) return null
		val maximumAgeNanos = runCatching {
			Math.multiplyExact(plan.maximumAcceptableResultAgeMs, NANOS_PER_MILLISECOND)
		}.getOrNull() ?: return null
		if (maximumAgeNanos != authority.acquisitionConfiguration.maximumObservationAgeNanos) {
			return null
		}
		if (plan.physicalConfigurationFingerprint() != authority.physicalConfigurationFingerprint) {
			return null
		}
		return QualifiedWifiPlan(plan, maximumAgeNanos)
	}

	private fun appliedRegistrationMatches(authority: WifiCaptureAuthority, plan: WifiPlan): Boolean {
		val applied = authority.appliedRegistration
		return applied.desiredRevision == plan.revision &&
			applied.appliedRevision == plan.revision &&
			applied.sourceInstanceId == authority.sourceInstanceId &&
			applied.registrationGeneration == authority.registrationGeneration &&
			applied.appliedAtElapsedRealtimeNanos != null &&
			applied.appliedAtElapsedRealtimeNanos >= 0L &&
			applied.appliedAtElapsedRealtimeNanos <=
				authority.temporalAuthority.capturedStartInclusiveNanos &&
			applied.physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
			applied.clockDomainId == authority.clockDomainId &&
			applied.capturedCollectedDataEpoch == authority.collectedDataEpoch
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
		wal: WifiWalObservationEvidence,
		deliveryIdentity: SourceDeliveryIdentity,
	) = WifiCapturedFactIdentity(
		sourceEventId = wal.sourceEventId,
		sourceAdmissionOrdinal = wal.sourceAdmissionOrdinal,
		walIntegrityIdentity = wal.walIntegrityIdentity,
		payloadChecksum = wal.payloadChecksum,
		sourceDeliveryIdentity = deliveryIdentity,
		deliveryUnitIndex = wal.deliveryUnitIndex,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		sessionSegmentId = sessionSegmentId,
		sessionManifestRevision = sessionManifestRevision,
		collectedDataEpoch = collectedDataEpoch,
	)

	private fun WifiWalObservationEvidence.evidenceBinding(
		deliveryIdentity: SourceDeliveryIdentity,
		authority: WifiCaptureAuthority,
	) = WifiCapturedEvidenceBinding(
		sourceEventId = sourceEventId,
		sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		walIntegrityIdentity = walIntegrityIdentity,
		payloadChecksum = payloadChecksum,
		sourceDeliveryIdentity = deliveryIdentity,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		acquisitionPlanRevision = authority.configurationRevision,
		acquisitionPlanChecksum = authority.serializedAcquisitionPlan.payloadChecksum,
		clockDomainId = clock.clockDomainId,
		observedIntervalStartElapsedRealtimeNanos = clock.observedIntervalStartElapsedRealtimeNanos,
		observedElapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		observedWallTimeMs = clock.observedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
	)

	private fun WifiWalObservationEvidence.matchesCapturedAuthority(
		authority: WifiCaptureAuthority,
	): Boolean = logicalTrackingId == authority.logicalTrackingId &&
		serviceRunId == authority.serviceRunId &&
		sourceInstanceId == authority.sourceInstanceId &&
		registrationGeneration == authority.registrationGeneration &&
		configurationRevision == authority.configurationRevision &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		purposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.sessionManifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch &&
		clock.clockDomainId == authority.clockDomainId

	private fun WifiDurableClockEvidence.isValidFor(authority: WifiCaptureAuthority): Boolean =
		clockDomainId == authority.clockDomainId &&
			observedIntervalStartElapsedRealtimeNanos > 0L &&
			observedElapsedRealtimeNanos >= observedIntervalStartElapsedRealtimeNanos &&
			receivedElapsedRealtimeNanos >= observedElapsedRealtimeNanos &&
			observedWallTimeMs >= 0L && wallTimeUncertaintyMs >= 0L

	private fun WifiResultSnapshotPayload.providerTimelineMatches(
		clock: WifiDurableClockEvidence,
	): Boolean {
		if (accessPoints.isEmpty()) return true
		val providerTimes = accessPoints.mapNotNull(WifiAccessPointEvidence::providerTimestampNanos)
		if (providerTimes.isEmpty()) return true
		return providerTimes.minOrNull() == clock.observedIntervalStartElapsedRealtimeNanos &&
			providerTimes.maxOrNull() == clock.observedElapsedRealtimeNanos
	}

	private fun WifiConfirmedEmptyProviderProof.matches(wal: WifiWalObservationEvidence): Boolean =
		sourceEventId == wal.sourceEventId &&
			sourceAdmissionOrdinal == wal.sourceAdmissionOrdinal &&
			walIntegrityIdentity == wal.walIntegrityIdentity &&
			payloadChecksum == wal.payloadChecksum &&
			sourceDeliveryIdentity == wal.sourceDeliveryIdentity &&
			clockDomainId == wal.clock.clockDomainId &&
			receivedElapsedRealtimeNanos == wal.clock.receivedElapsedRealtimeNanos &&
			providerObservationElapsedRealtimeNanos == wal.clock.observedElapsedRealtimeNanos &&
			observedWallTimeMs == wal.clock.observedWallTimeMs &&
			wallTimeUncertaintyMs == wal.clock.wallTimeUncertaintyMs

	private fun WifiCaptureAuthority.classifyProviderTime(
		providerTimeNanos: Long?,
		receivedElapsedRealtimeNanos: Long,
		maximumObservationAgeNanos: Long,
	): ProviderTimeClassification {
		if (providerTimeNanos == null || providerTimeNanos <= 0L ||
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

	private fun deriveObservedWall(
		clock: WifiDurableClockEvidence,
		providerTimeNanos: Long,
	): DerivedWifiWallTime? {
		if (clock.clockDomainId.isBlank() || clock.observedElapsedRealtimeNanos <= 0L ||
			providerTimeNanos <= 0L || providerTimeNanos > clock.observedElapsedRealtimeNanos ||
			clock.observedWallTimeMs < 0L || clock.wallTimeUncertaintyMs < 0L
		) return null
		return runCatching {
			val ageNanos = clock.observedElapsedRealtimeNanos - providerTimeNanos
			val wholeAgeMs = ageNanos / NANOS_PER_MILLISECOND
			val roundingUncertaintyMs = if (ageNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
			val observedWall = Math.subtractExact(clock.observedWallTimeMs, wholeAgeMs)
			val uncertainty = Math.addExact(
				clock.wallTimeUncertaintyMs,
				roundingUncertaintyMs,
			)
			val earliest = Math.subtractExact(observedWall, uncertainty)
			val latest = Math.addExact(observedWall, uncertainty)
			if (earliest < 0L) null else DerivedWifiWallTime(observedWall, uncertainty, earliest, latest)
		}.getOrNull()
	}

	private fun DerivedWifiWallTime.crossesRetentionFloor(
		deletionAuthority: WifiDeletionAuthority,
	): Boolean = deletionAuthority.retainedFromWallTimeMs?.let { earliestPossibleWallTimeMs < it }
		?: false

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

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private fun rejected(reason: WifiFactRejection) = WifiCapturedFactClassification.Rejected(reason)

	private data class QualifiedWifiPlan(val plan: WifiPlan, val maximumObservationAgeNanos: Long)
	private data class DerivedWifiWallTime(
		val observedWallTimeMs: Long,
		val wallTimeUncertaintyMs: Long,
		val earliestPossibleWallTimeMs: Long,
		val latestPossibleWallTimeMs: Long,
	)

	private enum class ProviderTimeClassification { FRESH, STALE, CLOCK_UNVERIFIABLE, MALFORMED }
	private enum class WifiBand { TWO_POINT_FOUR_GHZ, FIVE_GHZ, SIX_GHZ, OTHER }

	private const val SOURCE_PLAN_PAYLOAD_VERSION = 1
	private const val WIFI_PAYLOAD_VERSION = 2
	private const val NANOS_PER_MILLISECOND = 1_000_000L
	private const val WIFI_24_GHZ_MIN_MHZ = 2_400
	private const val WIFI_24_GHZ_MAX_MHZ = 2_500
	private const val WIFI_5_GHZ_MIN_MHZ = 4_900
	private const val WIFI_5_GHZ_MAX_MHZ = 5_900
	private const val WIFI_6_GHZ_MIN_MHZ = 5_925
	private const val WIFI_6_GHZ_MAX_MHZ = 7_125
	private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
}
