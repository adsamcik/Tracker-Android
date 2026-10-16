package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun AppDatabase.auditPortableActivitySelectedLineages(
	limits: PortableActivityReadLimits,
	logicalIds: List<String>,
	runs: List<SourceServiceRunEntity>,
): List<ActivityCapturedLineage> {
	val result = mutableListOf<ActivityCapturedLineage>()
	var revisions = 0L
	var fragments = 0L
	var evidence = 0L
	for (logicalId in logicalIds) {
		currentCoroutineContext().ensureActive()
		val selected = auditPortableActivityLogicalGroup(limits, logicalId, runs)
		result += selected
		for (lineage in selected) {
			revisions = Math.addExact(revisions, lineage.revisions.size.toLong())
			for (revision in lineage.revisions) {
				fragments = Math.addExact(fragments, revision.fragments.size.toLong())
				evidence = Math.addExact(evidence, revision.evidence.size.toLong())
			}
		}
		if (result.size > limits.maintenance.maximumLogicalWindows ||
			result.size > limits.maintenance.maximumCursors ||
			revisions > limits.maintenance.maximumRevisions
		) {
			portableActivityDependencyOverflow()
		}
		if (fragments > limits.maintenance.maximumTotalFragments ||
			evidence > limits.maintenance.maximumTotalEvidence
		) {
			portableActivityDependencyOverflow()
		}
	}
	if (result.map(ActivityCapturedLineage::logicalWindowId).distinct().size != result.size) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CONFLICTING_ORIGIN_IDENTITY)
	}
	return result
}

internal suspend fun AppDatabase.loadPortableActivityReplacementRuns(
	limits: PortableActivityReadLimits,
	logicalTrackingIds: List<String>,
): List<SourceServiceRunEntity> {
	val result = mutableListOf<SourceServiceRunEntity>()
	logicalTrackingIds.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { identities ->
		var afterLogicalId: String? = null
		var afterStartedAtMs: Long? = null
		var afterRunId: String? = null
		do {
			currentCoroutineContext().ensureActive()
			val page = trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalTrackingIds = identities,
				limit = minOf(ACTIVITY_PORTABLE_READ_PAGE_SIZE, limits.maximumRuns - result.size + 1),
				afterLogicalTrackingId = afterLogicalId,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterRunId,
			)
			result += page
			if (result.size > limits.maximumRuns) {
				portableActivityDependencyOverflow()
			}
			val last = page.lastOrNull()
			afterLogicalId = last?.logicalTrackingId
			afterStartedAtMs = last?.startedAtMs
			afterRunId = last?.serviceRunId
		} while (page.size == ACTIVITY_PORTABLE_READ_PAGE_SIZE)
	}
	if (result.map(SourceServiceRunEntity::serviceRunId).distinct().size != result.size ||
		result.map(SourceServiceRunEntity::logicalTrackingId).toSet() != logicalTrackingIds.toSet()
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return result.sortedWith(ACTIVITY_PORTABLE_RAW_RUN_ORDER)
}

internal suspend fun AppDatabase.loadPortableActivitySessions(
	logicalTrackingIds: List<String>,
): Map<String, LogicalTrackingSessionEntity> {
	val sessions = logicalTrackingIds.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).flatMap { identities ->
		sourceSessionDao().sessions(identities)
	}
	val result = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId)
	if (result.size != sessions.size || result.keys != logicalTrackingIds.toSet()) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return result
}

internal suspend fun AppDatabase.loadPortableActivitySegments(
	limits: PortableActivityReadLimits,
	segmentIds: List<Long>,
): List<SessionSegment> {
	if (segmentIds.size > limits.maximumRuns) {
		portableActivityDependencyOverflow()
	}
	val rows = segmentIds.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).flatMap { ids ->
		trackingHistoryReadDao().segments(ids)
	}
	if (rows.map(SessionSegment::id).distinct().size != rows.size ||
		rows.map(SessionSegment::id).toSet() != segmentIds.toSet()
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return rows
}

internal suspend fun AppDatabase.validatePortableActivityDeletionFences(runs: List<SourceServiceRunEntity>) {
	val scopes = runs.associateWith { run ->
		PortableActivityDeletionScopeDigest.derive(
			run.logicalTrackingId,
			run.serviceRunId,
		).value
	}
	val fences = scopes.values.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).flatMap { digests ->
		trackingHistoryReadDao().deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = digests,
		)
	}
	if (fences.isNotEmpty()) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.DELETED_SCOPE)
	}
}

