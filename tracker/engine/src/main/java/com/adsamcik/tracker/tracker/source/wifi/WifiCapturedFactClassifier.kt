package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
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
	val providerDedupKey: String?,
	val sourceDeliveryIdentity: SourceDeliveryIdentity?,
	val deliveryUnitIndex: Int,
	val deliveryUnitCount: Int,
	val capturedAuthority: WifiCaptureAuthority,
	val logicalTrackingId: LogicalTrackingId,
	val serviceRunId: ServiceRunId,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val configurationRevision: Long?,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val purposeEligibilityMask: Long,
	val sourceSequence: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val sessionManifestRevision: Long,
	val lifecycleLeaseGeneration: Long,
	val capturedCollectedDataEpoch: Long,
	val activityAutomationEpoch: Long?,
	val planAttribution: PlanAttribution,
	val clock: WifiDurableClockEvidence,
	val acquiredAtMs: Long,
	val qualityFlags: Long,
	val qualityConfidence: Float?,
	val payloadVersion: Int,
	val payloadBytes: List<Byte>,
	val payloadChecksum: String,
) {
	/** Recomputes the production `source_event_wal` identity over every integrity-bound field. */
	fun calculatedWalIntegrityIdentity(): String = asWalEntity().calculatedIntegrityIdentity()

	private fun asWalEntity(): SourceEventWalEntity = SourceEventWalEntity(
		admissionOrdinal = sourceAdmissionOrdinal,
		eventId = sourceEventId.value,
		providerDedupKey = providerDedupKey,
		deliveryIdentity = sourceDeliveryIdentity?.value,
		deliveryUnitIndex = deliveryUnitIndex,
		deliveryUnitCount = deliveryUnitCount,
		logicalTrackingId = logicalTrackingId.value,
		serviceRunId = serviceRunId.value,
		sourceKind = SourceKind.WIFI.stableCode,
		sourceInstanceId = sourceInstanceId.value,
		registrationGeneration = registrationGeneration,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		authorizationRevision = authorizationRevision,
		authorizationPurposeEligibilityMask = purposeEligibilityMask,
		authorizationFingerprint = authorizationFingerprint,
		sourceSequence = sourceSequence,
		configRevision = configurationRevision,
		planAttribution = planAttribution.ordinal,
		clockDomainId = clock.clockDomainId,
		observedElapsedNanos = clock.observedElapsedRealtimeNanos,
		observedIntervalStartNanos = clock.observedIntervalStartElapsedRealtimeNanos,
		receivedElapsedNanos = clock.receivedElapsedRealtimeNanos,
		wallTimeMs = clock.observedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
		capturedCollectedDataEpoch = capturedCollectedDataEpoch,
		activityAutomationEpoch = activityAutomationEpoch,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		sessionManifestRevision = sessionManifestRevision,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		acquiredAtMs = acquiredAtMs,
		qualityFlags = qualityFlags,
		qualityConfidence = qualityConfidence,
		payloadVersion = payloadVersion,
		payload = payloadBytes.toByteArray(),
		payloadChecksum = payloadChecksum,
		integrityIdentity = walIntegrityIdentity,
		createdAtMs = acquiredAtMs,
	)
}

/** Source-boundary input. Updated results must be recovered from one immutable WAL unit. */
internal data class WifiObservationInput(
	val origin: WifiObservationOrigin,
	val outcome: WifiProviderOutcome,
	val walEvidence: WifiWalObservationEvidence?,
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
	PLAN_ATTRIBUTION_UNVERIFIABLE,
	ACQUISITION_PLAN_UNVERIFIABLE,
	APPLIED_REGISTRATION_MISMATCH,
	PAYLOAD_TOO_LARGE,
	PROVIDER_DELIVERY_SHAPE_UNVERIFIABLE,
	TOO_MANY_ACCESS_POINTS,
	MALFORMED_ACCESS_POINT,
}

