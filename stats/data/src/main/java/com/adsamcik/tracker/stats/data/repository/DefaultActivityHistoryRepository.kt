package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Activity history facade. All dependencies are read in one bounded Room snapshot. */
internal class DefaultActivityHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ActivityHistoryRepository {
	private val importedProductReader = ImportedActivityProductReader(database)
	private val localPortableReader = RoomReadLocalPortableCapturedActivity(
		database,
		laneExecutionAuthority,
	)

	override suspend fun session(segmentId: Long): ActivityHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction {
				val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
					?: return@withTransaction ActivityHistoryQuery.NotFound
				val expansion = expandActivityMembership(listOf(seed))
				val snapshot = loadActivitySnapshot(expansion)
				val entry = ActivityHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
				entry?.let(ActivityHistoryQuery::Found) ?: ActivityHistoryQuery.NotFound
			}
		}
	}

	override suspend fun recent(limit: Int): ActivityHistoryPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction {
				composeRecentActivityHistory(limit)
			}
		}
	}

	private suspend fun composeRecentActivityHistory(limit: Int): ActivityHistoryPage {
		val live = loadRecentActivityHistory(limit)
		if (live.page is ActivityHistoryPage.Failed) return live.page
		val imported = try {
			importedProductReader.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return ActivityHistoryPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val localIdentities = live.logicalTrackingIds.associateBy { logicalId ->
			PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		if (localIdentities.size != live.logicalTrackingIds.size) {
			return ActivityHistoryPage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		val publicLive = (live.page as ActivityHistoryPage.Available).entries
		val hasReadableImports = imported.any { it is ImportedActivityProductEvaluation.Readable }
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(
				hasReadableImports,
				publicLive,
				localIdentities.keys,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return try {
			ActivityHistoryPage.Available(
				ActivityHistoryOriginComposer.compose(
					live = publicLive,
					liveLogicalTrackingIds = live.logicalTrackingIds,
					imported = imported,
					localPortableEntriesByIdentity = localPortableEntries,
					limit = limit,
				),
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			ActivityHistoryPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun loadLocalPortableOwnershipSnapshot(
		hasReadableImports: Boolean,
		localEntries: List<ActivityHistoryEntry>,
		localEntryIdentities: Set<String>,
	): Map<String, PortableActivityEntryV1> {
		if (!hasReadableImports || localEntryIdentities.isEmpty()) return emptyMap()
		if (localEntries.size != localEntryIdentities.size) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		val earliest = localEntries.minOf { it.startTime.raw }
		val latest = localEntries.maxOf { it.endTime.raw }
		if (latest <= earliest) throw ImportedActivityHistoryCompositionFailure()
		return when (val result = localPortableReader.read(
			ExportPortableCapturedActivityRequest(earliest, latest),
		)) {
			is ReadLocalPortableCapturedActivityResult.Ready -> {
				// Seed every overlapping local owner, including bounded rows outside the visible page.
				val completeSnapshot = result.envelope.entries.associateBy { it.identity.value }
				if (completeSnapshot.size != result.envelope.entries.size ||
					!completeSnapshot.keys.containsAll(localEntryIdentities)
				) {
					throw ImportedActivityHistoryCompositionFailure()
				}
				completeSnapshot
			}
			is ReadLocalPortableCapturedActivityResult.Unverifiable,
			ReadLocalPortableCapturedActivityResult.StorageUnavailable,
			ReadLocalPortableCapturedActivityResult.NoEntries ->
				throw ImportedActivityHistoryCompositionFailure()
		}
	}

	private suspend fun loadRecentActivityHistory(limit: Int): LiveActivityHistoryPage {
		val logicalIdByEntryKey = linkedMapOf<ActivityHistoryEntryKey, String>()
		val page = ActivityRecentHistoryPager.collect(
			limit = limit,
			pageSize = ACTIVITY_CANDIDATE_PAGE_SIZE,
			candidateBudget = MAX_ACTIVITY_CANDIDATE_SCAN,
			loadPage = { pageLimit, beforeStartTimeMs, beforeSegmentId ->
				database.activityCapturedFactDao().logicalHistoryCandidatePage(
					limit = pageLimit,
					beforeStartTimeMs = beforeStartTimeMs,
					beforeSegmentId = beforeSegmentId,
				)
			},
			compose = { seeds ->
				val expansion = expandActivityMembership(seeds)
				val snapshot = loadActivitySnapshot(expansion)
				if (snapshot.overflow) {
					ActivityHistoryPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
				} else {
					val candidateIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
					val composed = ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
							.filter { it.logicalTrackingId in candidateIds }
							.sortedWith(
								compareByDescending<ComposedActivityEntry> { it.recencyStartTimeMs }
									.thenByDescending { it.recencySegmentId },
							)
					composed.forEach { item ->
						logicalIdByEntryKey[item.entry.key] = item.logicalTrackingId
					}
					ActivityHistoryPage.Available(composed.map(ComposedActivityEntry::entry))
				}
			},
		)
		val returnedLogicalIds = when (page) {
			is ActivityHistoryPage.Available -> page.entries.mapNotNullTo(linkedSetOf()) { entry ->
				logicalIdByEntryKey[entry.key]
			}
			is ActivityHistoryPage.Failed -> emptySet()
		}
		val exactPage = if (page is ActivityHistoryPage.Available &&
			returnedLogicalIds.size != page.entries.size
		) {
			ActivityHistoryPage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		} else {
			page
		}
		return LiveActivityHistoryPage(exactPage, returnedLogicalIds)
	}

	private suspend fun expandActivityMembership(
		seeds: List<SessionSegment>,
	): ActivityMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank)
			.distinct()
		if (logicalIds.isEmpty()) return ActivityMembershipExpansion(seeds, emptyMap(), false)
		val runs = mutableListOf<SourceServiceRunEntity>()
		var afterLogicalId: String? = null
		var afterStartedAtMs: Long? = null
		var afterRunId: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_ACTIVITY_LOGICAL_MEMBERS - runs.size
			val pageLimit = minOf(ACTIVITY_MEMBER_PAGE_SIZE, remaining + 1)
			val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalTrackingIds = logicalIds,
				limit = pageLimit,
				afterLogicalTrackingId = afterLogicalId,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterRunId,
			)
			if (page.size > remaining) {
				return ActivityMembershipExpansion(seeds, emptyMap(), overflow = true)
			}
			if (page.isEmpty()) break
			runs += page
			val last = page.last()
			afterLogicalId = last.logicalTrackingId
			afterStartedAtMs = last.startedAtMs
			afterRunId = last.serviceRunId
			if (page.size < pageLimit) break
		}
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loadedSegments = if (segmentIds.isEmpty()) {
			emptyList()
		} else {
			database.trackingHistoryReadDao().segments(segmentIds)
		}
		val segments = (loadedSegments + seeds).distinctBy(SessionSegment::id)
		val segmentsById = segments.associateBy(SessionSegment::id)
		val failures = linkedMapOf<String, ActivityHistoryCause>()
		val runsByLogical = runs.groupBy(SourceServiceRunEntity::logicalTrackingId)
		logicalIds.forEach { logicalId ->
			if (runsByLogical[logicalId].isNullOrEmpty()) {
				failures[logicalId] = ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(segmentsById::get)
			if (segment == null || segment.serviceRunId != run.serviceRunId ||
				segment.logicalTrackingId != run.logicalTrackingId
			) failures[run.logicalTrackingId] = ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
		seeds.forEach { seed ->
			val logicalId = seed.logicalTrackingId ?: return@forEach
			val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
			if (run == null || run.logicalTrackingId != logicalId || run.sessionSegmentId != seed.id) {
				failures[logicalId] = ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		return ActivityMembershipExpansion(segments, failures, overflow = false)
	}

	@Suppress("LongMethod")
	private suspend fun loadActivitySnapshot(
		expansion: ActivityMembershipExpansion,
	): ActivityHistorySnapshot {
		if (expansion.overflow) return ActivityHistorySnapshot.overflow(expansion)
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
			.filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) return ActivityHistorySnapshot.empty(expansion)
		val readDao = database.trackingHistoryReadDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_ACTIVITY_MANIFESTS + 1)
		val manifestSources = readDao.manifestSources(runIds, MAX_ACTIVITY_MANIFEST_SOURCES + 1)
		val policies = readDao.policiesForServiceRuns(ACTIVITY_SOURCE, runIds)
		val completeness = readDao.completeness(runIds, MAX_ACTIVITY_COMPLETENESS + 1)
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank).distinct()
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factDao = database.activityCapturedFactDao()
		val revisionLoad = loadActivityRevisionPages(runIds, logicalIds)
		val revisions = revisionLoad.revisions
		currentCoroutineContext().ensureActive()
		val windowIds = revisions.map(ActivityCapturedWindowRevisionEntity::logicalWindowId).distinct()
		val cursors = if (windowIds.isEmpty()) emptyList() else {
			factDao.historyCursors(windowIds, MAX_ACTIVITY_CURSORS + 1)
		}
		val fragments = if (windowIds.isEmpty()) emptyList() else {
			factDao.historyFragments(windowIds, MAX_ACTIVITY_FRAGMENTS + 1)
		}
		val evidence = if (windowIds.isEmpty()) emptyList() else {
			factDao.historyEvidence(windowIds, MAX_ACTIVITY_EVIDENCE + 1)
		}
		currentCoroutineContext().ensureActive()
		val activityCompleteness = completeness.filter { it.sourceKind == ACTIVITY_SOURCE }
		val sourceInstanceIds = sequenceOf(
			revisions.asSequence().map(ActivityCapturedWindowRevisionEntity::sourceInstanceId),
			activityCompleteness.asSequence().filter { it.registrationGeneration > 0L }
				.map(SourceSessionCompletenessEntity::sourceInstanceId),
		).flatten().distinct().toList()
		val registrationGenerations = sequenceOf(
			revisions.asSequence().map(ActivityCapturedWindowRevisionEntity::registrationGeneration),
			activityCompleteness.asSequence().map(SourceSessionCompletenessEntity::registrationGeneration)
				.filter { it > 0L },
		).flatten().distinct().toList()
		val registrationPlans = if (sourceInstanceIds.isEmpty() || registrationGenerations.isEmpty()) {
			emptyList()
		} else {
			factDao.historyRegistrationPlans(
				sourceInstanceIds,
				registrationGenerations,
				MAX_ACTIVITY_REGISTRATION_PLANS + 1,
			)
		}
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val acquisitionPlanRevisions = if (planRevisions.isEmpty()) emptyList() else {
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_ACTIVITY_ACQUISITION_PLANS + 1)
		}
		val desiredPlans = if (planRevisions.isEmpty()) emptyList() else {
			factDao.historyDesiredPlans(ACTIVITY_SOURCE, planRevisions, MAX_ACTIVITY_DESIRED_PLANS + 1)
		}
		val providerRegistrations = if (registrationGenerations.isEmpty()) emptyList() else {
			factDao.historyProviderRegistrations(
				ACTIVITY_SOURCE, registrationGenerations, MAX_ACTIVITY_PROVIDER_REGISTRATIONS + 1,
			)
		}
		val authorizations = if (registrationGenerations.isEmpty()) emptyList() else {
			factDao.historyAuthorizations(
				ACTIVITY_SOURCE, registrationGenerations, MAX_ACTIVITY_AUTHORIZATIONS + 1,
			)
		}
		val consentEpochs = manifestSources.asSequence()
			.filter(::isActivityCaptureMembership)
			.map(SessionManifestSourceEntity::consentEpoch)
			.distinct().toList()
		val consents = if (consentEpochs.isEmpty()) emptyList() else {
			database.sourcePolicyDao().consentEpochs(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				consentEpochs,
			)
		}
		val deletionDigests = segments.mapNotNull { segment ->
			val logicalId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val runId = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logicalId == null || runId == null) null else {
				SourceDeletionFenceEntity.logicalServiceRunIdentity(
					ACTIVITY_SOURCE,
					SessionManifestPurposeCode.SESSION_CAPTURE,
					logicalId,
					runId,
				)
			}
		}.distinct()
		val deletionFences = if (deletionDigests.isEmpty()) emptyList() else {
			readDao.deletionFences(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				deletionDigests,
			)
		}
		currentCoroutineContext().ensureActive()
		val lanes = readDao.productLanesForServiceRuns(
			ACTIVITY_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			runIds,
		)
		val afterOrdinal = lanes.mapNotNull { it.activationOrdinal.takeIf { ordinal -> ordinal > 0L } }
			.minOrNull()?.minus(1L)
		val throughOrdinal = sequenceOf(
			evidence.maxOfOrNull(ActivityCapturedEvidenceEntity::sourceAdmissionOrdinal),
			completeness.asSequence().filter { it.sourceKind == ACTIVITY_SOURCE }
				.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		).filterNotNull().maxOrNull()
		val terminalFailures = if (afterOrdinal == null || throughOrdinal == null ||
			afterOrdinal >= throughOrdinal
		) emptyList() else {
			readDao.terminalFailuresForServiceRuns(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				runIds,
				afterOrdinal,
				throughOrdinal,
				MAX_ACTIVITY_TERMINAL_FAILURES + 1,
			)
		}
		val overflow = revisionLoad.overflow ||
			manifests.size > MAX_ACTIVITY_MANIFESTS ||
			manifestSources.size > MAX_ACTIVITY_MANIFEST_SOURCES ||
			completeness.size > MAX_ACTIVITY_COMPLETENESS ||
			revisions.size > MAX_ACTIVITY_REVISIONS || cursors.size > MAX_ACTIVITY_CURSORS ||
			fragments.size > MAX_ACTIVITY_FRAGMENTS || evidence.size > MAX_ACTIVITY_EVIDENCE ||
			registrationPlans.size > MAX_ACTIVITY_REGISTRATION_PLANS ||
			acquisitionPlanRevisions.size > MAX_ACTIVITY_ACQUISITION_PLANS ||
			desiredPlans.size > MAX_ACTIVITY_DESIRED_PLANS ||
			providerRegistrations.size > MAX_ACTIVITY_PROVIDER_REGISTRATIONS ||
			authorizations.size > MAX_ACTIVITY_AUTHORIZATIONS ||
			terminalFailures.size > MAX_ACTIVITY_TERMINAL_FAILURES
		return ActivityHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.take(MAX_ACTIVITY_MANIFESTS)
				.groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = manifestSources.take(MAX_ACTIVITY_MANIFEST_SOURCES).groupBy { source ->
				ActivityManifestKey(source.logicalTrackingId, source.manifestRevision)
			},
			policies = policies.associateBy(SourcePolicyEntity::policyRevision),
			consents = consents.associateBy(SourceConsentEpochEntity::epoch),
			completenessByRun = completeness.take(MAX_ACTIVITY_COMPLETENESS)
				.groupBy(SourceSessionCompletenessEntity::serviceRunId),
			revisions = revisions.take(MAX_ACTIVITY_REVISIONS),
			cursors = cursors.take(MAX_ACTIVITY_CURSORS),
			fragmentsByRevision = fragments.take(MAX_ACTIVITY_FRAGMENTS).groupBy(::revisionKey),
			evidenceByRevision = evidence.take(MAX_ACTIVITY_EVIDENCE).groupBy(::revisionKey),
			registrationPlans = registrationPlans.take(MAX_ACTIVITY_REGISTRATION_PLANS)
				.associateBy { it.sourceInstanceId to it.registrationGeneration },
			acquisitionPlanRevisions = acquisitionPlanRevisions.take(MAX_ACTIVITY_ACQUISITION_PLANS)
				.associateBy(AcquisitionPlanRevisionEntity::revision),
			desiredPlans = desiredPlans.take(MAX_ACTIVITY_DESIRED_PLANS)
				.associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providerRegistrations.take(MAX_ACTIVITY_PROVIDER_REGISTRATIONS)
				.associateBy(ProviderRegistrationGenerationEntity::registrationGeneration),
			authorizationsByRegistration = authorizations.take(MAX_ACTIVITY_AUTHORIZATIONS)
				.groupBy(SourceAuthorizationEntity::registrationGeneration),
			deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { it.scopeIdentityDigest },
			lanes = lanes,
			terminalFailures = terminalFailures.take(MAX_ACTIVITY_TERMINAL_FAILURES),
			evidenceState = database.sourceEvidenceStateDao().get(),
			overflow = overflow,
		)
	}

	private suspend fun loadActivityRevisionPages(
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
	): ActivityRevisionLoad {
		val revisions = mutableListOf<ActivityCapturedWindowRevisionEntity>()
		var cursor: ActivityRevisionKey? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_ACTIVITY_REVISIONS - revisions.size
			val pageLimit = minOf(ACTIVITY_REVISION_PAGE_SIZE, remaining + 1)
			val page = database.activityCapturedFactDao().historyRevisionPage(
				serviceRunIds = serviceRunIds,
				logicalTrackingIds = logicalTrackingIds,
				limit = pageLimit,
				afterWriterProjectionId = cursor?.writerProjectionId,
				afterWriterProjectionVersion = cursor?.writerProjectionVersion,
				afterLogicalWindowId = cursor?.logicalWindowId,
				afterSemanticRevision = cursor?.semanticRevision,
			)
			if (page.isEmpty()) return ActivityRevisionLoad(revisions, overflow = false)
			if (page.size > remaining) return ActivityRevisionLoad(revisions, overflow = true)
			revisions += page
			cursor = revisionKey(page.last())
			if (page.size < pageLimit) return ActivityRevisionLoad(revisions, overflow = false)
		}
	}
}

