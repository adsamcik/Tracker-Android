package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap

// These are the immutable maps already read in the caller's one bounded Room snapshot.
@Suppress("LongParameterList")
internal fun qualifyRetainedLocationReceipts(
    receipts: List<LocationAcceptedReceiptEntity>,
    samples: Map<Long, LocationSample>,
    effectiveSamples: Map<Long, LocationSample>,
    authenticatedSampleIds: Set<Long>,
    erasedNativeSampleIds: Set<Long>,
    state: SourceEvidenceState,
    purposes: List<LocationSamplePurposeAuthorityEntity>,
    policies: Map<Long, SourcePolicyEntity>,
    consents: Map<Pair<String, Long>, SourceConsentEpochEntity>,
    runById: Map<String, SourceServiceRunEntity>,
    manifestById: Map<Pair<String, Long>, SessionManifestVersionEntity>,
    sourcesById: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
    approvals: Map<Long, AmbientLocationRetentionAuthorityEntity>,
    fenced: Set<String>,
): RetainedLocationSavedSnapshot {
    val bySample = purposes.groupBy { it.sampleId }
    val accepted = mutableListOf<QualifiedRetainedLocationSample>()
    val gaps = mutableSetOf<RadioLocationContextGap>()
    for (receipt in receipts) {
        val sample = receipt.retainedLocationSample(samples, authenticatedSampleIds,
            erasedNativeSampleIds, state.retainedFromMs, gaps) ?: continue
        for (purpose in bySample[receipt.sampleId].orEmpty()) {
            val scope = qualifyRetainedLocationPurpose(purpose, receipt, policies, consents, runById,
                manifestById, sourcesById, approvals, fenced, gaps) ?: continue
            accepted += QualifiedRetainedLocationSample(effectiveSamples[sample.id] ?: sample, receipt, scope)
        }
    }
    return RetainedLocationSavedSnapshot(accepted.distinctBy { it.receipt.sourceEventId to it.scope }, gaps,
        erasedNativeSampleIds)
}

private fun LocationAcceptedReceiptEntity.retainedLocationSample(
    samples: Map<Long, LocationSample>,
    authenticatedSampleIds: Set<Long>,
    erasedNativeSampleIds: Set<Long>,
    retainedFromMs: Long?,
    gaps: MutableSet<RadioLocationContextGap>,
): LocationSample? {
    val sample = samples[sampleId]
    if (sample == null || sampleId !in authenticatedSampleIds) {
        gaps += RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE
        return null
    }
    if (isErasedOrExpiredRetainedLocation(sample.id, erasedNativeSampleIds, retainedFromMs)) {
        return null
    }
    return sample
}

private fun LocationSamplePurposeAuthorityEntity.hasEligibleRetainedLocationPolicy(
    policy: SourcePolicyEntity?,
    consent: SourceConsentEpochEntity?,
    receipt: LocationAcceptedReceiptEntity,
): Boolean {
    val eligible = policy != null && consent?.eligible == true && consent.persistenceEligible &&
        (if (purpose == LOCATION_CONTEXT_CAPTURE) {
            policy.capturePersistenceEligible && policy.captureConsentEpoch == consentEpoch
        } else {
            policy.ambientPersistenceEligible && policy.ambientConsentEpoch == consentEpoch
        })
    return eligible &&
        receipt.observedElapsedRealtimeNanos in effectiveFromElapsedNanos until effectiveUntilElapsedNanos
}

// Original snapshot dependencies and shared gap collector, without a second snapshot/context object.
@Suppress("LongParameterList")
private fun qualifyRetainedLocationPurpose(
    purpose: LocationSamplePurposeAuthorityEntity,
    receipt: LocationAcceptedReceiptEntity,
    policies: Map<Long, SourcePolicyEntity>,
    consents: Map<Pair<String, Long>, SourceConsentEpochEntity>,
    runById: Map<String, SourceServiceRunEntity>,
    manifestById: Map<Pair<String, Long>, SessionManifestVersionEntity>,
    sourcesById: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
    approvals: Map<Long, AmbientLocationRetentionAuthorityEntity>,
    fenced: Set<String>,
    gaps: MutableSet<RadioLocationContextGap>,
): RadioContextScope? {
    if (!LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, receipt)) {
        gaps += RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE
        return null
    }
    if (!purpose.hasEligibleRetainedLocationPolicy(policies[purpose.sourcePolicyRevision],
            consents[purpose.purpose to purpose.consentEpoch], receipt)) {
        gaps += RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE
        return null
    }
    val scope = if (purpose.purpose == LOCATION_CONTEXT_CAPTURE) {
        retainedLocationSessionScope(purpose, receipt, runById[purpose.serviceRunId],
            manifestById[purpose.serviceRunId to purpose.sessionManifestRevision], sourcesById, fenced)
    } else {
        retainedLocationAmbientScope(purpose, receipt, approvals[purpose.retentionApprovalRevision])
    }
    if (scope == null) {
        gaps += RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE
    }
    return scope
}

