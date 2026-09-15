package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryPage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Wi-Fi facade. Every call reads one bounded Room transaction snapshot. */
internal class DefaultWifiHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val importedProductEvaluator: ImportedWifiProductEvaluator,
	private val localPortableReader: ReadLocalPortableCapturedWifi,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WifiHistoryRepository {
	override suspend fun session(segmentId: Long): WifiHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction {
				val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
					?: return@withTransaction WifiHistoryQuery.NotFound
				val snapshot = loadSnapshot(expandMembership(listOf(seed)))
				WifiHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
					?.let(WifiHistoryQuery::Found) ?: WifiHistoryQuery.NotFound
			}
		}
	}

	override suspend fun imported(selection: WifiImportedHistorySelectionKey): WifiHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				try {
					val evaluation = importedProductEvaluator.selectIdentityInTransaction(selection)
						?: return@withTransaction WifiHistoryQuery.NotFound
					val readable = evaluation as? ImportedWifiProductEvaluation.Readable
					val collision = readable?.collidingLocalLogicalTrackingId
					val originConflict = if (collision == null) {
						false
					} else {
						val colliding = requireNotNull(readable)
						when (val local = localPortableReader.readInTransaction(
							ExportPortableCapturedWifiRequest(collision),
						)) {
							is ReadLocalPortableCapturedWifiResult.Ready ->
								!colliding.isReExportable || colliding.entry != local.entry
							is ReadLocalPortableCapturedWifiResult.Outcome -> true
						}
					}
					WifiHistoryQuery.Found(evaluation.toPublicWifiEntry(originConflict))
				} catch (cancelled: kotlinx.coroutines.CancellationException) {
					throw cancelled
				} catch (_: ArithmeticException) {
					WifiHistoryQuery.Failed(WifiHistoryCause.VALUE_OVERFLOW)
				} catch (_: RuntimeException) {
					WifiHistoryQuery.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
				}
			}
		}

	override suspend fun recent(limit: Int): WifiHistoryPage {
		require(limit in 1..MAX_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction { composeRecentHistory(limit) }
		}
	}

	private suspend fun composeRecentHistory(limit: Int): WifiHistoryPage {
		val live = loadRecentLocal(limit)
		if (live.page is WifiHistoryPage.Failed) return live.page
		val imported = try {
			importedProductEvaluator.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return WifiHistoryPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return WifiHistoryPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val collisions = imported.filterIsInstance<ImportedWifiProductEvaluation.Readable>()
			.mapNotNull(ImportedWifiProductEvaluation.Readable::collidingLocalLogicalTrackingId)
			.distinct()
		if (collisions.size > MAX_EXACT_ORIGIN_COLLISIONS) {
			return WifiHistoryPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortable = linkedMapOf<String, PortableCapturedWifiEntryV1>()
		try {
			for (logicalId in collisions) {
				currentCoroutineContext().ensureActive()
				when (val read = localPortableReader.readInTransaction(
					ExportPortableCapturedWifiRequest(logicalId),
				)) {
					is ReadLocalPortableCapturedWifiResult.Ready -> localPortable[logicalId] = read.entry
					is ReadLocalPortableCapturedWifiResult.Outcome ->
						return WifiHistoryPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
				}
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return WifiHistoryPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		return try {
			WifiHistoryPage.Available(
				WifiHistoryOriginComposer.compose(live.entries, imported, localPortable, limit),
			)
		} catch (_: ImportedWifiHistoryCompositionFailure) {
			WifiHistoryPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		} catch (_: ArithmeticException) {
			WifiHistoryPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			WifiHistoryPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun loadRecentLocal(limit: Int): LiveWifiHistoryPage {
		val accepted = linkedMapOf<String, ComposedWifiEntry>()
		var beforeStart: Long? = null
		var beforeId: Long? = null
		var scanned = 0
		while (accepted.size < limit && scanned < MAX_CANDIDATE_SCAN) {
			currentCoroutineContext().ensureActive()
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, MAX_CANDIDATE_SCAN - scanned)
			val candidates = database.wifiCapturedFactDao().logicalHistoryCandidatePage(
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
				WIFI_SOURCE,
				SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				pageLimit,
				beforeStart,
				beforeId,
			)
			if (candidates.isEmpty()) break
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = loadSnapshot(expandMembership(seeds))
			if (snapshot.overflow) {
				return LiveWifiHistoryPage(
					WifiHistoryPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED),
					emptyList(),
				)
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			WifiHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStart = last.logicalRecencyStartMs
			beforeId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) break
		}
		if (accepted.size < limit && scanned >= MAX_CANDIDATE_SCAN) {
			return LiveWifiHistoryPage(
				WifiHistoryPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED),
				emptyList(),
			)
		}
		val entries = accepted.values.sortedWith(
			compareByDescending<ComposedWifiEntry> { it.recencyStartTimeMs }
				.thenByDescending { it.recencySegmentId },
		).take(limit)
		return LiveWifiHistoryPage(
			WifiHistoryPage.Available(entries.map(ComposedWifiEntry::entry)),
			entries,
		)
	}

	private suspend fun expandMembership(seeds: List<SessionSegment>): WifiMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank).distinct()
		if (logicalIds.isEmpty()) return WifiMembershipExpansion(seeds, emptyMap())
		val runs = mutableListOf<SourceServiceRunEntity>()
		var cursor: ServiceRunCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_LOGICAL_MEMBERS - runs.size
			val pageLimit = minOf(MEMBER_PAGE_SIZE, remaining + 1)
			val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalIds, pageLimit, cursor?.logicalId, cursor?.startedAtMs, cursor?.serviceRunId,
			)
			if (page.size > remaining) {
				return WifiMembershipExpansion(seeds,
					logicalIds.associateWith { WifiHistoryCause.READ_BUDGET_EXCEEDED }, overflow = true)
			}
			if (page.isEmpty()) break
			val next = ServiceRunCursor(page.last())
			check(cursor == null || next > cursor) { "Wi-Fi member cursor did not advance" }
			runs += page
			cursor = next
			if (page.size < pageLimit) break
		}
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loaded = if (segmentIds.isEmpty()) emptyList() else
			database.trackingHistoryReadDao().segments(segmentIds)
		val segments = (loaded + seeds).distinctBy(SessionSegment::id)
		val byId = segments.associateBy(SessionSegment::id)
		val failures = linkedMapOf<String, WifiHistoryCause>()
		logicalIds.forEach { logical ->
			if (runs.none { it.logicalTrackingId == logical }) {
				failures[logical] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(byId::get)
			if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) failures[run.logicalTrackingId] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
			if (run == null || run.logicalTrackingId != logical || run.sessionSegmentId != seed.id) {
				failures[logical] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		return WifiMembershipExpansion(segments, failures)
	}

	@Suppress("LongMethod")
	private suspend fun loadSnapshot(expansion: WifiMembershipExpansion): WifiHistorySnapshot {
		if (expansion.overflow) return WifiHistorySnapshot.empty(expansion).copy(overflow = true)
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) return WifiHistorySnapshot.empty(expansion)
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		val readDao = database.trackingHistoryReadDao()
		val factDao = database.wifiCapturedFactDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_MANIFESTS + 1)
		val sources = readDao.manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		val policies = factDao.historyPolicies(WIFI_SOURCE, runIds, MAX_POLICIES + 1)
		val completeness = readDao.completeness(runIds, MAX_COMPLETENESS + 1)
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factLoad = loadFactPages(runIds, logicalIds)
		val revisions = factLoad.revisions
		currentCoroutineContext().ensureActive()
		val scopedCursors = factDao.historyCursorsForScopes(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			runIds,
			logicalIds,
			MAX_CURSORS + 1,
		)
		val factIds = revisions.map(WifiCapturedFactRevisionEntity::logicalFactId).distinct()
		val referencedCursorLoad = loadChunked(factIds, MAX_CURSORS) { ids, limit ->
			factDao.historyCursors(ids, limit)
		}
		val referencedCursors = referencedCursorLoad.rows
		val cursors = (scopedCursors + referencedCursors).distinctBy { cursor ->
			Triple(cursor.writerProjectionId, cursor.writerProjectionVersion, cursor.logicalFactId)
		}
		val generations = factDao.historyDeletionGenerations(logicalIds, runIds,
			MAX_DELETION_GENERATIONS + 1)
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val planHeaders = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_PLANS + 1)
		val desiredPlans = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyDesiredPlans(WIFI_SOURCE, planRevisions, MAX_PLANS + 1)
		val admissions = factDao.historyAdmissionWal(
			WIFI_SOURCE, runIds, SourceBrokerPurpose.MASK_SESSION_CAPTURE, MAX_ADMISSION_ROWS + 1,
		)
		val registrationGenerations = (revisions.map(WifiCapturedFactRevisionEntity::registrationGeneration) +
			admissions.map { it.registrationGeneration }).distinct()
		val providerLoad = loadChunked(registrationGenerations, MAX_PROVIDERS) { ids, limit ->
			factDao.historyProviderRegistrations(WIFI_SOURCE, ids, limit)
		}
		val providers = providerLoad.rows
		val authorizationLoad = loadChunked(registrationGenerations, MAX_AUTHORIZATIONS) { ids, limit ->
			factDao.historyAuthorizations(WIFI_SOURCE, ids, limit)
		}
		val authorizations = authorizationLoad.rows
		val demandIds = authorizations.mapNotNull { it.demandId }.distinct()
		val demandLoad = loadChunked(demandIds, MAX_DEMANDS) { ids, limit ->
			factDao.historyDemands(ids, limit)
		}
		val demands = demandLoad.rows
		val consentEpochs = sources.filter(::isWifiCaptureMembership)
			.map(SessionManifestSourceEntity::consentEpoch).distinct()
		val consentLoad = loadChunked(consentEpochs, MAX_CONSENTS) { epochs, limit ->
			factDao.historyConsentEpochs(
				WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, epochs, limit,
			)
		}
		val consents = consentLoad.rows
		val actions = factDao.historyStartActions(WIFI_SOURCE, runIds, MAX_ACTIONS + 1)
		val scopePairs = segments.mapNotNull { segment ->
			val logical = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val run = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logical == null || run == null) null else logical to run
		}
		val digestToPair = scopePairs.associateBy { (logical, run) ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logical, run,
			)
		}
		val fences = if (digestToPair.isEmpty()) emptyList() else factDao.historyDeletionFences(
			WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digestToPair.keys.toList(),
			MAX_DELETION_FENCES + 1,
		)
		val lanes = factDao.historyProductLanes(
			WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, runIds, MAX_LANES + 1,
		)
		val through = maxOfOrNull(
			maxOfOrNull(
				revisions.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal),
				completeness.filter { it.sourceKind == WIFI_SOURCE }
					.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull(),
			),
			admissions.maxOfOrNull { it.admissionOrdinal },
		)
		val after = lanes.minOfOrNull { it.activationOrdinal }?.minus(1L)
		val failures = if (after == null || through == null || after >= through) emptyList() else
			readDao.terminalFailuresForServiceRuns(
				WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, runIds,
				after, through, MAX_TERMINAL_FAILURES + 1,
			)
		val evidence = database.sourceEvidenceStateDao().get()
		currentCoroutineContext().ensureActive()
		val overflow = factLoad.overflow || referencedCursorLoad.overflow || providerLoad.overflow ||
			authorizationLoad.overflow || demandLoad.overflow || consentLoad.overflow ||
			runs.size > MAX_LOGICAL_MEMBERS ||
			sessions.size > MAX_SESSIONS || manifests.size > MAX_MANIFESTS ||
			sources.size > MAX_MANIFEST_SOURCES || completeness.size > MAX_COMPLETENESS ||
			policies.size > MAX_POLICIES || consents.size > MAX_CONSENTS ||
			cursors.size > MAX_CURSORS || generations.size > MAX_DELETION_GENERATIONS ||
			planHeaders.size > MAX_PLANS || desiredPlans.size > MAX_PLANS ||
			providers.size > MAX_PROVIDERS || authorizations.size > MAX_AUTHORIZATIONS ||
			demands.size > MAX_DEMANDS || actions.size > MAX_ACTIONS ||
			admissions.size > MAX_ADMISSION_ROWS ||
			fences.size > MAX_DELETION_FENCES || lanes.size > MAX_LANES ||
			failures.size > MAX_TERMINAL_FAILURES
		return WifiHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.take(MAX_MANIFESTS).groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.take(MAX_MANIFEST_SOURCES).groupBy {
				WifiManifestKey(it.logicalTrackingId, it.manifestRevision)
			},
			policies = policies.take(MAX_POLICIES).associateBy(SourcePolicyEntity::policyRevision),
			consents = consents.take(MAX_CONSENTS).associateBy(SourceConsentEpochEntity::epoch),
			completenessByRun = completeness.take(MAX_COMPLETENESS).groupBy { it.serviceRunId },
			revisions = revisions.take(MAX_FACT_REVISIONS),
			cursors = cursors.take(MAX_CURSORS),
			deletionGenerations = generations.take(MAX_DELETION_GENERATIONS)
				.associateBy { it.logicalTrackingId to it.serviceRunId },
			deletedScopes = fences.take(MAX_DELETION_FENCES)
				.mapNotNull { digestToPair[it.scopeIdentityDigest] }.toSet(),
			planHeaders = planHeaders.take(MAX_PLANS).associateBy { it.revision },
			desiredPlans = desiredPlans.take(MAX_PLANS).associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providers.take(MAX_PROVIDERS).associateBy { it.registrationGeneration },
			authorizationsByRegistration = authorizations.take(MAX_AUTHORIZATIONS)
				.groupBy { it.registrationGeneration },
			demands = demands.take(MAX_DEMANDS).associateBy(SourceDemandEntity::demandId),
			startActions = actions.take(MAX_ACTIONS),
			admissions = admissions.take(MAX_ADMISSION_ROWS),
			lanes = lanes.take(MAX_LANES),
			terminalFailures = failures.take(MAX_TERMINAL_FAILURES),
			evidenceState = evidence,
			overflow = overflow,
		)
	}

	private suspend fun loadFactPages(
		runIds: List<String>,
		logicalIds: List<String>,
	): WifiFactLoad {
		val facts = mutableListOf<WifiCapturedFactRevisionEntity>()
		var cursor: FactCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_FACT_REVISIONS - facts.size
			val pageLimit = minOf(FACT_PAGE_SIZE, remaining + 1)
			val page = database.wifiCapturedFactDao().historyRevisionPage(
				runIds, logicalIds, pageLimit, cursor?.projectionId, cursor?.projectionVersion,
				cursor?.logicalFactId, cursor?.semanticRevision,
			)
			if (page.size > remaining) return WifiFactLoad(facts, overflow = true)
			if (page.isEmpty()) break
			val next = FactCursor(page.last())
			check(cursor == null || next > cursor) { "Wi-Fi fact cursor did not advance" }
			facts += page
			cursor = next
			if (page.size < pageLimit) break
		}
		return WifiFactLoad(facts, overflow = false)
	}

	private suspend fun <I, T> loadChunked(
		ids: List<I>,
		maximum: Int,
		query: suspend (List<I>, Int) -> List<T>,
	): BoundedLoad<T> {
		val rows = mutableListOf<T>()
		for (chunk in ids.chunked(SQL_ID_BATCH)) {
			currentCoroutineContext().ensureActive()
			val remaining = maximum - rows.size
			if (remaining <= 0) return BoundedLoad(rows, overflow = true)
			val page = query(chunk, remaining + 1)
			if (page.size > remaining) return BoundedLoad(rows, overflow = true)
			rows += page
		}
		return BoundedLoad(rows, overflow = false)
	}

	private data class BoundedLoad<T>(val rows: List<T>, val overflow: Boolean)

	private data class WifiFactLoad(
		val revisions: List<WifiCapturedFactRevisionEntity>,
		val overflow: Boolean,
	)

	private data class ServiceRunCursor(
		val logicalId: String,
		val startedAtMs: Long,
		val serviceRunId: String,
	) : Comparable<ServiceRunCursor> {
		constructor(run: SourceServiceRunEntity) : this(run.logicalTrackingId, run.startedAtMs, run.serviceRunId)

		override fun compareTo(other: ServiceRunCursor): Int = compareValuesBy(
			this, other, ServiceRunCursor::logicalId, ServiceRunCursor::startedAtMs,
			ServiceRunCursor::serviceRunId,
		)
	}

	private data class FactCursor(
		val projectionId: String,
		val projectionVersion: Int,
		val logicalFactId: String,
		val semanticRevision: Long,
	) : Comparable<FactCursor> {
		constructor(fact: WifiCapturedFactRevisionEntity) : this(
			fact.writerProjectionId, fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision,
		)

		override fun compareTo(other: FactCursor): Int = compareValuesBy(
			this, other, FactCursor::projectionId, FactCursor::projectionVersion,
			FactCursor::logicalFactId, FactCursor::semanticRevision,
		)
	}

	private fun maxOfOrNull(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> maxOf(left, right)
	}

	private companion object {
		const val MAX_RESULTS = 100
		const val MAX_EXACT_ORIGIN_COLLISIONS = 1
		const val CANDIDATE_PAGE_SIZE = 32
		const val MAX_CANDIDATE_SCAN = 128
		const val MEMBER_PAGE_SIZE = 32
		const val MAX_LOGICAL_MEMBERS = 128
		const val FACT_PAGE_SIZE = 256
		const val MAX_FACT_REVISIONS = 4_096
		const val MAX_CURSORS = 4_096
		const val MAX_SESSIONS = 128
		const val MAX_MANIFESTS = 512
		const val MAX_MANIFEST_SOURCES = 4_096
		const val MAX_COMPLETENESS = 512
		const val MAX_POLICIES = 512
		const val MAX_CONSENTS = 512
		const val MAX_DELETION_GENERATIONS = 128
		const val MAX_DELETION_FENCES = 128
		const val MAX_LANES = 128
		const val MAX_PLANS = 512
		const val MAX_PROVIDERS = 512
		const val MAX_AUTHORIZATIONS = 4_096
		const val MAX_DEMANDS = 4_096
		const val MAX_ACTIONS = 512
		const val MAX_ADMISSION_ROWS = 4_096
		const val MAX_TERMINAL_FAILURES = 512
		const val SQL_ID_BATCH = 400
	}
}

private data class LiveWifiHistoryPage(
	val page: WifiHistoryPage,
	val entries: List<ComposedWifiEntry>,
)

internal fun isWifiCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == WIFI_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		source.persistenceEligible
