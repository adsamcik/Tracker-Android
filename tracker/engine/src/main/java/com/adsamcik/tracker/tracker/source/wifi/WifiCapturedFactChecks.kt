package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint

internal fun WifiCapturedFactRevisionEntity.matchesAuthenticatedWal(authenticated: AuthenticatedWifiWal): Boolean =
	WifiMaintenanceFactChecks.matches(this, authenticated)

/** Exact saved fact fields compared with their original qualified provider delivery. */
private object WifiMaintenanceFactChecks {
	fun matches(fact: WifiCapturedFactRevisionEntity, authenticated: AuthenticatedWifiWal): Boolean {
		val derived = authenticated.derivedAggregate ?: return false
		return WifiMaintenanceFactIdentity.matches(fact, authenticated) &&
			WifiMaintenanceFactMeasurements.matches(fact, authenticated, derived) &&
			productMatches(fact, derived.aggregate)
	}

	private fun productMatches(fact: WifiCapturedFactRevisionEntity, aggregate: WifiIdentityFreeAggregate): Boolean =
		when (fact.factKind) {
			WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE ->
				fact.aggregateOwnerLogicalFactId == null && fact.aggregateOwnerSemanticRevision == null &&
					fact.aggregateOwnerCursorRevision == null && fact.matchesIdentityFreeAggregate(aggregate)
			WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY ->
				fact.aggregateOwnerLogicalFactId != null && fact.aggregateOwnerSemanticRevision != null &&
					fact.aggregateOwnerCursorRevision != null && hasNoAggregatePayload(fact)
			else -> false
		}

	private fun hasNoAggregatePayload(fact: WifiCapturedFactRevisionEntity): Boolean = fact.observationCount == null &&
		fact.twoPointFourGhzCount == null && fact.fiveGhzCount == null && fact.sixGhzCount == null &&
		fact.otherBandCount == null && fact.strongestSignalDbm == null && fact.weakestSignalDbm == null &&
			fact.signalSumDbm == null
}

private object WifiMaintenanceFactIdentity {
	fun matches(fact: WifiCapturedFactRevisionEntity, authenticated: AuthenticatedWifiWal): Boolean =
		bindingMatches(fact, authenticated) && carrierHeaderMatches(fact, authenticated.evidence) &&
		carrierDeliveryMatches(fact, authenticated.evidence) && registrationMatches(fact, authenticated.authority) &&
		captureOwnershipMatches(fact, authenticated.authority) && acquisitionMatches(fact, authenticated.authority)

	private fun bindingMatches(fact: WifiCapturedFactRevisionEntity, authenticated: AuthenticatedWifiWal): Boolean {
		val authority = authenticated.authority
		return fact.writerBindingGeneration == authenticated.writerBindingGeneration &&
			fact.writerOwnerGeneration == authenticated.writerOwnerGeneration &&
			fact.logicalTrackingId == authority.logicalTrackingId.value && fact.serviceRunId == authority.serviceRunId.value &&
			fact.sessionSegmentId == authority.sessionSegmentId &&
				fact.capturedSourceCodes == authority.capturedSources.toStableCodes() &&
			fact.controlSourceCodes == authority.controlSources.toStableCodes()
	}

	private fun carrierHeaderMatches(
		fact: WifiCapturedFactRevisionEntity,
		evidence: WifiWalObservationEvidence,
	): Boolean =
		fact.sourceEventId == evidence.sourceEventId.value &&
			fact.sourceAdmissionOrdinal == evidence.sourceAdmissionOrdinal &&
		fact.walIntegrityIdentity == evidence.walIntegrityIdentity && fact.payloadChecksum == evidence.payloadChecksum &&
		(fact.payloadVersion == null || fact.payloadVersion == evidence.payloadVersion)

	private fun carrierDeliveryMatches(
		fact: WifiCapturedFactRevisionEntity,
		evidence: WifiWalObservationEvidence,
	): Boolean =
		fact.sourceDeliveryIdentity == evidence.sourceDeliveryIdentity?.value &&
			fact.deliveryUnitIndex == evidence.deliveryUnitIndex &&
		fact.deliveryUnitCount == evidence.deliveryUnitCount && fact.sourceSequence == evidence.sourceSequence &&
		fact.planAttribution == evidence.planAttribution.name