internal suspend fun AppDatabase.loadPortableActivityManifestDependencies(
	limits: PortableActivityReadLimits,
	runs: List<SourceServiceRunEntity>,
): ActivityPortableManifestDependencies {
	val manifests = mutableListOf<SessionManifestVersionEntity>()
	val sources = mutableListOf<SessionManifestSourceEntity>()
	runs.map(SourceServiceRunEntity::serviceRunId).chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { runIds ->
		currentCoroutineContext().ensureActive()
		val manifestPage = trackingHistoryReadDao().manifests(
			serviceRunIds = runIds,
			limit = limits.maximumTotalManifests - manifests.size + 1,
		)
		manifests += manifestPage
		if (manifests.size > limits.maximumTotalManifests) {
			portableActivityDependencyOverflow()
		}
		val sourcePage = trackingHistoryReadDao().manifestSources(
			serviceRunIds = runIds,
			limit = limits.maximumTotalSources - sources.size + 1,
		)
		sources += sourcePage
		if (sources.size > limits.maximumTotalSources) {
			portableActivityDependencyOverflow()
		}
	}
	val runIds = runs.mapTo(hashSetOf(), SourceServiceRunEntity::serviceRunId)
	val logicalIds = runs.mapTo(hashSetOf(), SourceServiceRunEntity::logicalTrackingId)
	if (manifests.any { manifest -> manifest.serviceRunId !in runIds } ||
		sources.any { source -> source.logicalTrackingId !in logicalIds }
	) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	val manifestRunByKey = manifests.associate { manifest ->
		(manifest.logicalTrackingId to manifest.manifestRevision) to manifest.serviceRunId
	}
	if (manifestRunByKey.size != manifests.size) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	return ActivityPortableManifestDependencies(
		manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
		sourcesByRun = sources.groupBy { source ->
			manifestRunByKey[source.logicalTrackingId to source.manifestRevision]
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		},
	)
}

internal fun validatePortableActivityLogicalManifestUnions(
	logicalTrackingIds: List<String>,
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
) {
	logicalTrackingIds.forEach { logicalTrackingId ->
		val revisionSlices = runs.filter { run -> run.logicalTrackingId == logicalTrackingId }
			.map { run ->
				manifestsByRun[run.serviceRunId].orEmpty()
					.map(SessionManifestVersionEntity::manifestRevision)
			}
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(revisionSlices)) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}
}

internal suspend fun AppDatabase.loadPortableActivityCompleteness(
	limits: PortableActivityReadLimits,
	runIds: List<String>,
): List<SourceSessionCompletenessEntity> = loadPortableActivityBoundedDependencies(limits, runIds) { batch, limit ->
	activityCapturedFactDao().portableCompleteness(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		serviceRunIds = batch,
		limit = limit,
	)
}

internal suspend fun AppDatabase.loadPortableActivityLifecycleActions(
	limits: PortableActivityReadLimits,
	runIds: List<String>,
): List<LifecycleDesiredActionEntity> = loadPortableActivityBoundedDependencies(
	limits, 	runIds, limits.maximumLifecycleActions,
) { batch, limit ->
	activityCapturedFactDao().portableLifecycleActions(
		SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		batch,
		limit,
	)
}

internal suspend fun AppDatabase.loadPortableActivityAuthorizationTimeline(
	limits: PortableActivityReadLimits,
	generations: List<Long>,
): List<SourceAuthorizationEntity> = loadPortableActivityBoundedDependencies(limits,
	generations, limits.maximumCaptureAuthorizations,
) { batch, limit ->
	activityCapturedFactDao().portableAuthorizationsForGenerations(
		SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		batch,
		limit,
	)
}