private fun retainedLocationSessionScope(
    purpose: LocationSamplePurposeAuthorityEntity,
    receipt: LocationAcceptedReceiptEntity,
    run: SourceServiceRunEntity?,
    manifest: SessionManifestVersionEntity?,
    sourcesById: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
    fenced: Set<String>,
): RadioContextScope? {
    val members = manifest?.let { sourcesById[it.logicalTrackingId to it.manifestRevision] }.orEmpty()
    if (run == null || manifest == null) {
        return null
    }
    if (!run.matchesRetainedLocationPurpose(purpose) || !manifest.matchesRetainedLocationPurpose(purpose, receipt) ||
        members.none { it.qualifiesRetainedLocationPurpose(purpose) } ||
        purpose.retainedLocationScopeDigest() in fenced) {
        return null
    }
    return RadioContextScope.Session(SessionContextScope(requireNotNull(purpose.logicalTrackingId),
        requireNotNull(purpose.serviceRunId), requireNotNull(purpose.sessionSegmentId),
        receipt.bootId, receipt.clockDomainId, receipt.collectedDataEpoch))
}

private fun SourceServiceRunEntity.matchesRetainedLocationPurpose(
    purpose: LocationSamplePurposeAuthorityEntity,
): Boolean = logicalTrackingId == purpose.logicalTrackingId && sessionSegmentId == purpose.sessionSegmentId &&
    SessionManifestIntegrity.hasRetainedLeaseGeneration(this, purpose.lifecycleLeaseGeneration)

private fun SessionManifestVersionEntity.matchesRetainedLocationPurpose(
    purpose: LocationSamplePurposeAuthorityEntity,
    receipt: LocationAcceptedReceiptEntity,
): Boolean = sourcePolicyRevision == purpose.sourcePolicyRevision && effectiveBootId == receipt.bootId &&
    effectiveElapsedRealtimeNanos <= receipt.observedElapsedRealtimeNanos

private fun SessionManifestSourceEntity.qualifiesRetainedLocationPurpose(
    purpose: LocationSamplePurposeAuthorityEntity,
): Boolean = sourceKind == SourceDestinationOwnerEntity.SOURCE_LOCATION && this.purpose == LOCATION_CONTEXT_CAPTURE &&
    persistenceEligible && consentEpoch == purpose.consentEpoch

private fun retainedLocationAmbientScope(
    purpose: LocationSamplePurposeAuthorityEntity,
    receipt: LocationAcceptedReceiptEntity,
    approval: AmbientLocationRetentionAuthorityEntity?,
): RadioContextScope? {
    if (approval == null || !approval.matchesRetainedLocationPurpose(purpose, receipt) ||
        receipt.isBelowRetainedLocationFloor(approval.retainedFromMs)) {
        return null
    }
    return RadioContextScope.Ambient(AmbientContextScope(receipt.bootId,
        receipt.clockDomainId, receipt.collectedDataEpoch))
}

private fun AmbientLocationRetentionAuthorityEntity.matchesRetainedLocationPurpose(
    purpose: LocationSamplePurposeAuthorityEntity,
    receipt: LocationAcceptedReceiptEntity,
): Boolean = isActive && AmbientLocationRetentionAuthorityIntegrity.isAuthentic(this) &&
    opaquePolicyId == purpose.retentionPolicyId && collectedDataEpoch == receipt.collectedDataEpoch &&
    sourcePolicyRevision == purpose.sourcePolicyRevision && ambientConsentEpoch == purpose.consentEpoch

private fun LocationAcceptedReceiptEntity.isErasedOrExpiredRetainedLocation(
    sampleId: Long,
    erasedSampleIds: Set<Long>,
    floor: Long?,
): Boolean = sampleId in erasedSampleIds || isBelowRetainedLocationFloor(floor)

private fun LocationAcceptedReceiptEntity.isBelowRetainedLocationFloor(floor: Long?): Boolean =
    floor?.let { observedWallTimeMs - wallTimeUncertaintyMs < it } == true

private const val LOCATION_CONTEXT_CAPTURE = SourceBrokerPurpose.SESSION_CAPTURE
