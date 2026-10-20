package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSessionAuthorityKey
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity

/** Only authority actually referenced by the accepted prefix can invalidate that prefix. */
internal data class SavedLocationPrefixAuthority(
    val keys: Set<LocationSessionAuthorityKey>,
    val policies: Map<Long, SourcePolicyEntity?>,
    val consents: Map<Long, SourceConsentEpochEntity?>,
    val manifests: Map<Long, Pair<SessionManifestVersionEntity?, List<SessionManifestSourceEntity>>>,
    val runIdentity: Pair<String, Long?>?,
    val retainedLeases: Map<Long, Boolean>,
) {
    suspend fun refreshed(database: AppDatabase, logicalId: String, runId: String) =
        read(database, logicalId, runId, keys)

    /** New points extend the dependency set; unchanged keys need no extra metadata queries. */
    suspend fun extend(database: AppDatabase, logicalId: String, runId: String,
        added: List<LocationSessionAuthorityKey>): SavedLocationPrefixAuthority {
        val missing = added.toSet() - keys
        if (missing.isEmpty()) {
            return this
        }
        val extra = read(database, logicalId, runId, missing)
        return copy(keys = keys + missing, policies = policies + extra.policies,
            consents = consents + extra.consents, manifests = manifests + extra.manifests,
            runIdentity = extra.runIdentity, retainedLeases = retainedLeases + extra.retainedLeases)
    }

    companion object {
        suspend fun read(database: AppDatabase, logicalId: String, runId: String,
            keys: Set<LocationSessionAuthorityKey>): SavedLocationPrefixAuthority {
            val policyDao = database.sourcePolicyDao()
            val sessionDao = database.sourceSessionDao()
            val run = sessionDao.serviceRun(runId)
            return SavedLocationPrefixAuthority(keys,
                keys.map { it.policyRevision }.distinct().associateWith { policyDao.policyAtRevision(it, LOCATION) },
                keys.map { it.consentEpoch }.distinct().associateWith {
                    policyDao.consentEpoch(LOCATION, SourceBrokerPurpose.SESSION_CAPTURE, it)
                },
                keys.map { it.manifestRevision }.distinct().associateWith {
                    sessionDao.manifest(logicalId, it) to sessionDao.manifestSources(logicalId, it)
                }, run?.let { it.logicalTrackingId to it.sessionSegmentId },
                keys.map { it.leaseGeneration }.distinct().associateWith {
                    run != null && SessionManifestIntegrity.hasRetainedLeaseGeneration(run, it)
                })
        }
        private const val LOCATION = 1
    }
}