	private fun registrationMatches(fact: WifiCapturedFactRevisionEntity, authority: WifiCaptureAuthority): Boolean =
		fact.sourceInstanceId == authority.sourceInstanceId.value &&
			fact.registrationGeneration == authority.registrationGeneration &&
		fact.configurationRevision == authority.configurationRevision &&
		fact.physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
		fact.authorizationRevision == authority.authorizationRevision &&
			fact.authorizationFingerprint == authority.authorizationFingerprint &&
		fact.purposeEligibilityMask == authority.purposeEligibilityMask

	private fun captureOwnershipMatches(fact: WifiCapturedFactRevisionEntity, authority: WifiCaptureAuthority): Boolean =
		fact.sourcePolicyRevision == authority.sourcePolicyRevision &&
			fact.captureConsentEpoch == authority.captureConsentEpoch &&
		fact.manifestRevision == authority.sessionManifestRevision &&
			fact.lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
		fact.collectedDataEpoch == authority.collectedDataEpoch && fact.scopeDeletionGeneration == 0L

	private fun acquisitionMatches(fact: WifiCapturedFactRevisionEntity, authority: WifiCaptureAuthority): Boolean =
		fact.clockDomainId == authority.clockDomainId && fact.storedZoneId == authority.zoneId &&
		fact.planPayloadVersion == authority.serializedAcquisitionPlan.payloadVersion &&
		fact.planPayloadChecksum == authority.serializedAcquisitionPlan.payloadChecksum &&
		fact.maximumObservationAgeNanos == authority.acquisitionConfiguration.maximumObservationAgeNanos &&
		fact.resultContract == authority.acquisitionConfiguration.resultContract.name &&
		fact.registrationAppliedAtNanos == authority.appliedRegistration.appliedAtElapsedRealtimeNanos
}

private object WifiMaintenanceFactMeasurements {
	fun matches(fact: WifiCapturedFactRevisionEntity, authenticated: AuthenticatedWifiWal,
		derived: WifiCapturedFact.Aggregate): Boolean = temporalMatches(fact, authenticated.authority) &&
			sampleTimingMatches(fact, authenticated.evidence, derived) &&
			observationMatches(fact, authenticated.evidence, derived) && coverageMatches(fact, derived)

	private fun temporalMatches(fact: WifiCapturedFactRevisionEntity, authority: WifiCaptureAuthority): Boolean =
		retainedIntervalMatches(fact, fact.providerAcceptanceStartNanos, fact.providerAcceptanceEndNanos,
			authority.temporalAuthority.providerAcceptance) &&
		retainedIntervalMatches(fact, fact.authorizationEffectStartNanos, fact.authorizationEffectEndNanos,
			authority.temporalAuthority.authorizationEffect) &&
		retainedIntervalMatches(fact, fact.sessionRunEffectStartNanos, fact.sessionRunEffectEndNanos,
			authority.temporalAuthority.sessionRunEffect)

	/** A later closing can narrow saved authority only while every measured reading remains inside it. */
	private fun retainedIntervalMatches(
		fact: WifiCapturedFactRevisionEntity,
		recordedStart: Long,
		recordedEnd: Long,
		current: WifiProviderTimeInterval,
	): Boolean {
		val boundsMatch = recordedStart >= 0L && recordedStart == current.startInclusiveNanos &&
			recordedEnd > recordedStart && current.endExclusiveNanos <= recordedEnd
		return boundsMatch && fact.coverageIntervalStartNanos >= recordedStart &&
			fact.coverageIntervalEndNanos >= fact.coverageIntervalStartNanos &&
			fact.coverageIntervalEndNanos < current.endExclusiveNanos
	}

