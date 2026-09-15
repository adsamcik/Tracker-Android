package com.adsamcik.tracker.stats.data.repository

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
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WifiHistoryRepository {
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

	override suspend fun lookup(selection: WifiHistorySelection): WifiHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				try {
					when (val deleted = deletedHistoryReader.readDeletedInTransaction(selection)) {
						is WifiDeletedHistoryResult.Deleted ->
							return@withTransaction WifiHistoryQuery.Found(deleted.entry)
						is WifiDeletedHistoryResult.Unverifiable ->
							return@withTransaction WifiHistoryQuery.Failed(
								if (selection is WifiHistorySelection.Imported) {
									WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
								} else {
									WifiHistoryCause.FACT_INTEGRITY_FAILED
								},
							)
						WifiDeletedHistoryResult.NotDeleted -> Unit
					}
					when (selection) {
						is WifiHistorySelection.Imported -> {
							val evaluation = importedProductEvaluator.selectIdentityInTransaction(
								selection.selected.key,
							) ?: return@withTransaction WifiHistoryQuery.NotFound
							importedQueryInTransaction(evaluation, selection.selected)
						}
						is WifiHistorySelection.Local -> localQueryInTransaction(selection.key)
					}
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
		return WifiHistoryQuery.Found(evaluation.toPublicWifiEntry(originConflict))
	}

	@Suppress("LongMethod", "ReturnCount")
	private suspend fun localQueryInTransaction(
		selection: WifiLocalHistorySelectionKey,
	): WifiHistoryQuery {
		val dao = database.importedWifiDao()
		val expectedCount = dao.localEntryOwnerCount()
		if (expectedCount < 0L ||
			expectedCount > ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS
		) return WifiHistoryQuery.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		val matches = mutableListOf<String>()
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = dao.localEntryOwnerPage(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left >= right } ||
				after?.let { previous ->
					page.firstOrNull()?.let { first -> first <= previous }
				} == true
			) return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			page.forEach { logicalId ->
				val key = PortableWifiOpaqueIdentity.derive(
					PortableWifiIdentityKind.LOGICAL_ENTRY,
					logicalId,
				).value
				if (key == selection.value) matches += logicalId
			}
			loaded = Math.addExact(loaded, page.size.toLong())
			if (loaded > expectedCount) {
				return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			}
			if (page.isEmpty() || page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
			after = page.last()
		}
		if (loaded != expectedCount) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val logicalId = matches.singleOrNull() ?: return if (matches.isEmpty()) {
			WifiHistoryQuery.NotFound
		} else {
			WifiHistoryQuery.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
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
		if (segments.size != segmentIds.size) {
			return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val seed = segments.minByOrNull(SessionSegment::id)
			?: return WifiHistoryQuery.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		val query = sessionInTransaction(seed.id)
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
	@Suppress("LongMethod")
	internal suspend fun recentInTransaction(limit: Int): WifiSourceRecentPage {
		require(limit in 1..MAX_RESULTS)
		val live = loadRecentLocal(limit)
		if (live.page is WifiHistoryPage.Failed) {
			return WifiSourceRecentPage.Failed(live.page.cause)
		}
		val imported = try {
			importedProductEvaluator.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val collisions = imported.filterIsInstance<ImportedWifiProductEvaluation.Readable>()
			.mapNotNull(ImportedWifiProductEvaluation.Readable::collidingLocalLogicalTrackingId)
			.distinct()
		if (collisions.size > MAX_EXACT_ORIGIN_COLLISIONS) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
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
						return WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
				}
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		return try {
			WifiHistoryOriginComposer.composeSourceRecent(
				live = live.entries,
				imported = imported,
				localPortableByLogicalId = localPortable,
				limit = limit,
			)
		} catch (_: ImportedWifiHistoryCompositionFailure) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		} catch (_: ArithmeticException) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			WifiSourceRecentPage.Failed(WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	/** Exact complete local Wi-Fi groups for one bounded caller-owned physical candidate set. */
	internal suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
	): WifiComposedPage {
		require(segmentIds.size <= MAX_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) return WifiComposedPage.Available(emptyList())
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		if (seeds.size != segmentIds.size) {
			return WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val expansion = expandMembership(seeds)
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
	@Suppress("CyclomaticComplexMethod")
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
			if (page.isEmpty()) break
			if (page.size > pageLimit) {
				return WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			}
			scanned += page.size
			val seedIds = page.map { it.sortSegmentId }
			if (seedIds.distinct().size != seedIds.size) {
				return WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			}
			val seeds = database.trackingHistoryReadDao().segments(seedIds)
			if (seeds.size != seedIds.size) {
				return WifiComposedPage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			}
			val expansion = expandMembership(seeds)
			expansion.failures.values.firstOrNull()?.let { return WifiComposedPage.Failed(it) }
			val snapshot = loadSnapshot(expansion)
			if (snapshot.overflow) {
				return WifiComposedPage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val candidateIds = page.mapNotNull { it.logicalTrackingId }.toSet()
			accepted += WifiHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in candidateIds && it.entry.capturesOnlyWifi }
				.sortedWith(wifiCompositionOrder)
			val last = page.last()
			beforeStartTimeMs = last.sortStartTimeMs
			beforeSegmentId = last.sortSegmentId
			if (page.size < pageLimit) break
		}
		return WifiComposedPage.Available(accepted.take(limit))
	}

	override suspend fun range(request: WifiHistoryRangeRequest): WifiHistoryRangePage =
		withContext(ioDispatcher) {
			database.withTransaction { rangeInTransaction(request) }
		}

	override suspend fun structuralDays(
		request: WifiHistoryRangeRequest,
	): WifiHistoryStructuralDayPage = withContext(ioDispatcher) {
		database.withTransaction {
			when (val page = rangeInTransaction(request)) {
				is WifiHistoryRangePage.Failed -> WifiHistoryStructuralDayPage.Failed(page.cause)
				is WifiHistoryRangePage.Available -> try {
					WifiHistoryStructuralDayPage.Available(
						days = WifiHistoryStructuralDayComposer.compose(
							page.entries,
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

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	internal suspend fun rangeInTransaction(
		request: WifiHistoryRangeRequest,
	): WifiHistoryRangePage {
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
		val selectedLocalRows = localRows.take(RANGE_SOURCE_CANDIDATE_LIMIT)
		val localComposed = if (selectedLocalRows.isEmpty()) {
			emptyMap()
		} else {
			val expansion = expandMembership(selectedLocalRows.map { it.segment })
			expansion.failures.values.firstOrNull()?.let {
				return WifiHistoryRangePage.Failed(it)
			}
			val snapshot = loadSnapshot(expansion)
			if (snapshot.overflow) {
				return WifiHistoryRangePage.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)
			}
			WifiHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.associateBy(ComposedWifiEntry::logicalTrackingId)
		}
		val localItems = selectedLocalRows.map { row ->
			val logicalId = row.segment.logicalTrackingId
				?: return WifiHistoryRangePage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			val composed = localComposed[logicalId]
				?: return WifiHistoryRangePage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			if (composed.entry.startTime.raw != row.logicalStartTimeMs ||
				composed.entry.endTime.raw != row.logicalEndTimeMs
			) return WifiHistoryRangePage.Failed(WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			WifiRangeWorkItem.Local(composed.recencyStartTimeMs, logicalId, composed)
		}

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
		val importedItems = importedPage.evaluations.map { evaluation ->
			val readable = evaluation as? ImportedWifiProductEvaluation.Readable
			val collision = readable?.collidingLocalLogicalTrackingId
			val collidingPortable = localPortable
			val publicEntry = if (collision == null) {
				evaluation.toPublicWifiEntry()
			} else {
				val colliding = requireNotNull(readable)
				when {
					collidingPortable == null || collidingPortable.first != collision ->
						return WifiHistoryRangePage.Failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
					colliding.isReExportable && colliding.entry == collidingPortable.second -> null
					else -> evaluation.toPublicWifiEntry(originConflict = true)
				}
			}
			WifiRangeWorkItem.Imported(
				startTimeMs = evaluation.candidate.newestMemberStartTimeMs,
				identity = evaluation.candidate.newestMemberIdentity.value,
				entry = publicEntry,
			)
		}

		val work = (localItems + importedItems).sortedWith(WIFI_RANGE_WORK_ORDER)
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
				}
				is WifiRangeWorkItem.Imported -> {
					importedStart = item.startTimeMs
					importedIdentity = item.identity
					item.entry?.let(output::add)
				}
			}
			consumed += 1
			if (output.size == request.limit) break
		}
		val hasMore = consumed < work.size ||
			localRows.size > RANGE_SOURCE_CANDIDATE_LIMIT ||
			importedPage.hasMore
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
			if (candidates.isEmpty()) break
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val expansion = expandMembership(seeds)
			expansion.failures.values.firstOrNull()?.let {
				return LiveWifiHistoryPage(WifiHistoryPage.Failed(it), emptyList())
			}
			val snapshot = loadSnapshot(expansion)
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
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(byId::get)
			if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) failures[run.logicalTrackingId] = WifiHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
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
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			val run = seed.serviceRunId?.let(runById::get)
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
		const val RANGE_SOURCE_CANDIDATE_LIMIT = 100
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