internal sealed interface WifiCapturedFactClassification {
	data class FreshChanged(val fact: WifiCapturedFact.Aggregate) : WifiCapturedFactClassification
	data class FreshUnchanged(val fact: WifiCapturedFact.CoverageOnly) : WifiCapturedFactClassification
	data object Absent : WifiCapturedFactClassification
	data object Stale : WifiCapturedFactClassification
	data object Failed : WifiCapturedFactClassification
	data object PermissionLimited : WifiCapturedFactClassification
	data object OsThrottled : WifiCapturedFactClassification
	data object ClockUnverifiable : WifiCapturedFactClassification
	data class Replay(val reference: WifiAggregateFactReference) : WifiCapturedFactClassification
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
		if (!wal.hasValidWalProvenance()) {
			return rejected(WifiFactRejection.WAL_PROVENANCE_UNVERIFIABLE)
		}
		if (wal.planAttribution != PlanAttribution.CAPTURED_REGISTRATION) {
			return rejected(WifiFactRejection.PLAN_ATTRIBUTION_UNVERIFIABLE)
		}
		if (wal.capturedAuthority != expectedAuthority || !wal.matchesCapturedAuthority(expectedAuthority)) {
			return rejected(WifiFactRejection.CAPTURE_AUTHORITY_MISMATCH)
		}
		if (currentDeletionAuthority.currentCollectedDataEpoch != expectedAuthority.collectedDataEpoch) {
			return rejected(WifiFactRejection.DELETION_AUTHORITY_MISMATCH)
		}
		if (currentDeletionAuthority.currentScopeDeletionGeneration !=
			expectedAuthority.scopeDeletionGeneration
		) {
			return rejected(WifiFactRejection.DELETION_AUTHORITY_MISMATCH)
		}
		val qualifiedPlan = qualifyAcquisitionPlan(expectedAuthority)
			?: return rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)
		if (!appliedRegistrationMatches(expectedAuthority, qualifiedPlan.plan)) {
			return rejected(WifiFactRejection.APPLIED_REGISTRATION_MISMATCH)
		}
		if (wal.configurationRevision?.let { it != qualifiedPlan.plan.revision } == true) {
			return rejected(WifiFactRejection.ACQUISITION_PLAN_UNVERIFIABLE)
		}
		if (wal.payloadBytes.size > MAX_CANONICAL_WIFI_PAYLOAD_BYTES) {
			return rejected(WifiFactRejection.PAYLOAD_TOO_LARGE)
		}
		val payload = decodeWalPayload(wal) ?: return walPayloadRejection(wal)
		if (!wal.clock.isValidFor(expectedAuthority)) {
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (!payload.providerTimelineMatches(wal.clock)) {
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		val resultContract = expectedAuthority.acquisitionConfiguration.resultContract
		if (payload.accessPoints.size > resultContract.maximumAccessPointCount) {
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
		if (payload.accessPoints.isEmpty()) {
			// Android's current Wi-Fi callback payload has no provider-origin timestamp/proof for an
			// empty result. Receipt or WAL time cannot manufacture a captured zero.
			return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (!payload.hasCanonicalProviderDeliveryShape()) {
			return rejected(WifiFactRejection.PROVIDER_DELIVERY_SHAPE_UNVERIFIABLE)
		}
		val deliveryIdentity = wal.sourceDeliveryIdentity
			?: return rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
		val calculatedDeliveryIdentity = runCatching {
			wifiProviderDeliveryIdentity(expectedAuthority.clockDomainId, payload.accessPoints)
		}.getOrNull() ?: return rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
		if (deliveryIdentity != calculatedDeliveryIdentity) {
			return when {
				semanticRevision > 1L &&
					correctionBase?.reference?.identity?.sourceDeliveryIdentity == deliveryIdentity ->
					rejected(WifiFactRejection.RAW_EVIDENCE_CHANGED)
				priorFact?.reference?.identity?.sourceDeliveryIdentity == deliveryIdentity ->
					rejected(WifiFactRejection.DELIVERY_IDENTITY_COLLISION)
				else -> rejected(WifiFactRejection.DELIVERY_IDENTITY_UNVERIFIABLE)
			}
		}
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

		return classifyNonEmpty(
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

	private fun classifyNonEmpty(
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
		val itemClassifications = payload.accessPoints.map { accessPoint ->
			accessPoint to if (authority.acquisitionConfiguration.resultContract.accepts(
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
		val acceptedWalls = providerTimes.map { providerTime ->
			deriveObservedWall(wal.clock, providerTime)
				?: return WifiCapturedFactClassification.ClockUnverifiable
		}
		if (acceptedWalls.any { wall -> wall.crossesRetentionFloor(deletionAuthority) }) {
			return WifiCapturedFactClassification.Stale
		}
		val wall = acceptedWalls[providerTimes.indexOf(providerEnd)]
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
					mutation.semanticRevision == 1L &&
					prior.authority.temporalAuthority.isImmutableForAggregateReuse() &&
					prior.productEffect.aggregate == aggregate
			}
		return if (directAggregateOwner != null) {
			WifiCapturedFactClassification.FreshUnchanged(
				WifiCapturedFact.CoverageOnly.fromExactAggregate(
					mutation = mutation,
					authority = authority,
					evidenceBinding = evidenceBinding,
					observedWallTimeMs = wall.observedWallTimeMs,
					wallTimeUncertaintyMs = wall.wallTimeUncertaintyMs,
					availability = WifiAvailability.AVAILABLE,
					coverage = coverage,
					observedAggregate = aggregate,
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
				WifiCapturedFactClassification.Replay(priorFact.reference)
			} else {
				rejected(WifiFactRejection.DELIVERY_IDENTITY_COLLISION)
			}
		}
		return if (correctionBase?.authority == authority &&
			correctionBase.productEffect == productEffect
		) {
			WifiCapturedFactClassification.Replay(requireNotNull(correctionBase).reference)
		} else {
			null
		}
	}

	private fun decodeWalPayload(wal: WifiWalObservationEvidence): WifiResultSnapshotPayload? {
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity) ||
			!LOWERCASE_SHA_256.matches(wal.payloadChecksum)
		) return null
		val bytes = wal.payloadBytes.toByteArray()
		if (bytes.sha256() != wal.payloadChecksum ||
			wal.calculatedWalIntegrityIdentity() != wal.walIntegrityIdentity ||
			wal.payloadVersion != WIFI_PAYLOAD_VERSION
		) {
			return null
		}
		return runCatching {
			val decoded = payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, bytes)
				as? WifiResultSnapshotPayload ?: return@runCatching null
			val canonical = payloadCodec.encode(decoded, WIFI_PAYLOAD_VERSION).bytes
			decoded.takeIf { canonical.contentEquals(bytes) }
		}.getOrNull()
	}

	private fun walPayloadRejection(wal: WifiWalObservationEvidence): WifiCapturedFactClassification {
		if (!LOWERCASE_SHA_256.matches(wal.walIntegrityIdentity)) {
			return rejected(WifiFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
		}
		val bytes = wal.payloadBytes.toByteArray()
		if (!LOWERCASE_SHA_256.matches(wal.payloadChecksum) || bytes.sha256() != wal.payloadChecksum) {
			return rejected(WifiFactRejection.PAYLOAD_INTEGRITY_UNVERIFIABLE)
		}
		if (wal.calculatedWalIntegrityIdentity() != wal.walIntegrityIdentity) {
			return rejected(WifiFactRejection.WAL_INTEGRITY_UNVERIFIABLE)
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
		!authority.isExactSettlementOf(correctionBase.authority) -> false
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
		scopeDeletionGeneration = scopeDeletionGeneration,
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
		providerDedupKey = providerDedupKey,
		sourceSequence = sourceSequence,
		planAttribution = planAttribution,
		acquisitionPlanRevision = authority.configurationRevision,
		acquisitionPlanChecksum = authority.serializedAcquisitionPlan.payloadChecksum,
		clockDomainId = clock.clockDomainId,
		observedIntervalStartElapsedRealtimeNanos = clock.observedIntervalStartElapsedRealtimeNanos,
		observedElapsedRealtimeNanos = clock.observedElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = clock.receivedElapsedRealtimeNanos,
		observedWallTimeMs = clock.observedWallTimeMs,
		wallTimeUncertaintyMs = clock.wallTimeUncertaintyMs,
		acquiredAtMs = acquiredAtMs,
		qualityFlags = qualityFlags,
		qualityConfidence = qualityConfidence,
	)

	private fun WifiWalObservationEvidence.matchesCapturedAuthority(
		authority: WifiCaptureAuthority,
	): Boolean = logicalTrackingId == authority.logicalTrackingId &&
		serviceRunId == authority.serviceRunId &&
		sourceInstanceId == authority.sourceInstanceId &&
		registrationGeneration == authority.registrationGeneration &&
		(configurationRevision == null || configurationRevision == authority.configurationRevision) &&
		physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		authorizationRevision == authority.authorizationRevision &&
		authorizationFingerprint == authority.authorizationFingerprint &&
		purposeEligibilityMask == authority.purposeEligibilityMask &&
		sourcePolicyRevision == authority.sourcePolicyRevision &&
		captureConsentEpoch == authority.captureConsentEpoch &&
		sessionManifestRevision == authority.sessionManifestRevision &&
		lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		capturedCollectedDataEpoch == authority.collectedDataEpoch &&
		activityAutomationEpoch == null &&
		clock.clockDomainId == authority.clockDomainId

	private fun WifiWalObservationEvidence.hasValidWalProvenance(): Boolean =
		sourceAdmissionOrdinal > 0L &&
		// Android emits one Wi-Fi snapshot unit per provider callback. This pure qualifier has no
		// sibling universe with which it could authenticate any other claimed cardinality.
		deliveryUnitCount == 1 && deliveryUnitIndex == 0 &&
			providerDedupKey == null && sourceSequence >= 0L && activityAutomationEpoch == null &&
			acquiredAtMs >= 0L &&
			(qualityConfidence == null || qualityConfidence in 0f..1f)

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

	private fun WifiResultSnapshotPayload.hasCanonicalProviderDeliveryShape(): Boolean {
		if (accessPoints.isEmpty() || resultAgeMs != null) return false
		if (accessPoints.any { it.identifierToken.isNotEmpty() || it.providerTimestampNanos == null }) {
			return false
		}
		if (accessPoints != accessPoints.sortedWith(WIFI_PROVIDER_ITEM_ORDER)) return false
		val latestProviderNanos = accessPoints.maxOf { requireNotNull(it.providerTimestampNanos) }
		return platformTimestampMs == latestProviderNanos / NANOS_PER_MILLISECOND
	}

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

	/** Only fully settled authority may back another fact's aggregate reference. */
	private fun WifiCaptureTemporalAuthority.isImmutableForAggregateReuse(): Boolean =
		providerAcceptance.endExclusiveNanos != Long.MAX_VALUE &&
			authorizationEffect.endExclusiveNanos != Long.MAX_VALUE &&
			sessionRunEffect.endExclusiveNanos != Long.MAX_VALUE

	/** Corrections may close an open interval, but may not rotate any captured authority. */
	private fun WifiCaptureAuthority.isExactSettlementOf(previous: WifiCaptureAuthority): Boolean =
		copy(temporalAuthority = previous.temporalAuthority) == previous &&
			temporalAuthority.providerAcceptance.isExactSettlementOf(
				previous.temporalAuthority.providerAcceptance,
			) &&
			temporalAuthority.authorizationEffect.isExactSettlementOf(
				previous.temporalAuthority.authorizationEffect,
			) &&
			temporalAuthority.sessionRunEffect.isExactSettlementOf(
				previous.temporalAuthority.sessionRunEffect,
			)

	private fun WifiProviderTimeInterval.isExactSettlementOf(
		previous: WifiProviderTimeInterval,
	): Boolean = startInclusiveNanos == previous.startInclusiveNanos &&
		(endExclusiveNanos == previous.endExclusiveNanos ||
			(previous.endExclusiveNanos == Long.MAX_VALUE && endExclusiveNanos < Long.MAX_VALUE))

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
	// Canonical v2 bytes: type + count + 64 minimized APs + platform time + null result age.
	// A minimized AP is empty modified-UTF (2), frequency (4), signal (4), and timestamp (1 + 8).
	private val MAX_CANONICAL_WIFI_PAYLOAD_BYTES = 4 + 4 +
		(WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1.maximumAccessPointCount * 19) + 9 + 1
	private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
	private val WIFI_PROVIDER_ITEM_ORDER = compareBy<WifiAccessPointEvidence>(
		WifiAccessPointEvidence::frequencyMhz,
		WifiAccessPointEvidence::signalLevelDbm,
		WifiAccessPointEvidence::providerTimestampNanos,
	)
}
