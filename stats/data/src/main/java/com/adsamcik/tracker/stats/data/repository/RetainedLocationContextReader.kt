package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextGap
import javax.inject.Inject

/** Caller owns the Room transaction. This reader has no collection or repair dependency. */
internal class RetainedLocationContextReader @Inject constructor(private val database: AppDatabase) {
    suspend fun readInTransaction(radios: List<RadioContextInput>): RetainedLocationContextSnapshot {
        if (radios.isEmpty()) return RetainedLocationContextSnapshot(emptyList())
        val clocks = radios.groupBy { it.scope.clockIdentity() }
        if (clocks.size > MAX_CONTEXT_CLOCKS) return limited()
        val receipts = mutableListOf<LocationAcceptedReceiptEntity>()
        for ((clock, group) in clocks) {
            val from = maxOf(0L, group.minOf { it.observedFromElapsedNanos } - CONTEXT_MARGIN_NANOS)
            val last = group.maxOf { it.observedElapsedNanos }
            val to = if (last > Long.MAX_VALUE - CONTEXT_MARGIN_NANOS) Long.MAX_VALUE else last + CONTEXT_MARGIN_NANOS
            val page = database.locationPurposeDao().contextReceipts(clock.first, clock.second,
                from, to, MAX_CONTEXT_POINTS - receipts.size + 1)
            receipts += page
            if (receipts.size > MAX_CONTEXT_POINTS) return limited()
        }
        return qualifyInTransaction(receipts)
    }

