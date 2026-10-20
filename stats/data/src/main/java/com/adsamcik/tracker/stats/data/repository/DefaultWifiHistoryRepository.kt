package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.dao.WifiLogicalRangeCandidate
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
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
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryPage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiHistoryStructuralDayPage
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Wi-Fi facade. Every call reads one bounded Room transaction snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal class DefaultWifiHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val importedProductEvaluator: ImportedWifiProductEvaluator,
	private val localPortableReader: ReadLocalPortableCapturedWifi,
	private val deletedHistoryReader: WifiDeletedHistoryReader,
	private val bootClockDomainProvider: BootClockDomainProvider,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WifiHistoryRepository {
	/** Original imported receipt/lineage qualification without constructing a whole public entry. */
	internal suspend fun measuredImportedEvaluationInTransaction(identity: String): ImportedWifiProductEvaluation? =
		importedProductEvaluator.selectIdentityInTransaction(WifiImportedHistorySelectionKey(identity))

	internal suspend fun measuredFactInTransaction(factId: String, revision: Long): QualifiedMeasuredWifiFact? {
		val snapshot = measuredSnapshotInTransaction(factId)
			?:
                throw MeasuredRadioParentFailure(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
		return WifiHistoryComposer.qualifyMeasuredFact(factId, revision, snapshot, laneExecutionAuthority)
	}

	/** Existing capture proof inputs, scoped to one current parent rather than a complete logical entry. */
	internal suspend fun measuredSnapshotInTransaction(factId: String): WifiHistorySnapshot? {
		require(factId.isNotBlank())
		val cursor = database.wifiCapturedFactDao().cursor(SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, factId) ?: return null
		val seed = database.trackingHistoryReadDao().segments(listOf(cursor.sessionSegmentId)).singleOrNull() ?:
            return null
		return loadSnapshot(WifiMembershipExpansion(listOf(seed), emptyMap()), factId)
	}

	override suspend fun session(segmentId: Long): WifiHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction { sessionInTransaction(segmentId) }
		}
	}

	internal suspend fun sessionInTransaction(segmentId: Long): WifiHistoryQuery {
		require(segmentId > 0L)
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return WifiHistoryQuery.NotFound
		val snapshot = loadSnapshot(expandMembership(listOf(seed)))
		return WifiHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
			?.let(WifiHistoryQuery::Found) ?: WifiHistoryQuery.NotFound
	}

	override suspend fun imported(selection: WifiImportedHistorySelectionKey): WifiHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				try {
					val evaluation = importedProductEvaluator.selectIdentityInTransaction(selection)
						?: return@withTransaction WifiHistoryQuery.NotFound
					importedQueryInTransaction(evaluation, null)
				} catch (cancelled: kotlinx.coroutines.CancellationException) {
					throw cancelled
				} catch (_: ArithmeticException) {
					WifiHistoryQuery.Failed(WifiHistoryCause.VALUE_OVERFLOW)
				} catch (_: RuntimeException) {
					WifiHistoryQuery.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
				}
			}
		}

	/** Selected range keys share import qualification, origin comparison and detail ownership. */
	internal suspend fun selectedImportedInTransaction(
		identities: List<String>,
		range: HistoryRange,
	): Map<String, WifiHistoryEntry> {
		require(identities.size in 1..MAX_RESULTS && identities.distinct().size == identities.size)
		val evaluations = identities.map { identity ->
			currentCoroutineContext().ensureActive()
			importedProductEvaluator.selectIdentityInTransaction(WifiImportedHistorySelectionKey(identity))
				?: throw ImportedWifiHistoryCompositionFailure()
		}
		val collisions = evaluations.filterIsInstance<ImportedWifiProductEvaluation.Readable>()
			.mapNotNull { it.collidingLocalLogicalTrackingId }.distinct()
		if (collisions.size > MAX_EXACT_ORIGIN_COLLISIONS) {
			throw ImportedWifiHistoryCompositionFailure()
		}
		val localPortable = readCollisionPortableEntries(collisions)
		val visible = database.nativeLogicalIdsVisibleInRange(collisions.toSet(), range)
		val composed = WifiHistoryOriginComposer.composeSelectedImported(evaluations, localPortable, visible)
		val byIdentity = evaluations.associateBy { it.candidate.selection.key.value }
		return composed.mapValues { (identity, entry) ->
			database.withImportedWifiDetails(requireNotNull(byIdentity[identity]), entry)
		}
	}

	private suspend fun readCollisionPortableEntries(
		logicalIds: List<String>,
	): Map<String, PortableCapturedWifiEntryV1> = logicalIds.associateWith { logicalId ->
		currentCoroutineContext().ensureActive()
		when (val read = localPortableReader.readInTransaction(ExportPortableCapturedWifiRequest(logicalId))) {
			is ReadLocalPortableCapturedWifiResult.Ready -> read.entry
			is ReadLocalPortableCapturedWifiResult.Outcome -> throw ImportedWifiHistoryCompositionFailure()
		}
	}

	override suspend fun lookup(selection: WifiHistorySelection): WifiHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				try {
					lookupInTransaction(selection)
				} catch (cancelled: kotlinx.coroutines.CancellationException) {
					throw cancelled
				} catch (_: ArithmeticException) {
					WifiHistoryQuery.Failed(WifiHistoryCause.VALUE_OVERFLOW)
				} catch (_: RuntimeException) {
					WifiHistoryQuery.Failed(
						if (selection is WifiHistorySelection.Imported) {
							WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
						} else {
							WifiHistoryCause.FACT_INTEGRITY_FAILED
						},
					)
				}
			}
		}

	private suspend fun lookupInTransaction(selection: WifiHistorySelection): WifiHistoryQuery {
		when (val deleted = deletedHistoryReader.readDeletedInTransaction(selection)) {
			is WifiDeletedHistoryResult.Deleted ->
				return WifiHistoryQuery.Found(deleted.entry)
			is WifiDeletedHistoryResult.Unverifiable ->
				return WifiHistoryQuery.Failed(
					if (selection is WifiHistorySelection.Imported) {
						WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
					} else {
						WifiHistoryCause.FACT_INTEGRITY_FAILED
					},
				)
			WifiDeletedHistoryResult.NotDeleted -> Unit
		}
		return when (selection) {
			is WifiHistorySelection.Imported -> {
				val evaluation = importedProductEvaluator.selectIdentityInTransaction(
					selection.selected.key,
				) ?: return WifiHistoryQuery.NotFound
				importedQueryInTransaction(evaluation, selection.selected)
			}
			is WifiHistorySelection.Local -> localQueryInTransaction(selection.key)
		}
	}


	private suspend fun importedQueryInTransaction(
		evaluation: ImportedWifiProductEvaluation,
		expected: WifiImportedHistorySelection?,
	): WifiHistoryQuery {
		if (expected != null && evaluation.candidate.selection != expected) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.STALE_SELECTION)
		}
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
		return WifiHistoryQuery.Found(database.withImportedWifiDetails(evaluation,
			evaluation.toPublicWifiEntry(originConflict)))
	}

	private suspend fun localQueryInTransaction(
		selection: WifiLocalHistorySelectionKey,
	): WifiHistoryQuery {
		val dao = database.importedWifiDao()
		val expectedCount = dao.localEntryOwnerCount()
		if (expectedCount < 0L || expectedCount > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val matches = matchingLocalEntryOwners(dao, selection, expectedCount)
			?: return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		val logicalId = matches.singleOrNull() ?: return if (matches.isEmpty()) {
			WifiHistoryQuery.NotFound
		} else {
			WifiHistoryQuery.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		return selectedLocalSessionInTransaction(logicalId, selection)
	}

	private suspend fun matchingLocalEntryOwners(
		dao: ImportedWifiDao,
		selection: WifiLocalHistorySelectionKey,
		expectedCount: Long,
	): List<String>? {
		val matches = mutableListOf<String>()
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.localEntryOwnerPage(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			if (!page.isValidLocalOwnerPage(after)) {
				return null
			}
			page.forEach { logicalId ->
				val key = PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					logicalId,
				).value
				if (key == selection.value) {
					matches += logicalId
				}
			}
			loaded = Math.addExact(loaded, page.size.toLong())
			if (loaded > expectedCount) {
				return null
			}
			if (page.isEmpty() || page.size < ImportedWifiDao.OWNER_PAGE_SIZE) {
				break
			}
			after = page.last()
		}
		return matches.takeIf { loaded == expectedCount }
	}

	private fun List<String>.isValidLocalOwnerPage(after: String?): Boolean =
		size <= ImportedWifiDao.OWNER_PAGE_SIZE &&
			zipWithNext().none { (left, right) -> left >= right } &&
			after?.let { previous -> firstOrNull()?.let { first -> first <= previous } } != true

	private suspend fun selectedLocalSessionInTransaction(
		logicalId: String,
		selection: WifiLocalHistorySelectionKey,
	): WifiHistoryQuery {
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(logicalId),
			MAX_LOGICAL_MEMBERS + 1,
			null,
			null,
			null,
		)
		if (runs.size > MAX_LOGICAL_MEMBERS) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		if (segmentIds.size != runs.size || segmentIds.isEmpty()) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
		val seed = segments.takeIf { it.size == segmentIds.size }?.minByOrNull(SessionSegment::id)
			?: return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		return selectedSessionQueryInTransaction(seed.id, selection)
	}

	private suspend fun selectedSessionQueryInTransaction(
		segmentId: Long,
		selection: WifiLocalHistorySelectionKey,
	): WifiHistoryQuery {
		val query = sessionInTransaction(segmentId)
		val found = query as? WifiHistoryQuery.Found ?: return query
		found.entry.causes.firstOrNull { it.isIntegrityFailure }?.let {
			return WifiHistoryQuery.Failed(it)
		}
		return if (found.entry.localSelection == selection) {
			found
		} else {
			WifiHistoryQuery.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
	}

	override suspend fun recent(limit: Int): WifiHistoryPage {
		require(limit in 1..MAX_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction {
				when (val page = recentInTransaction(limit)) {
					is WifiSourceRecentPage.Available ->
						WifiHistoryPage.Available(page.entries.map(WifiSourceRecentEntry::entry))
					is WifiSourceRecentPage.Failed -> WifiHistoryPage.Failed(page.cause)
				}
			}
		}
	}

	/**
	 * Mixed local/imported Wi-Fi page for the shared history facade. Local rows retain complete
	 * authenticated physical membership; imported rows never acquire a native id.
	 */
	internal suspend fun recentInTransaction(limit: Int): WifiSourceRecentPage {
		require(limit in 1..MAX_RESULTS)
		val live = loadRecentLocal(limit)
		if (live.page is WifiHistoryPage.Failed) {
			return WifiSourceRecentPage.Failed(live.page.cause)
		}
		return recentImportedInTransaction(live, limit)
	}

	private suspend fun recentImportedInTransaction(
		live: LiveWifiHistoryPage,
		limit: Int,
	): WifiSourceRecentPage {
		val imported = try {
			importedProductEvaluator.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return composeRecentOrigins(live, imported, limit)
	}

	private suspend fun composeRecentOrigins(
		live: LiveWifiHistoryPage,
		imported: List<ImportedWifiProductEvaluation>,
		limit: Int,
	): WifiSourceRecentPage {
		val collisions = imported.filterIsInstance<ImportedWifiProductEvaluation.Readable>()
			.mapNotNull(ImportedWifiProductEvaluation.Readable::collidingLocalLogicalTrackingId)
			.distinct()
		if (collisions.size > MAX_EXACT_ORIGIN_COLLISIONS) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortable = try {
			readCollisionPortableEntries(collisions)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		return composeRecentDetails(live, imported, localPortable, limit)
	}

	private suspend fun composeRecentDetails(
		live: LiveWifiHistoryPage,
		imported: List<ImportedWifiProductEvaluation>,
		localPortable: Map<String, PortableCapturedWifiEntryV1>,
		limit: Int,
	): WifiSourceRecentPage {
		return try {
			val composed = WifiHistoryOriginComposer.composeSourceRecent(
				live = live.entries,
				imported = imported,
				localPortableByLogicalId = localPortable,
				limit = limit,
			)
			if (composed is WifiSourceRecentPage.Available) {
				val bySelection = imported.associateBy { it.candidate.selection }
				WifiSourceRecentPage.Available(composed.entries.map { row ->
					if (row is WifiSourceRecentEntry.Imported) {
						row.copy(entry =
						database.withImportedWifiDetails(requireNotNull(bySelection[row.selection]),
							row.entry))
					} else {
						row
					}
				})
			} else {
				composed
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ImportedWifiHistoryCompositionFailure) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		} catch (_: ArithmeticException) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	/** Exact complete local Wi-Fi groups for one bounded caller-owned physical candidate set. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	internal suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
		sourceOnly: Boolean = false,
	): WifiComposedPage {
		require(segmentIds.size <= MAX_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) {
			return WifiComposedPage.Available(emptyList())
		}
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		if (seeds.size != segmentIds.size) {
			return WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val selectedSeeds = if (sourceOnly) {
			database.sourceOnlyHistoryCandidates(seeds, WIFI_SOURCE)
		} else {
			seeds
		}
		val expansion = expandMembership(selectedSeeds)
		expansion.failures.values.firstOrNull()?.let { return WifiComposedPage.Failed(it) }
		val snapshot = loadSnapshot(expansion)
		if (snapshot.overflow) {
			return WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
		return WifiComposedPage.Available(
			WifiHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in logicalIds }
				.sortedWith(wifiCompositionOrder),
		)
	}

	/**
	 * Intent-first recent Wi-Fi-only groups. Fact presence is never the only-source predicate.
	 * The complete manifest union inside [WifiHistoryComposer] decides `capturesOnlyWifi`.
	 */
	internal suspend fun recentWifiOnlyInTransaction(limit: Int): WifiComposedPage {
		require(limit in 1..MAX_RESULTS)
		val accepted = mutableListOf<ComposedWifiEntry>()
		var scanned = 0
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		while (accepted.size < limit) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_CANDIDATE_SCAN - scanned
			if (remaining == 0) {
				return WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining)
			val page = database.trackingHistoryReadDao().recentEntryCandidatePage(
				limit = pageLimit,
				stepsSourceKind = WIFI_SOURCE,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				includeExactStepsOnlyIntent = true,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeSegmentId = beforeSegmentId,
			)
			if (page.isEmpty()) {
				break
			}
			if (page.size > pageLimit) {
				return WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			}
			scanned += page.size
			val composed = when (val candidates = wifiOnlyCandidateEntries(page.map { it.sortSegmentId })) {
				is WifiComposedPage.Available -> candidates.entries
				is WifiComposedPage.Failed -> return candidates
			}
			val candidateIds = page.mapNotNull { it.logicalTrackingId }.toSet()
			accepted += selectedWifiOnlyEntries(composed, candidateIds)
			val last = page.last()
			beforeStartTimeMs = last.sortStartTimeMs
			beforeSegmentId = last.sortSegmentId
			if (page.size < pageLimit) {
				break
			}
		}
		return WifiComposedPage.Available(accepted.take(limit))
	}

	private fun selectedWifiOnlyEntries(
		entries: List<ComposedWifiEntry>, logicalIds: Set<String>,
	): List<ComposedWifiEntry> = entries
		.filter { it.logicalTrackingId in logicalIds && it.entry.capturesOnlyWifi }
		.sortedWith(wifiCompositionOrder)

	private suspend fun wifiOnlyCandidateEntries(seedIds: List<Long>): WifiComposedPage {
		if (seedIds.distinct().size != seedIds.size) {
			return WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val seeds = database.trackingHistoryReadDao().segments(seedIds)
		return if (seeds.size != seedIds.size) {
			WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		} else {
			composeLocalSeeds(database.sourceOnlyHistoryCandidates(seeds, WIFI_SOURCE))
		}
	}

	override suspend fun range(request: WifiHistoryRangeRequest): WifiHistoryRangePage =
		withContext(ioDispatcher) {
			database.withTransaction { rangeInTransaction(request) }
		}

	override suspend fun structuralDays(
		request: WifiHistoryRangeRequest,
	): WifiHistoryStructuralDayPage = withContext(ioDispatcher) {
		database.withTransaction {
			val structuralEntries = mutableListOf<WifiHistoryStructuralEntryProjection>()
			when (val page = rangeInTransaction(request, structuralEntries)) {
				is WifiHistoryRangePage.Failed -> WifiHistoryStructuralDayPage.Failed(page.cause)
				is WifiHistoryRangePage.Available -> try {
					WifiHistoryStructuralDayPage.Available(
						days = WifiHistoryStructuralDayComposer.compose(
							structuralEntries,
							request.fromInclusive.raw,
							request.toExclusive.raw,
						),
						continuation = page.continuation,
					)
				} catch (_: WifiHistoryRangeLimitExceeded) {
					WifiHistoryStructuralDayPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
				} catch (_: ArithmeticException) {
					WifiHistoryStructuralDayPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
				} catch (_: RuntimeException) {
					WifiHistoryStructuralDayPage.Failed(WifiHistoryCause.STORED_ZONE_INVALID)
				}
			}
		}
	}

	internal suspend fun rangeInTransaction(
		request: WifiHistoryRangeRequest,
		structuralOutput: MutableList<WifiHistoryStructuralEntryProjection>? = null,
	): WifiHistoryRangePage {
		structuralOutput?.clear()
		val cursor = WifiHistoryRangeCursorCodec.decode(
			request.continuation,
			request.fromInclusive.raw,
			request.toExclusive.raw,
		) ?: return WifiHistoryRangePage.Failed(WifiHistoryCause.RANGE_CONTINUATION_INVALID)
		val localRows = database.wifiCapturedFactDao().logicalHistoryRangeCandidatePage(
			sourceKind = WIFI_SOURCE,
			capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			fromInclusiveMs = request.fromInclusive.raw,
			toExclusiveMs = request.toExclusive.raw,
			limit = RANGE_SOURCE_CANDIDATE_LIMIT + 1,
			beforeStartTimeMs = cursor.localStartTimeMs,
			beforeSegmentId = cursor.localSegmentId,
		)
		if (localRows.size > RANGE_SOURCE_CANDIDATE_LIMIT + 1) {
			return WifiHistoryRangePage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		return rangeFromLocalRows(request, cursor, localRows, structuralOutput)
	}

	private suspend fun rangeFromLocalRows(
		request: WifiHistoryRangeRequest,
		cursor: WifiHistoryRangeCursor,
		localRows: List<WifiLogicalRangeCandidate>,
		structuralOutput: MutableList<WifiHistoryStructuralEntryProjection>?,
	): WifiHistoryRangePage {
		val selectedLocalRows = localRows.take(RANGE_SOURCE_CANDIDATE_LIMIT)
		val localComposed = if (selectedLocalRows.isEmpty()) {
			emptyMap()
		} else {
			when (val composed = composeLocalSeeds(selectedLocalRows.map { it.segment })) {
				is WifiComposedPage.Available -> composed.entries.associateBy(ComposedWifiEntry::logicalTrackingId)
				is WifiComposedPage.Failed -> return WifiHistoryRangePage.Failed(composed.cause)
			}
		}
		val localItems = localRangeItems(selectedLocalRows, localComposed)
			?: return WifiHistoryRangePage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)

		return rangeWithImportedOrigins(request, cursor, localRows.size > RANGE_SOURCE_CANDIDATE_LIMIT,
			localItems, structuralOutput)
	}

	private fun localRangeItems(
		rows: List<WifiLogicalRangeCandidate>,
		composedByLogicalId: Map<String, ComposedWifiEntry>,
	): List<WifiRangeWorkItem.Local>? {
		return rows.map { row ->
			val logicalId = row.segment.logicalTrackingId
				?: return null
			val composed = composedByLogicalId[logicalId]
				?: return null
			if (composed.entry.startTime.raw != row.logicalStartTimeMs ||
				composed.entry.endTime.raw != row.logicalEndTimeMs
			) {
				return null
			}
			WifiRangeWorkItem.Local(composed.recencyStartTimeMs, logicalId, composed)
		}
	}

	private suspend fun rangeWithImportedOrigins(
		request: WifiHistoryRangeRequest,
		cursor: WifiHistoryRangeCursor,
		moreLocal: Boolean,
		localItems: List<WifiRangeWorkItem.Local>,
		structuralOutput: MutableList<WifiHistoryStructuralEntryProjection>?,
	): WifiHistoryRangePage {
		val importedPage = try {
			importedProductEvaluator.selectRangeInTransaction(
				ImportedWifiProductRangeRequest(
					fromInclusiveMs = request.fromInclusive.raw,
					toExclusiveMs = request.toExclusive.raw,
					limit = RANGE_SOURCE_CANDIDATE_LIMIT,
					beforeStartTimeMs = cursor.importedStartTimeMs,
					beforeIdentity = cursor.importedIdentity?.let(::PortableWifiOpaqueIdentity),
				),
			)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return WifiHistoryRangePage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return WifiHistoryRangePage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return composeRangeOrigins(request, cursor, moreLocal, localItems, structuralOutput, importedPage)
	}

	private suspend fun composeRangeOrigins(
		request: WifiHistoryRangeRequest,
		cursor: WifiHistoryRangeCursor,
		moreLocal: Boolean,
		localItems: List<WifiRangeWorkItem.Local>,
		structuralOutput: MutableList<WifiHistoryStructuralEntryProjection>?,
		importedPage: ImportedWifiProductRangePage,
	): WifiHistoryRangePage {
		val collisions = importedPage.evaluations
			.filterIsInstance<ImportedWifiProductEvaluation.Readable>()
			.mapNotNull(ImportedWifiProductEvaluation.Readable::collidingLocalLogicalTrackingId)
			.distinct()
		if (collisions.size > MAX_EXACT_ORIGIN_COLLISIONS) {
			return WifiHistoryRangePage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortable = collisions.singleOrNull()?.let { logicalId ->
			when (val read = localPortableReader.readInTransaction(
				ExportPortableCapturedWifiRequest(logicalId),
			)) {
				is ReadLocalPortableCapturedWifiResult.Ready -> logicalId to read.entry
				is ReadLocalPortableCapturedWifiResult.Outcome ->
					return WifiHistoryRangePage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			}
		}
		val importedItems = importedRangeItems(importedPage.evaluations, localPortable)
			?: return WifiHistoryRangePage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		val work = (localItems + importedItems).sortedWith(WIFI_RANGE_WORK_ORDER)
		return publishRangePage(request, cursor, moreLocal, importedPage.hasMore, work, structuralOutput)
	}

	private suspend fun importedRangeItems(
		evaluations: List<ImportedWifiProductEvaluation>,
		localPortable: Pair<String, PortableCapturedWifiEntryV1>?,
	): List<WifiRangeWorkItem.Imported>? {
		return evaluations.map { evaluation ->
			val readable = evaluation as? ImportedWifiProductEvaluation.Readable
			val collision = readable?.collidingLocalLogicalTrackingId
			val collidingPortable = localPortable
			val publicEntry = if (collision == null) {
				evaluation.toPublicWifiEntry()
			} else {
				val colliding = requireNotNull(readable)
				when {
					collidingPortable == null || collidingPortable.first != collision ->
						return null
					colliding.isReExportable && colliding.entry == collidingPortable.second -> null
					else -> evaluation.toPublicWifiEntry(originConflict = true)
				}
			}
			WifiRangeWorkItem.Imported(
				startTimeMs = evaluation.candidate.newestMemberStartTimeMs,
				identity = evaluation.candidate.newestMemberIdentity.value,
				entry = publicEntry?.let { database.withImportedWifiDetails(evaluation, it) },
				structuralRuns = publicEntry?.let {
					evaluation.authenticatedStructuralRuns(it)
				}.orEmpty(),
			)
		}
	}


	private suspend fun publishRangePage(
		request: WifiHistoryRangeRequest,
		cursor: WifiHistoryRangeCursor,
		moreLocal: Boolean,
		moreImported: Boolean,
		work: List<WifiRangeWorkItem>,
		structuralOutput: MutableList<WifiHistoryStructuralEntryProjection>?,
	): WifiHistoryRangePage {
		val output = mutableListOf<WifiHistoryEntry>()
		var localStart = cursor.localStartTimeMs
		var localSegmentId = cursor.localSegmentId
		var importedStart = cursor.importedStartTimeMs
		var importedIdentity = cursor.importedIdentity
		var consumed = 0
		for (item in work) {
			currentCoroutineContext().ensureActive()
			when (item) {
				is WifiRangeWorkItem.Local -> {
					localStart = item.startTimeMs
					localSegmentId = item.composed.recencySegmentId
					output += item.composed.entry
					structuralOutput?.add(
						WifiHistoryStructuralEntryProjection(
							item.composed.entry,
							item.composed.structuralRuns,
						),
					)
				}
				is WifiRangeWorkItem.Imported -> {
					importedStart = item.startTimeMs
					importedIdentity = item.identity
					item.entry?.let { entry ->
						output += entry
						structuralOutput?.add(
							WifiHistoryStructuralEntryProjection(entry, item.structuralRuns),
						)
					}
				}
			}
			consumed += 1
			if (output.size == request.limit) {
				break
			}
		}
		val hasMore = consumed < work.size ||
			moreLocal || moreImported
		val continuation = if (hasMore) {
			WifiHistoryRangeCursorCodec.encode(
				WifiHistoryRangeCursor(
					fromInclusiveMs = request.fromInclusive.raw,
					toExclusiveMs = request.toExclusive.raw,
					localStartTimeMs = localStart,
					localSegmentId = localSegmentId,
					importedStartTimeMs = importedStart,
					importedIdentity = importedIdentity,
				),
			)
		} else {
			null
		}
		return WifiHistoryRangePage.Available(output, continuation)
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
			if (candidates.isEmpty()) {
				break
			}
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val composed = when (val page = composeLocalSeeds(seeds)) {
				is WifiComposedPage.Available -> page.entries
				is WifiComposedPage.Failed -> return LiveWifiHistoryPage(
					WifiHistoryPage.Failed(page.cause), emptyList())
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			composed
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStart = last.logicalRecencyStartMs
			beforeId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) {
				break
			}
		}
		return recentLocalPage(accepted, limit, scanned)
	}

	private fun recentLocalPage(
		accepted: Map<String, ComposedWifiEntry>, limit: Int, scanned: Int,
	): LiveWifiHistoryPage {
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

	private suspend fun composeLocalSeeds(seeds: List<SessionSegment>): WifiComposedPage {
		val expansion = expandMembership(seeds)
		expansion.failures.values.firstOrNull()?.let { return WifiComposedPage.Failed(it) }
		val snapshot = loadSnapshot(expansion)
		return if (snapshot.overflow) {
			WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		} else {
			WifiComposedPage.Available(WifiHistoryComposer.composeRecent(snapshot, laneExecutionAuthority))
		}
	}

	private suspend fun expandMembership(seeds: List<SessionSegment>): WifiMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank).distinct()
		if (logicalIds.isEmpty()) {
			return WifiMembershipExpansion(seeds, emptyMap())
		}
		val runs = loadMembershipRuns(logicalIds) ?: return WifiMembershipExpansion(seeds,
			logicalIds.associateWith { WifiHistoryCause.READ_BUDGET_EXCEEDED }, overflow = true)
		currentCoroutineContext().ensureActive()
		val runIds = runs.map(SourceServiceRunEntity::serviceRunId)
		val rawSegments = database.wifiCapturedFactDao().rawHistorySegments(
			logicalTrackingIds = logicalIds,
			serviceRunIds = runIds,
			limit = MAX_LOGICAL_MEMBERS + 1,
		)
		if (rawSegments.size > MAX_LOGICAL_MEMBERS) {
			return WifiMembershipExpansion(
				rawSegments.take(MAX_LOGICAL_MEMBERS),
				logicalIds.associateWith { WifiHistoryCause.READ_BUDGET_EXCEEDED },
				overflow = true,
			)
		}
		val segments = rawSegments.sortedWith(compareBy(
			SessionSegment::logicalTrackingId,
			SessionSegment::startTimeMs,
			SessionSegment::id,
		))
		val failures = membershipFailures(seeds, logicalIds, runs, segments)
		return WifiMembershipExpansion(segments, failures)
	}

	private suspend fun loadMembershipRuns(logicalIds: List<String>): List<SourceServiceRunEntity>? {
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
				return null
			}
			if (page.isEmpty()) {
				break
			}
			val next = ServiceRunCursor(page.last())
			check(cursor == null || next > cursor) { "Wi-Fi member cursor did not advance" }
			runs += page
			cursor = next
			if (page.size < pageLimit) {
				break
			}
		}
		return runs
	}

	private fun membershipFailures(
		seeds: List<SessionSegment>,
		logicalIds: List<String>,
		runs: List<SourceServiceRunEntity>,
		segments: List<SessionSegment>,
	): Map<String, WifiHistoryCause> {
		val byId = segments.associateBy(SessionSegment::id)
		val runById = runs.associateBy(SourceServiceRunEntity::serviceRunId)
		val failures = linkedMapOf<String, WifiHistoryCause>()
		if (segments.size != runs.size || byId.size != segments.size ||
			runById.size != runs.size
		) {
			logicalIds.forEach { failures[it] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID }
		}
		logicalIds.forEach { logical ->
			if (runs.none { it.logicalTrackingId == logical }) {
				failures[logical] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		appendRunMembershipFailures(runs, byId, failures)
		appendSegmentMembershipFailures(segments, logicalIds, runById, failures)
		appendSeedMembershipFailures(seeds, runById, failures)
		return failures
	}

	private fun appendRunMembershipFailures(
		runs: List<SourceServiceRunEntity>,
		byId: Map<Long, SessionSegment>,
		failures: MutableMap<String, WifiHistoryCause>,
	) {
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(byId::get)
			if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) {
				failures[run.logicalTrackingId] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
	}

	private fun appendSegmentMembershipFailures(
		segments: List<SessionSegment>,
		logicalIds: List<String>,
		runById: Map<String, SourceServiceRunEntity>,
		failures: MutableMap<String, WifiHistoryCause>,
	) {
		segments.forEach { segment ->
			val claimedLogical = segment.logicalTrackingId
			val claimedRun = segment.serviceRunId?.let(runById::get)
			if (claimedLogical !in logicalIds || claimedRun == null ||
				claimedRun.logicalTrackingId != claimedLogical ||
				claimedRun.sessionSegmentId != segment.id
			) {
				claimedLogical?.takeIf { it in logicalIds }?.let {
					failures[it] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
				}
				claimedRun?.logicalTrackingId?.takeIf { it in logicalIds }?.let {
					failures[it] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
				}
			}
		}
	}

	private fun appendSeedMembershipFailures(
		seeds: List<SessionSegment>,
		runById: Map<String, SourceServiceRunEntity>,
		failures: MutableMap<String, WifiHistoryCause>,
	) {
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			val run = seed.serviceRunId?.let(runById::get)
			if (run == null || run.logicalTrackingId != logical || run.sessionSegmentId != seed.id) {
				failures[logical] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
	}

	@Suppress("LongMethod")
	private suspend fun loadSnapshot(expansion: WifiMembershipExpansion, selectedFactId: String? =
        null): WifiHistorySnapshot {
		if (expansion.overflow) {
			return WifiHistorySnapshot.empty(expansion).copy(overflow = true)
		}
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) {
			return WifiHistorySnapshot.empty(expansion)
		}
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		val readDao = database.trackingHistoryReadDao()
		val factDao = database.wifiCapturedFactDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_MANIFESTS + 1)
		val sources = readDao.manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		val policies = factDao.historyPolicies(WIFI_SOURCE, runIds, MAX_POLICIES + 1)
		val completeness = readDao.completeness(runIds, MAX_COMPLETENESS + 1)
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factLoad = if (selectedFactId == null) {
			loadFactPages(runIds, logicalIds)
		} else {
			val rows = factDao.selectedHistoryLineages(SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, selectedFactId, MAX_FACT_REVISIONS + 1)
			WifiFactLoad(rows.take(MAX_FACT_REVISIONS), rows.size > MAX_FACT_REVISIONS)
		}
		val revisions = factLoad.revisions
		currentCoroutineContext().ensureActive()
		val scopedCursors = loadScopedCursors(runIds, logicalIds, selectedFactId)
		val factIds = revisions.map(WifiCapturedFactRevisionEntity::logicalFactId).distinct()
		val childLoad = loadCurrentChildren(factIds)
		val currentChildren = childLoad.rows
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
		val planHeaders = if (planRevisions.isEmpty()) {
			emptyList()
		} else
			{
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_PLANS + 1)
		}
		val desiredPlans = if (planRevisions.isEmpty()) {
			emptyList()
		} else
			{
			factDao.historyDesiredPlans(WIFI_SOURCE, planRevisions, MAX_PLANS + 1)
		}
		val admissions = loadHistoryAdmissions(runIds, revisions, selectedFactId)
		val registrationGenerations = (revisions.map(WifiCapturedFactRevisionEntity::registrationGeneration) +
			admissions.map { it.registrationGeneration }).distinct()
		val providerLoad = loadChunked(registrationGenerations, MAX_PROVIDERS) { ids, limit ->
			factDao.historyProviderRegistrations(WIFI_SOURCE, ids, limit)
		}
		val providers = providerLoad.rows
		val currentProviderRows = database.sourceBrokerDao().currentPhysicalRegistrationsBounded(
			WIFI_SOURCE,
			MAX_PROVIDERS + 1,
		)
		val retiringProviderRows = database.sourceBrokerDao().pendingProviderRemovalsBounded(
			WIFI_SOURCE,
			MAX_PROVIDERS + 1,
		)
		val currentProviders = (currentProviderRows + retiringProviderRows)
			.distinctBy { it.registrationGeneration }
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
		val digestToPair = historyScopePairs(segments)
		val fences = if (digestToPair.isEmpty()) {
			emptyList()
		} else {
			factDao.historyDeletionFences(
			WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digestToPair.keys.toList(),
			MAX_DELETION_FENCES + 1,
		)
		}
		val lanes = factDao.historyProductLanes(
			WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, runIds, MAX_LANES + 1,
		)
		val failures = loadHistoryFailures(runIds, revisions, completeness, admissions, lanes, selectedFactId)
		val evidence = database.sourceEvidenceStateDao().get()
		currentCoroutineContext().ensureActive()
		val dependencyCounts = listOf(
			runs.size to MAX_LOGICAL_MEMBERS,
			sessions.size to MAX_SESSIONS,
			manifests.size to MAX_MANIFESTS,
			sources.size to MAX_MANIFEST_SOURCES,
			completeness.size to MAX_COMPLETENESS,
			policies.size to MAX_POLICIES,
			consents.size to MAX_CONSENTS,
			cursors.size to MAX_CURSORS,
			generations.size to MAX_DELETION_GENERATIONS,
			planHeaders.size to MAX_PLANS,
			desiredPlans.size to MAX_PLANS,
			providers.size to MAX_PROVIDERS,
			authorizations.size to MAX_AUTHORIZATIONS,
			currentProviderRows.size to MAX_PROVIDERS,
			retiringProviderRows.size to MAX_PROVIDERS,
			currentProviders.size to MAX_PROVIDERS,
			demands.size to MAX_DEMANDS,
			actions.size to MAX_ACTIONS,
			admissions.size to MAX_ADMISSION_ROWS,
			fences.size to MAX_DELETION_FENCES,
			lanes.size to MAX_LANES,
			failures.size to MAX_TERMINAL_FAILURES,
		)
		val boundedReadOverflow = listOf(
			factLoad.overflow, referencedCursorLoad.overflow, providerLoad.overflow,
			authorizationLoad.overflow, demandLoad.overflow, consentLoad.overflow, childLoad.overflow,
		).any { it }
		val overflow = boundedReadOverflow || dependencyCounts.any { (count, maximum) -> count > maximum }
		return WifiHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			savedSessionCutoffs = sessions.associate { it.logicalTrackingId to database.savedSessionCutoff(it) },
			runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.take(MAX_MANIFESTS).groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.take(MAX_MANIFEST_SOURCES).groupBy {
				WifiManifestKey(it.logicalTrackingId, it.manifestRevision)
			},
			policies = policies.take(MAX_POLICIES).associateBy(SourcePolicyEntity::policyRevision),
			consents = consents.take(MAX_CONSENTS).associateBy(SourceConsentEpochEntity::epoch),
			completenessByRun = completeness.take(MAX_COMPLETENESS).groupBy { it.serviceRunId },
			revisions = revisions.take(MAX_FACT_REVISIONS),
			childrenByRevision = currentChildren.groupBy { it.logicalFactId to it.semanticRevision },
			cursors = cursors.take(MAX_CURSORS),
			deletionGenerations = generations.take(MAX_DELETION_GENERATIONS)
				.associateBy { it.logicalTrackingId to it.serviceRunId },
			deletedScopes = fences.take(MAX_DELETION_FENCES)
				.mapNotNull { digestToPair[it.scopeIdentityDigest] }.toSet(),
			planHeaders = planHeaders.take(MAX_PLANS).associateBy { it.revision },
			desiredPlans = desiredPlans.take(MAX_PLANS).associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providers.take(MAX_PROVIDERS).associateBy { it.registrationGeneration },
			currentProviderRegistrations = currentProviders.take(MAX_PROVIDERS),
			currentClockDomainId = bootClockDomainProvider.current(),
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

	private fun historyScopePairs(segments: List<SessionSegment>): Map<String, Pair<String, String>> {
		val scopePairs = segments.mapNotNull { segment ->
			val logical = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val run = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logical == null || run == null) {
				null
			} else {
				logical to run
			}
		}
		return scopePairs.associateBy { (logical, run) ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logical, run,
			)
		}
	}

	private suspend fun loadScopedCursors(
		runIds: List<String>,
		logicalIds: List<String>,
		selectedFactId: String?,
	): List<WifiCapturedFactCursorEntity> {
		return if (selectedFactId != null) {
			emptyList()
		} else {
			database.wifiCapturedFactDao().historyCursorsForScopes(
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			runIds,
			logicalIds,
			MAX_CURSORS + 1,
		)
		}
	}

	private suspend fun loadCurrentChildren(
		factIds: List<String>,
	): BoundedLoad<WifiCapturedChildEntity> {
		var childOverflow = false
		val currentChildren = factIds.chunked(WIFI_CHILD_FACT_CHUNK).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			val rows = database.radioObservationChildDao().wifiCurrentSessionChildrenByFactIds(
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
				ids, ids.size * MAX_WIFI_CHILDREN + 1,
			)
			if (rows.size > ids.size * MAX_WIFI_CHILDREN) {
				childOverflow = true
			}
			rows.take(ids.size * MAX_WIFI_CHILDREN)
		}
		return BoundedLoad(currentChildren, childOverflow)
	}

	private suspend fun loadHistoryAdmissions(
		runIds: List<String>,
		revisions: List<WifiCapturedFactRevisionEntity>,
		selectedFactId: String?,
	): List<SourceEventWalEntity> {
		return if (selectedFactId == null) {
			database.wifiCapturedFactDao().historyAdmissionWal(
			WIFI_SOURCE, runIds, SourceBrokerPurpose.MASK_SESSION_CAPTURE, MAX_ADMISSION_ROWS + 1,
		)
		} else {
			revisions.map(WifiCapturedFactRevisionEntity::sourceEventId).distinct().chunked(SQL_ID_BATCH).flatMap { eventIds ->
			currentCoroutineContext().ensureActive()
			database.sourceEventWalDao().boundedPayloadsByEventIds(WIFI_SOURCE, eventIds,
				MAX_WIFI_WAL_PAYLOAD_BYTES, eventIds.size + 1)
		}
		}
	}

	private suspend fun loadHistoryFailures(
		runIds: List<String>,
		revisions: List<WifiCapturedFactRevisionEntity>,
		completeness: List<SourceSessionCompletenessEntity>,
		admissions: List<SourceEventWalEntity>,
		lanes: List<SourceProductProjectionLaneEntity>,
		selectedFactId: String?,
	): List<SourceProjectionFailureEntity> {
		val through = maxOfOrNull(
			maxOfOrNull(
				revisions.maxOfOrNull(WifiCapturedFactRevisionEntity::sourceAdmissionOrdinal),
				completeness.filter { it.sourceKind == WIFI_SOURCE }
					.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull(),
			),
			admissions.maxOfOrNull { it.admissionOrdinal },
		)
		val after = lanes.minOfOrNull { it.activationOrdinal }?.minus(1L)
		return if (selectedFactId != null) {
			revisions.map { it.sourceAdmissionOrdinal }.distinct().mapNotNull { ordinal ->
			database.sourceProjectionStateDao().failure(SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION, ordinal)?.takeIf { it.terminal }
		}
		} else if (after == null || through == null || after >= through) {
			emptyList()
		} else
			{
			database.trackingHistoryReadDao().terminalFailuresForServiceRuns(
				WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, runIds,
				after, through, MAX_TERMINAL_FAILURES + 1,
			)
		}
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
			if (page.size > remaining) {
				return WifiFactLoad(facts, overflow = true)
			}
			if (page.isEmpty()) {
				break
			}
			val next = FactCursor(page.last())
			check(cursor == null || next > cursor) { "Wi-Fi fact cursor did not advance" }
			facts += page
			cursor = next
			if (page.size < pageLimit) {
				break
			}
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
			if (remaining <= 0) {
				return BoundedLoad(rows, overflow = true)
			}
			val page = query(chunk, remaining + 1)
			if (page.size > remaining) {
				return BoundedLoad(rows, overflow = true)
			}
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
		const val RANGE_SOURCE_CANDIDATE_LIMIT = 100
		const val CANDIDATE_PAGE_SIZE = 32
		const val MAX_CANDIDATE_SCAN = 128
		const val MEMBER_PAGE_SIZE = 32
		const val MAX_LOGICAL_MEMBERS = 128
		const val FACT_PAGE_SIZE = 256
		const val MAX_FACT_REVISIONS = 4_096
		const val WIFI_CHILD_FACT_CHUNK = 64
		const val MAX_WIFI_CHILDREN = 64
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

private sealed interface WifiRangeWorkItem {
	val startTimeMs: Long
	val stableIdentity: String
	val originOrder: Int

	data class Local(
		override val startTimeMs: Long,
		val logicalTrackingId: String,
		val composed: ComposedWifiEntry,
	) : WifiRangeWorkItem {
		override val stableIdentity: String = logicalTrackingId
		override val originOrder: Int = 0
	}

	data class Imported(
		override val startTimeMs: Long,
		val identity: String,
		val entry: WifiHistoryEntry?,
		val structuralRuns: List<WifiHistoryStructuralRunProjection>,
	) : WifiRangeWorkItem {
		override val stableIdentity: String = identity
		override val originOrder: Int = 1
	}
}

private val WIFI_RANGE_WORK_ORDER =
	compareByDescending<WifiRangeWorkItem> { it.startTimeMs }
		.thenBy(WifiRangeWorkItem::originOrder)
		.thenByDescending(WifiRangeWorkItem::stableIdentity)

internal fun isWifiCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == WIFI_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		source.persistenceEligible