	private fun sampleTimingMatches(fact: WifiCapturedFactRevisionEntity, evidence: WifiWalObservationEvidence,
		derived: WifiCapturedFact.Aggregate): Boolean =
		fact.observedIntervalStartNanos == evidence.clock.observedIntervalStartElapsedRealtimeNanos &&
		fact.observedElapsedNanos == evidence.clock.observedElapsedRealtimeNanos &&
		fact.receivedElapsedNanos == evidence.clock.receivedElapsedRealtimeNanos &&
		fact.coverageIntervalStartNanos == derived.coverage.providerIntervalStartElapsedRealtimeNanos &&
		fact.coverageIntervalEndNanos == derived.coverage.providerIntervalEndElapsedRealtimeNanos

	private fun observationMatches(fact: WifiCapturedFactRevisionEntity, evidence: WifiWalObservationEvidence,
		derived: WifiCapturedFact.Aggregate): Boolean = fact.observedWallTimeMs == derived.observedWallTimeMs &&
			fact.wallTimeUncertaintyMs == derived.wallTimeUncertaintyMs && fact.acquiredAtMs == evidence.acquiredAtMs &&
			fact.qualityFlags == evidence.qualityFlags && fact.qualityConfidence == evidence.qualityConfidence

	private fun coverageMatches(fact: WifiCapturedFactRevisionEntity, derived: WifiCapturedFact.Aggregate): Boolean =
		fact.availability == derived.availability.name &&
			fact.submittedResultCount == derived.coverage.submittedResultCount &&
		fact.acceptedResultCount == derived.coverage.acceptedResultCount &&
			fact.staleResultCount == derived.coverage.staleResultCount &&
		fact.clockUnverifiableResultCount == derived.coverage.clockUnverifiableResultCount &&
		fact.malformedResultCount == derived.coverage.malformedResultCount &&
		fact.coverageCompleteness == derived.coverage.completeness.name && fact.appliedAtMs == derived.observedWallTimeMs
}

internal fun WifiCapturedFactRevisionEntity.matchesIdentityFreeAggregate(
	aggregate: WifiIdentityFreeAggregate,
): Boolean = observationCount == aggregate.observationCount &&
	twoPointFourGhzCount == aggregate.bandMix.twoPointFourGhzCount &&
	fiveGhzCount == aggregate.bandMix.fiveGhzCount &&
	sixGhzCount == aggregate.bandMix.sixGhzCount &&
	otherBandCount == aggregate.bandMix.otherCount &&
	strongestSignalDbm == aggregate.signalQuality?.strongestSignalLevelDbm &&
	weakestSignalDbm == aggregate.signalQuality?.weakestSignalLevelDbm &&
	signalSumDbm == aggregate.signalQuality?.signalLevelSumDbm

internal fun WifiCapturedFactCursorEntity.matchesCurrent(revision: WifiCapturedFactRevisionEntity): Boolean =
	WifiCapturedFactRevisionIntegrity.cursorAuthenticatesCurrent(this, revision)

internal fun WifiCapturedFactRevisionEntity.hasFiniteAggregateReuseAuthority(): Boolean =
	providerAcceptanceEndNanos != Long.MAX_VALUE &&
		authorizationEffectEndNanos != Long.MAX_VALUE && sessionRunEffectEndNanos != Long.MAX_VALUE

internal fun WifiCapturedFactRevisionEntity.hasExactRevisionIdentity(): Boolean =
	logicalFactId == WifiCapturedFactRevisionIntegrity.logicalFactId(
		sourceDeliveryIdentity, logicalTrackingId, serviceRunId, sessionSegmentId,
		manifestRevision, collectedDataEpoch, scopeDeletionGeneration,
	) && mutationId == WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, semanticRevision)

internal fun WifiCapturedFactRevisionEntity.earliestCoveredWallTimeMs(): Long? = runCatching {
	CanonicalRetentionBounds.wifiCoverage(
		coverageEndWallTimeMs = observedWallTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		coverageStartElapsedRealtimeNanos = coverageIntervalStartNanos,
		coverageEndElapsedRealtimeNanos = coverageIntervalEndNanos,
	)
}.getOrNull()