internal data class ActivityRevisionLoad(
	val revisions: List<ActivityCapturedWindowRevisionEntity>,
	val overflow: Boolean,
)

private data class LiveActivityHistoryPage(
	val page: ActivityHistoryPage,
	val logicalTrackingIds: Set<String>,
)

internal data class ActivityMembershipExpansion(
	val segments: List<SessionSegment>,
	val failures: Map<String, ActivityHistoryCause>,
	val overflow: Boolean,
)

@Suppress("LongParameterList")
internal data class ActivityHistorySnapshot(
	val expansion: ActivityMembershipExpansion,
	val sessions: Map<String, LogicalTrackingSessionEntity>,
	val runs: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<ActivityManifestKey, List<SessionManifestSourceEntity>>,
	val policies: Map<Long, SourcePolicyEntity>,
	val consents: Map<Long, SourceConsentEpochEntity>,
	val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
	val revisions: List<ActivityCapturedWindowRevisionEntity>,
	val cursors: List<ActivityCapturedWindowCursorEntity>,
	val fragmentsByRevision: Map<ActivityRevisionKey, List<ActivityCapturedFragmentEntity>>,
	val evidenceByRevision: Map<ActivityRevisionKey, List<ActivityCapturedEvidenceEntity>>,
	val registrationPlans: Map<Pair<String, Long>, ActivityCapturedRegistrationPlanEntity>,
	val acquisitionPlanRevisions: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val deletionFenceDigests: Set<String>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState?,
	val overflow: Boolean,
) {
	companion object {
		fun empty(expansion: ActivityMembershipExpansion) = ActivityHistorySnapshot(
			expansion = expansion,
			sessions = emptyMap(),
			runs = emptyMap(),
			manifestsByRun = emptyMap(),
			sourcesByManifest = emptyMap(),
			policies = emptyMap(),
			consents = emptyMap(),
			completenessByRun = emptyMap(),
			revisions = emptyList(),
			cursors = emptyList(),
			fragmentsByRevision = emptyMap(),
			evidenceByRevision = emptyMap(),
			registrationPlans = emptyMap(),
			acquisitionPlanRevisions = emptyMap(),
			desiredPlans = emptyMap(),
			providerRegistrations = emptyMap(),
			authorizationsByRegistration = emptyMap(),
			deletionFenceDigests = emptySet(),
			lanes = emptyList(),
			terminalFailures = emptyList(),
			evidenceState = null,
			overflow = false,
		)

		fun overflow(expansion: ActivityMembershipExpansion) = empty(expansion).copy(overflow = true)
	}
}

