package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity

/** Negative intent check only; uncertain candidates still use the complete source reader. */
internal suspend fun AppDatabase.sourceOnlyHistoryCandidates(
    seeds: List<SessionSegment>,
    sourceKind: Int,
): List<SessionSegment> {
    val runIds = seeds.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
    if (runIds.isEmpty() || runIds.size > MAX_INTENT_ROWS) {
        return seeds
    }
    val read = trackingHistoryReadDao()
    val runs = read.serviceRuns(runIds)
    val manifests = read.manifests(runIds, MAX_INTENT_ROWS + 1)
    val sources = read.manifestSources(runIds, MAX_INTENT_ROWS + 1)
    if (manifests.size > MAX_INTENT_ROWS || sources.size > MAX_INTENT_ROWS) {
        return seeds
    }
    val runsById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
    val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
    val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
    return seeds.filterNot { seed ->
        val run = runsById[seed.serviceRunId]
        run != null && run.ownsSourceOnlyCandidate(seed) && hasVerifiedOtherCapture(
            run, manifestsByRun[run.serviceRunId].orEmpty(), sourcesByManifest, sourceKind)
    }
}

private fun SourceServiceRunEntity.ownsSourceOnlyCandidate(seed: SessionSegment): Boolean =
    logicalTrackingId == seed.logicalTrackingId && sessionSegmentId == seed.id &&
        presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE

private fun hasVerifiedOtherCapture(
    run: SourceServiceRunEntity,
    manifests: List<SessionManifestVersionEntity>,
    sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
    sourceKind: Int,
): Boolean {
    if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)) {
        return false
    }
    val membership = manifests.map { manifest ->
        sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty()
    }
    if (!manifests.zip(membership).all { (manifest, sources) ->
            sources.all { it.hasKnownIntentShape() } && SessionManifestIntegrity.verify(manifest, sources)
        }) {
        return false
    }
    return membership.any { sources -> sources.any { source ->
        source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible &&
            source.sourceKind != sourceKind
    } }
}

private fun SessionManifestSourceEntity.hasKnownIntentShape(): Boolean =
    purpose in SessionManifestPurposeCode.ALL && sourceKind in
        SourceDestinationOwnerEntity.SOURCE_LOCATION..SourceDestinationOwnerEntity.SOURCE_CELL

private const val MAX_INTENT_ROWS = 1_024