internal suspend fun AppDatabase.validatePortableActivityAuthorizationRevisions(
	limits: PortableActivityReadLimits,
	authorizationsByKey: Map<PortableActivityAuthorizationKey, List<SourceAuthorizationEntity>>,
): Map<String, SourceDemandEntity> {
	val demandIds = authorizationsByKey.values.asSequence().flatten()
		.mapNotNull(SourceAuthorizationEntity::demandId)
		.distinct()
		.sorted()
		.toList()
	if (demandIds.size > limits.maximumCaptureAuthorizations) {
		portableActivityDependencyOverflow()
	}
	val demands = mutableListOf<SourceDemandEntity>()
	demandIds.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { batch ->
		currentCoroutineContext().ensureActive()
		demands += sourceBrokerDao().demandsByIds(batch)
		if (demands.size > limits.maximumCaptureAuthorizations) {
			portableActivityDependencyOverflow()
		}
	}
	val demandsById = demands.associateBy(SourceDemandEntity::demandId)
	if (demandsById.size != demands.size || demandsById.keys != demandIds.toSet()) {
		abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}
	authorizationsByKey.forEach { (key, rows) ->
		val snapshot = rows.toAuthorizationSnapshotOrNull()
			?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val referencedDemands = rows.mapNotNull { row ->
			row.demandId?.let(demandsById::get)
		}
		if (referencedDemands.map(SourceDemandEntity::demandId).distinct().size !=
			referencedDemands.size
		) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val first = rows.first()
		val expected = SourceBrokerAuthorization.rows(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			registrationGeneration = key.registrationGeneration,
			authorizationRevision = key.authorizationRevision,
			demands = referencedDemands,
			effectiveBootId = snapshot.effectiveBootId,
			effectiveElapsedRealtimeNanos = snapshot.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = first.effectiveWallTimeMs,
		).sortedBy(SourceAuthorizationEntity::memberId)
		if (rows.sortedBy(SourceAuthorizationEntity::memberId) != expected) {
			abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}
	return demandsById
}

internal suspend fun AppDatabase.loadPortableActivityRegistrationPlans(
	limits: PortableActivityReadLimits,
	generations: List<Long>,
): List<ActivityCapturedRegistrationPlanEntity> = loadPortableActivityBoundedDependencies(
	limits, generations,
) { batch, limit ->
	activityCapturedFactDao().portableRegistrationPlans(batch, limit)
}

internal suspend fun AppDatabase.loadPortableActivityDesiredPlans(
	limits: PortableActivityReadLimits,
	revisions: List<Long>,
): List<SourceDesiredPlanEntity> = loadPortableActivityBoundedDependencies(limits, revisions) { batch, limit ->
	activityCapturedFactDao().portableDesiredPlans(
		SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		batch,
		limit,
	)
}

internal suspend fun AppDatabase.loadPortableActivityProviderRegistrations(
	limits: PortableActivityReadLimits,
	generations: List<Long>,
): List<ProviderRegistrationGenerationEntity> = loadPortableActivityBoundedDependencies(
	limits, generations,
) { batch, limit ->
	activityCapturedFactDao().portableProviderRegistrations(
		SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
		batch,
		limit,
	)
}

private suspend fun <Identity, T> AppDatabase.loadPortableActivityBoundedDependencies(
	limits: PortableActivityReadLimits,
	identities: List<Identity>,
	maximum: Int = limits.maximumCompletenessRows,
	load: suspend (List<Identity>, Int) -> List<T>,
): List<T> {
	val result = mutableListOf<T>()
	identities.chunked(ACTIVITY_PORTABLE_SQLITE_BIND_BATCH).forEach { batch ->
		currentCoroutineContext().ensureActive()
		result += load(batch, maximum - result.size + 1)
		if (result.size > maximum) {
			portableActivityDependencyOverflow()
		}
	}
	return result
}

private suspend fun AppDatabase.auditPortableActivityLogicalGroup(
	limits: PortableActivityReadLimits,
	logicalId: String,
	runs: List<SourceServiceRunEntity>,
): List<ActivityCapturedLineage> {
	val scopes = runs.filter { it.logicalTrackingId == logicalId }.map { run ->
		ActivityCapturedSelectedRunScope(
			run.serviceRunId,
			run.sessionSegmentId
				?: abortPortableActivitySnapshot(PortableActivityExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE),
		)
	}
	if (scopes.size > limits.maximumRunsPerEntry) {
		portableActivityDependencyOverflow()
	}
	return auditCapturedActivityFacts(
		limits.maintenance, { currentCoroutineContext().ensureActive() }, false,
		ActivityCapturedAuditScope(logicalId, scopes.associateBy(ActivityCapturedSelectedRunScope::serviceRunId)),
	).lineages
}

private val ACTIVITY_PORTABLE_RAW_RUN_ORDER = compareBy<SourceServiceRunEntity>(
	SourceServiceRunEntity::logicalTrackingId,
)
	.thenBy(SourceServiceRunEntity::startedAtMs)
	.thenBy(SourceServiceRunEntity::serviceRunId)

private const val ACTIVITY_PORTABLE_READ_PAGE_SIZE = 256

internal const val ACTIVITY_PORTABLE_SQLITE_BIND_BATCH = 128

internal data class ActivityPortableManifestDependencies(
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByRun: Map<String, List<SessionManifestSourceEntity>>,
)