internal data class ActivityManifestKey(val logicalTrackingId: String, val manifestRevision: Long)

internal data class ActivityRevisionKey(
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val logicalWindowId: String,
	val semanticRevision: Long,
)

internal fun revisionKey(entity: ActivityCapturedWindowRevisionEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

private fun revisionKey(entity: ActivityCapturedFragmentEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

private fun revisionKey(entity: ActivityCapturedEvidenceEntity) = ActivityRevisionKey(
	entity.writerProjectionId,
	entity.writerProjectionVersion,
	entity.logicalWindowId,
	entity.semanticRevision,
)

internal data class ComposedActivityEntry(
	val logicalTrackingId: String,
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
	val entry: com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry,
)

internal fun isActivityCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == ACTIVITY_SOURCE &&
		source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible

internal const val ACTIVITY_SOURCE = SourceDestinationOwnerEntity.SOURCE_ACTIVITY
private const val ACTIVITY_CANDIDATE_PAGE_SIZE = 64
private const val ACTIVITY_MEMBER_PAGE_SIZE = 32
private const val ACTIVITY_REVISION_PAGE_SIZE = 64
private const val MAX_ACTIVITY_HISTORY_RESULTS = 100
private const val MAX_ACTIVITY_CANDIDATE_SCAN = 128
private const val MAX_ACTIVITY_LOGICAL_MEMBERS = 128
private const val MAX_ACTIVITY_MANIFESTS = 512
private const val MAX_ACTIVITY_MANIFEST_SOURCES = 4_096
private const val MAX_ACTIVITY_COMPLETENESS = 512
private const val MAX_ACTIVITY_REVISIONS = 512
private const val MAX_ACTIVITY_CURSORS = 512
private const val MAX_ACTIVITY_FRAGMENTS = 8_192
private const val MAX_ACTIVITY_EVIDENCE = 16_384
private const val MAX_ACTIVITY_REGISTRATION_PLANS = 512
private const val MAX_ACTIVITY_ACQUISITION_PLANS = 512
private const val MAX_ACTIVITY_DESIRED_PLANS = 512
private const val MAX_ACTIVITY_PROVIDER_REGISTRATIONS = 512
private const val MAX_ACTIVITY_AUTHORIZATIONS = 4_096
private const val MAX_ACTIVITY_TERMINAL_FAILURES = 512