    /** Shared source-owned lookup for range/history readers, using their already bounded candidates. */
    suspend fun qualifyInTransaction(receipts: List<LocationAcceptedReceiptEntity>): RetainedLocationContextSnapshot {
        if (receipts.size > MAX_CONTEXT_POINTS || receipts.map { it.sampleId }.distinct().size != receipts.size) return limited()
        val state = database.sourceEvidenceStateDao().get()
            ?: return unavailable(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE)
        if (database.locationPurposeDao().savedFileExportAuthorityStamp().endsWith("|1")) {
            return unavailable(RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE)
        }
        val ids = receipts.map { it.sampleId }
        val samples = ids.chunked(QUERY_CHUNK).flatMap { database.locationSampleDao().byIds(it) }.associateBy { it.id }
        val purposes = mutableListOf<LocationSamplePurposeAuthorityEntity>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            purposes += database.locationPurposeDao().contextPurposes(chunk, MAX_CONTEXT_DEPENDENCIES - purposes.size + 1)
            if (purposes.size > MAX_CONTEXT_DEPENDENCIES) return limited()
        }
        val policyIds = purposes.map { it.sourcePolicyRevision }.distinct()
        val policies = policyIds.chunked(QUERY_CHUNK).flatMap {
            database.sourcePolicyDao().policiesAtRevisions(LOCATION, it)
        }.associateBy { it.policyRevision }
        val consents = purposes.groupBy { it.purpose }.flatMap { (purpose, group) ->
            group.map { it.consentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
                database.sourcePolicyDao().consentEpochs(LOCATION, purpose, it)
            }
        }.associateBy { it.purpose to it.epoch }
        val runs = purposes.mapNotNull { it.serviceRunId }.distinct()
        if (runs.size > MAX_CONTEXT_CLOCKS) return limited()
        val runRows = if (runs.isEmpty()) emptyList() else database.trackingHistoryReadDao().serviceRuns(runs)
        val manifests = if (runs.isEmpty()) emptyList() else database.trackingHistoryReadDao().manifests(runs, MAX_CONTEXT_DEPENDENCIES + 1)
        val sources = if (runs.isEmpty()) emptyList() else database.trackingHistoryReadDao().manifestSources(runs, MAX_CONTEXT_DEPENDENCIES + 1)
        if (manifests.size > MAX_CONTEXT_DEPENDENCIES || sources.size > MAX_CONTEXT_DEPENDENCIES) return limited()
        val runById = runRows.associateBy { it.serviceRunId }
        val manifestById = manifests.associateBy { it.serviceRunId to it.manifestRevision }
        val sourcesById = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
        val digests = purposes.filter { it.purpose == CAPTURE }.map { it.scopeDigest() }.distinct()
        val fences = digests.chunked(QUERY_CHUNK).flatMap {
            database.sourceDeletionFenceDao().contextFences(LOCATION, CAPTURE, it, MAX_CONTEXT_DEPENDENCIES + 1)
        }
        if (fences.size > MAX_CONTEXT_DEPENDENCIES) return limited()
        val fenced = fences.map { it.scopeIdentityDigest }.toSet()
        val revisions = purposes.mapNotNull { it.retentionApprovalRevision }.distinct()
        val approvals = revisions.chunked(QUERY_CHUNK).flatMap {
            database.locationPurposeDao().contextRetentionApprovals(it, MAX_CONTEXT_DEPENDENCIES + 1)
        }.associateBy { it.approvalRevision }
        val bySample = purposes.groupBy { it.sampleId }
        val accepted = mutableListOf<LocationContextInput>()
        val gaps = mutableSetOf<RadioLocationContextGap>()
        for (receipt in receipts) {
            val sample = samples[receipt.sampleId]
            if (sample == null || !LocationAcceptedReceiptIntegrity.matchesCanonicalSample(receipt, sample) ||
                !LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(receipt) ||
                receipt.writerId != SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID ||
                receipt.writerVersion != SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION) {
                gaps += RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE
                continue
            }
            if (receipt.collectedDataEpoch != state.collectedDataEpoch ||
                receipt.sourceAdmissionOrdinal <= state.deletedSourceEventHighWaterOrdinal ||
                state.retainedFromMs?.let { receipt.observedWallTimeMs - receipt.wallTimeUncertaintyMs < it } == true) continue
            val latitude = sample.latE7 ?: continue
            val longitude = sample.lonE7 ?: continue
            val accuracy = sample.hAccM?.toFloat()?.takeIf { it.isFinite() && it >= 0f } ?: continue
            for (purpose in bySample[receipt.sampleId].orEmpty()) {
                if (!LocationAcceptedReceiptIntegrity.hasRetainedIntegrity(purpose, receipt)) {
                    gaps += RadioLocationContextGap.LOCATION_PROVENANCE_UNVERIFIABLE
                    continue
                }
                val policy = policies[purpose.sourcePolicyRevision]
                val consent = consents[purpose.purpose to purpose.consentEpoch]
                val eligible = policy != null && consent?.eligible == true && consent.persistenceEligible &&
                    (if (purpose.purpose == CAPTURE) policy.capturePersistenceEligible && policy.captureConsentEpoch == purpose.consentEpoch
                    else policy.ambientPersistenceEligible && policy.ambientConsentEpoch == purpose.consentEpoch)
                if (!eligible || receipt.observedElapsedRealtimeNanos !in purpose.effectiveFromElapsedNanos until purpose.effectiveUntilElapsedNanos) {
                    gaps += RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE
                    continue
                }
                val scope = if (purpose.purpose == CAPTURE) {
                    val run = runById[purpose.serviceRunId]
                    val manifest = manifestById[purpose.serviceRunId to purpose.sessionManifestRevision]
                    val members = manifest?.let { sourcesById[it.logicalTrackingId to it.manifestRevision] }.orEmpty()
                    if (run == null || manifest == null || !SessionManifestIntegrity.verify(manifest, members) ||
                        run.logicalTrackingId != purpose.logicalTrackingId || run.sessionSegmentId != purpose.sessionSegmentId ||
                        run.leaseGeneration != purpose.lifecycleLeaseGeneration || run.bootId != receipt.bootId ||
                        manifest.sourcePolicyRevision != purpose.sourcePolicyRevision || manifest.effectiveBootId != receipt.bootId ||
                        manifest.effectiveElapsedRealtimeNanos > receipt.observedElapsedRealtimeNanos ||
                        members.none { it.sourceKind == LOCATION && it.purpose == CAPTURE && it.persistenceEligible && it.consentEpoch == purpose.consentEpoch } ||
                        purpose.scopeDigest() in fenced) {
                        gaps += RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE
                        continue
                    }
                    RadioContextScope.Session(SessionContextScope(requireNotNull(purpose.logicalTrackingId),
                        requireNotNull(purpose.serviceRunId), requireNotNull(purpose.sessionSegmentId),
                        receipt.bootId, receipt.clockDomainId, receipt.collectedDataEpoch))
                } else {
                    val approval = approvals[purpose.retentionApprovalRevision]
                    if (approval == null || !approval.isActive || !AmbientLocationRetentionAuthorityIntegrity.isAuthentic(approval) ||
                        approval.opaquePolicyId != purpose.retentionPolicyId || approval.collectedDataEpoch != receipt.collectedDataEpoch ||
                        approval.sourcePolicyRevision != purpose.sourcePolicyRevision || approval.ambientConsentEpoch != purpose.consentEpoch ||
                        approval.retainedFromMs?.let { receipt.observedWallTimeMs - receipt.wallTimeUncertaintyMs < it } == true) {
                        gaps += RadioLocationContextGap.LOCATION_AUTHORITY_UNAVAILABLE
                        continue
                    }
                    RadioContextScope.Ambient(AmbientContextScope(receipt.bootId, receipt.clockDomainId, receipt.collectedDataEpoch))
                }
                accepted += LocationContextInput(receipt.sourceEventId, scope, receipt.observedElapsedRealtimeNanos,
                    latitude, longitude, accuracy, true, receipt.observedWallTimeMs, receipt.wallTimeUncertaintyMs)
            }
        }
        return RetainedLocationContextSnapshot(accepted.distinctBy { it.sourceEventId to it.scope }, gaps)
    }

    private fun limited() = unavailable(RadioLocationContextGap.LOCATION_DEPENDENCY_LIMIT)
    private fun unavailable(gap: RadioLocationContextGap) = RetainedLocationContextSnapshot(emptyList(), setOf(gap))
}

internal data class RetainedLocationContextSnapshot(val points: List<LocationContextInput>,
    val gaps: Set<RadioLocationContextGap> = emptySet())

internal fun RadioContextScope.clockIdentity(): Pair<String, String> = when (this) {
    is RadioContextScope.Session -> owner.bootId to owner.clockDomainId
    is RadioContextScope.Ambient -> owner.bootId to owner.clockDomainId
}

private fun LocationSamplePurposeAuthorityEntity.scopeDigest() = SourceDeletionFenceEntity.logicalServiceRunIdentity(
    LOCATION, CAPTURE, requireNotNull(logicalTrackingId), requireNotNull(serviceRunId))
internal const val MAX_CONTEXT_POINTS = 4_096
internal const val MAX_CONTEXT_DEPENDENCIES = 16_384
internal const val QUERY_CHUNK = 400
private const val MAX_CONTEXT_CLOCKS = 100
private const val CONTEXT_MARGIN_NANOS = 60_000_000_000L
private const val LOCATION = SourceDestinationOwnerEntity.SOURCE_LOCATION
private const val CAPTURE = SourceBrokerPurpose.SESSION_CAPTURE
