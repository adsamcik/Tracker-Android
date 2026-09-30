package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthenticator
import com.adsamcik.tracker.shared.base.database.data.*

/** Fields already bound by the native fact checksum, never reconstructed from imported data. */
internal data class RetainedRadioAuthorityInput(
    val registration: Long, val instance: String, val clock: String, val physicalFingerprint: String,
    val epoch: Long, val authorizationRevision: Long, val authorizationFingerprint: String, val purposeMask: Long,
    val fromNanos: Long, val toNanos: Long, val receivedNanos: Long, val wallTimeMs: Long,
    val policyRevision: Long, val consentEpoch: Long, val demandId: String?,
    val retentionPolicyId: String, val retentionRevision: Long,
)

/** Bounded historical provider/purpose snapshot; toggling recording off does not erase history. */
internal class RetainedAmbientRadioAuthority private constructor(
    private val source: Int,
    private val currentClock: String,
    private val registrations: Map<Long, ProviderRegistrationGenerationEntity>,
    private val currentRegistrations: List<ProviderRegistrationGenerationEntity>,
    private val authorizations: Map<Long, List<SourceAuthorizationSnapshot>>,
    private val demands: Map<String, SourceDemandEntity>,
    private val policies: Map<Long, SourcePolicyEntity>,
    private val consents: Map<Long, SourceConsentEpochEntity>,
) {
    fun qualifies(input: RetainedRadioAuthorityInput): Boolean {
        val policy = policies[input.policyRevision] ?: return false
        val consent = consents[input.consentEpoch] ?: return false
        if (!policy.ambientPersistenceEligible || policy.ambientConsentEpoch != input.consentEpoch ||
            !consent.eligible || !consent.persistenceEligible) return false
        val registration = registrations[input.registration] ?: return false
        if (ProviderRegistrationIntervalAuthenticator.authenticate(registration, source, input.registration,
                input.instance, input.clock, input.physicalFingerprint, input.epoch, registration.providerResidency,
                input.fromNanos, input.toNanos, input.wallTimeMs, currentClock, currentRegistrations)
            !is ProviderRegistrationIntervalAuthentication.Authenticated) return false
        val timeline = authorizations[input.registration].orEmpty()
        val callback = timeline.singleOrNull { it.authorizationRevision == input.authorizationRevision }
            ?: return false
        if (callback.authorizationFingerprint != input.authorizationFingerprint || callback.purposeEligibilityMask != input.purposeMask || callback.effectiveBootId != input.clock ||
            callback.effectiveElapsedRealtimeNanos > input.receivedNanos) return false
        val callbackTime = if (input.demandId != null) input.receivedNanos else input.toNanos
        if (timeline.lastOrNull { it.effectiveBootId == input.clock && it.effectiveElapsedRealtimeNanos <= callbackTime } != callback) return false
        val before = timeline.lastOrNull { it.effectiveBootId == input.clock && it.effectiveElapsedRealtimeNanos <= input.fromNanos }
            ?: return false
        val relevant = listOf(before) + timeline.filter { it.effectiveBootId == input.clock &&
            it.effectiveElapsedRealtimeNanos > input.fromNanos && it.effectiveElapsedRealtimeNanos <= input.toNanos }
        if ((relevant + callback).distinct().any { !authenticatesSnapshot(it, input.registration) }) return false
        return relevant.all { snapshot ->
            val members = snapshot.authorizedMembers
            members.any { member ->
                val demand = demands[member.demandId] ?: return@any false
                member.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && member.persistenceEligible &&
                    member.sourcePolicyRevision == input.policyRevision && member.consentEpoch == input.consentEpoch &&
                    (input.demandId == null || member.demandId == input.demandId) &&
                    member.liveAmbientRetentionPolicyId == input.retentionPolicyId &&
                    member.liveAmbientRetentionApprovalRevision == input.retentionRevision &&
                    demand.sourceKind == source && demand.purpose == member.purpose && demand.persistenceEligible &&
                    demand.sourcePolicyRevision == member.sourcePolicyRevision && demand.consentEpoch == member.consentEpoch &&
                    demand.requestedBootId == input.clock && demand.requestedElapsedRealtimeNanos <= input.fromNanos &&
                    (demand.retireBootId != input.clock || demand.retireElapsedRealtimeNanos?.let { input.toNanos < it } == true)
            }
        }
    }

    private fun authenticatesSnapshot(snapshot: SourceAuthorizationSnapshot, generation: Long): Boolean {
        val immutable = snapshot.authorizedMembers.map { demands[it.demandId] ?: return false }
        if (immutable.any { it.sourceKind != source } || immutable.map { it.demandId }.distinct().size != immutable.size) return false
        val expected = SourceBrokerAuthorization.rows(source, generation, snapshot.authorizationRevision, immutable,
            snapshot.effectiveBootId, snapshot.effectiveElapsedRealtimeNanos, snapshot.members.first().effectiveWallTimeMs)
        return expected.sortedBy { it.memberId } == snapshot.members.sortedBy { it.memberId }
    }

    companion object {
        suspend fun load(database: AppDatabase, source: Int, inputs: List<RetainedRadioAuthorityInput>,
            currentClock: String): RetainedAmbientRadioAuthority? {
            val generations = inputs.map { it.registration }.distinct()
            if (generations.size > MAX_CONTEXT_POINTS) throw RetainedRadioDependencyLimitExceeded()
            val dao = database.wifiCapturedFactDao()
            val registrations = mutableListOf<ProviderRegistrationGenerationEntity>()
            val rows = mutableListOf<SourceAuthorizationEntity>()
            for (chunk in generations.chunked(QUERY_CHUNK)) {
                registrations += dao.historyProviderRegistrations(source, chunk, MAX_CONTEXT_DEPENDENCIES - registrations.size + 1)
                rows += dao.historyAuthorizations(source, chunk, MAX_CONTEXT_DEPENDENCIES - rows.size + 1)
                if (registrations.size > MAX_CONTEXT_DEPENDENCIES || rows.size > MAX_CONTEXT_DEPENDENCIES)
                    throw RetainedRadioDependencyLimitExceeded()
            }
            val current = database.sourceBrokerDao().currentPhysicalRegistrationsBounded(source, MAX_CONTEXT_POINTS + 1)
            if (current.size > MAX_CONTEXT_POINTS) throw RetainedRadioDependencyLimitExceeded()
            val demandIds = rows.mapNotNull { it.demandId }.distinct()
            if (demandIds.size > MAX_CONTEXT_DEPENDENCIES) throw RetainedRadioDependencyLimitExceeded()
            val demands = demandIds.chunked(QUERY_CHUNK).flatMap { dao.historyDemands(it, it.size + 1) }
            if (demands.size != demandIds.size) return null
            val snapshots = rows.groupBy { it.registrationGeneration }.mapValues { (_, group) ->
                group.groupBy { it.authorizationRevision }.values.map { it.toAuthorizationSnapshotOrNull() ?: return null }
                    .sortedWith(compareBy<SourceAuthorizationSnapshot> { it.effectiveElapsedRealtimeNanos }.thenBy { it.authorizationRevision })
            }
            val policies = inputs.map { it.policyRevision }.distinct().chunked(QUERY_CHUNK).flatMap {
                database.sourcePolicyDao().policiesAtRevisions(source, it)
            }
            val consents = inputs.map { it.consentEpoch }.distinct().chunked(QUERY_CHUNK).flatMap {
                database.sourcePolicyDao().consentEpochs(source, SourceBrokerPurpose.AMBIENT_PRODUCT, it)
            }
            return RetainedAmbientRadioAuthority(source, currentClock, registrations.associateBy { it.registrationGeneration },
                current, snapshots, demands.associateBy { it.demandId }, policies.associateBy { it.policyRevision },
                consents.associateBy { it.epoch })
        }
    }
}

/** Distinguishes a bounded snapshot from missing or malformed recording authority. */
internal class RetainedRadioDependencyLimitExceeded : IllegalStateException("Radio authority dependency limit exceeded")
