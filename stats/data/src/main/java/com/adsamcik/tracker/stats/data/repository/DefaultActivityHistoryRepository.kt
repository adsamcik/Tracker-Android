package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportedActivityOrderedProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityOrderedProductPage
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedActivityResult
import com.adsamcik.tracker.shared.base.database.RetainedImportedActivityIdentity
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityLogicalRangeCandidate
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
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryPage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeUnavailableReason
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
	private val rangeIssuer = Any()
	private val localPortableReader = RoomReadLocalPortableCapturedActivity(
		database,
		laneExecutionAuthority,
	)

	override suspend fun session(segmentId: Long): ActivityHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction { sessionInTransaction(segmentId) }
		}
	}

	internal suspend fun sessionInTransaction(segmentId: Long): ActivityHistoryQuery {
		require(segmentId > 0L)
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return ActivityHistoryQuery.NotFound
		val expansion = expandActivityMembership(listOf(seed))
		val snapshot = loadActivitySnapshot(expansion)
		val entry = ActivityHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
		return entry?.let(ActivityHistoryQuery::Found) ?: ActivityHistoryQuery.NotFound
	}

	/** Transaction-scoped local source group retaining exact authenticated physical membership. */
	internal suspend fun sourceSessionInTransaction(segmentId: Long): ActivitySourceQuery {
		require(segmentId > 0L)
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return ActivitySourceQuery.NotFound
		val expansion = expandActivityMembership(listOf(seed))
		val snapshot = loadActivitySnapshot(expansion)
		val logicalId = seed.logicalTrackingId ?: return ActivitySourceQuery.NotFound
		val composed = ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
			.singleOrNull { it.logicalTrackingId == logicalId }
			?: return ActivitySourceQuery.NotFound
		return ActivitySourceQuery.Found(ActivitySourceComposedEntry.Local(composed))
	}

	override suspend fun recent(limit: Int): ActivityHistoryPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction {
				when (val page = recentActivityHistoryInTransaction(limit)) {
					is ActivitySourceComposedPage.Available ->
						ActivityHistoryPage.Available(page.entries.map(ActivitySourceComposedEntry::entry))
					is ActivitySourceComposedPage.Failed -> ActivityHistoryPage.Failed(page.cause)
				}
			}
		}
	}

	/**
	 * Combined local/imported recent producer for the shared history facade. Both origins are
	 * normalized by newest physical-member recency before duplicate suppression and final cutoff.
	 */
	internal suspend fun recentActivityHistoryInTransaction(
		limit: Int,
	): ActivitySourceComposedPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		val localPage = when (val page = loadOrderedRecentLocalPage()) {
			is ActivityLocalOrderedPage.Failed ->
				return ActivitySourceComposedPage.Failed(page.cause)
			is ActivityLocalOrderedPage.Ready -> page
		}
		val local = localPage.entries
		val importedPage = try {
			importedProductReader.selectOrderedRecentPageInTransaction(
				limit = MAX_ACTIVITY_HISTORY_RESULTS,
				beforeRecencyStartTimeMs = null,
				beforeRecencyMemberIdentity = null,
			)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return ActivitySourceComposedPage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val imported = when (importedPage) {
			is ImportedActivityOrderedProductPage.Unverifiable ->
				return ActivitySourceComposedPage.Failed(importedPage.reason.toHistoryCause())
			is ImportedActivityOrderedProductPage.Ready -> importedPage.evaluations
		}
		val importedHasMore =
			(importedPage as ImportedActivityOrderedProductPage.Ready).hasMore
		val localIdentities = local.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
		val localIdentityByOpaque = localIdentities.associateBy { logicalId ->
			PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		if (localIdentityByOpaque.size != localIdentities.size) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		val importedEvaluations = imported.map { it.evaluation }
		val hasImportedOwnership = importedEvaluations.any {
			it is ImportedActivityProductEvaluation.Readable || it is ImportedActivityProductEvaluation.Retained
		}
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(
				hasImportedOwnership,
				local.map(ComposedActivityEntry::entry),
				localIdentityByOpaque.keys,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivitySourceComposedPage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val localProtectedIdentities = try {
			loadValueFreeLocalProtectedOwnership(local, localPortableEntries)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivitySourceComposedPage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return try {
			val mappedImported = ActivityHistoryOriginComposer.composeImported(
				liveLogicalTrackingIds = localIdentities,
				imported = importedEvaluations,
				localPortableEntriesByIdentity = localPortableEntries,
				localProtectedIdentitiesByEntry = localProtectedIdentities,
			)
			val importedByIdentity = imported.associateBy { it.evaluation.candidate.identity }
			val combined = buildList {
				local.forEach { add(ActivitySourceComposedEntry.Local(it)) }
				mappedImported.forEach { mapped ->
					val entry = mapped.entry ?: return@forEach
					val ordered = importedByIdentity[mapped.evaluation.candidate.identity]
						?: throw ImportedActivityHistoryCompositionFailure()
					add(
						ActivitySourceComposedEntry.Imported(
							entry = entry,
							evaluation = mapped.evaluation,
							recencyStartTimeMs = ordered.recencyStartTimeMs,
							recencyMemberIdentity = ordered.recencyMemberIdentity.value,
						),
					)
				}
			}.sortedWith(ACTIVITY_SOURCE_COMPOSITION_ORDER)
			val localFrontier = local.takeIf { localPage.hasMore }?.lastOrNull()?.orderKey()
			val importedFrontier = imported.takeIf { importedHasMore }
				?.lastOrNull()?.orderKey()
			val safe = combined.takeWhile { entry ->
				entry.orderKey().isSafeBeforeUnseen(localFrontier, importedFrontier)
			}.take(limit)
			if (safe.size < limit && (localPage.hasMore || importedHasMore)) {
				return ActivitySourceComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			}
			ActivitySourceComposedPage.Available(safe)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun loadOrderedRecentLocalPage(): ActivityLocalOrderedPage {
		val rows = database.activityCapturedFactDao().logicalHistoryCandidatePage(
			limit = MAX_ACTIVITY_HISTORY_RESULTS + 1,
			beforeStartTimeMs = null,
			beforeSegmentId = null,
			activitySourceKind = ACTIVITY_SOURCE,
		)
		if (!isValidLocalRecentPage(rows)) {
			return ActivityLocalOrderedPage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val selected = rows.take(MAX_ACTIVITY_HISTORY_RESULTS)
		if (selected.isEmpty()) return ActivityLocalOrderedPage.Ready(emptyList(), false)
		val expansion = expandActivityMembership(selected.map { it.segment })
		val snapshot = loadActivitySnapshot(expansion)
		if (snapshot.overflow) {
			return ActivityLocalOrderedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val selectedIds = selected.mapNotNull { it.segment.logicalTrackingId }.toSet()
		val composed = ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
			.filter { it.logicalTrackingId in selectedIds }
			.sortedWith(activityCompositionOrder)
		if (composed.size != selected.size || composed.indices.any { index ->
			composed[index].recencyStartTimeMs != selected[index].logicalRecencyStartMs ||
				composed[index].recencySegmentId != selected[index].logicalRecencySegmentId
		}) {
			return ActivityLocalOrderedPage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		return ActivityLocalOrderedPage.Ready(composed, rows.size > MAX_ACTIVITY_HISTORY_RESULTS)
	}

	override suspend fun range(
		request: ActivityHistoryRangeRequest,
	): ActivityHistoryRangePage = withContext(ioDispatcher) {
		database.withTransaction { rangeInTransaction(request) }
	}

	/** Complete mixed-origin Activity range page for callers already holding the Room transaction. */
	@Suppress("LongMethod", "CyclomaticComplexMethod")
	internal suspend fun rangeInTransaction(
		request: ActivityHistoryRangeRequest,
	): ActivityHistoryRangePage {
		val revision = currentRangeRevision()
			?: return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		val cursor = when (val supplied = request.continuation) {
			null -> ActivityHistoryRangeContinuationSnapshot(
				issuer = rangeIssuer,
				scope = request.scope,
				revision = revision,
				localCursor = null,
				importedCursor = null,
			)
			is ActivityHistoryRangeContinuationSnapshot -> supplied.takeIf {
				it.issuer === rangeIssuer && it.scope == request.scope && it.revision == revision
			} ?: return ActivityHistoryRangePage.Unavailable(
				ActivityHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
			else -> return ActivityHistoryRangePage.Unavailable(
				ActivityHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
		}
		val bounds = try {
			request.scope.queryBounds()
		} catch (_: ArithmeticException) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
		}
		val localRows = database.activityCapturedFactDao().logicalHistoryRangeCandidatePage(
			activitySourceKind = ACTIVITY_SOURCE,
			fromInclusiveMs = bounds.fromInclusiveMs,
			toExclusiveMs = bounds.toExclusiveMs,
			limit = RANGE_SOURCE_CANDIDATE_LIMIT + 1,
			beforeRecencyStartTimeMs = cursor.localCursor?.recencyStartTimeMs,
			beforeRecencySegmentId = cursor.localCursor?.recencySegmentId,
		)
		if (!isValidLocalRangePage(localRows, cursor.localCursor)) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val selectedLocalRows = localRows.take(RANGE_SOURCE_CANDIDATE_LIMIT)
		val localSnapshot = if (selectedLocalRows.isEmpty()) {
			null
		} else {
			val expansion = expandActivityMembership(selectedLocalRows.map { it.segment })
			loadActivitySnapshot(expansion).also { snapshot ->
				if (snapshot.overflow) {
					return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
				}
			}
		}
		val localComposed = localSnapshot?.let { snapshot ->
			ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.associateBy(ComposedActivityEntry::logicalTrackingId)
		}.orEmpty()
		val localPrepared = selectedLocalRows.map { row ->
			val logicalId = row.segment.logicalTrackingId
				?: return ActivityHistoryRangePage.Failed(
					ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
				)
			val composed = localComposed[logicalId]
				?: return ActivityHistoryRangePage.Failed(
					ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
				)
			if (composed.recencyStartTimeMs != row.logicalRecencyStartMs ||
				composed.recencySegmentId != row.logicalRecencySegmentId ||
				composed.entry.startTime.raw != row.logicalStartTimeMs ||
				composed.entry.endTime.raw != row.logicalEndTimeMs
			) {
				return ActivityHistoryRangePage.Failed(
					ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
				)
			}
			val temporal = try {
				localActivityTemporalAuthority(composed, requireNotNull(localSnapshot))
			} catch (_: ActivityHistoryRangeLimitExceeded) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			} catch (_: RuntimeException) {
				if (request.scope is ActivityHistoryRangeScope.StructuralDays) {
					return ActivityHistoryRangePage.Failed(
						composed.entry.causes.firstOrNull { it.isIntegrityFailure }
							?: ActivityHistoryCause.STORED_ZONE_INVALID,
					)
				}
				null
			}
			val rangeEntry = try {
				activityHistoryRangeEntry(composed.entry, temporal, request.scope)
			} catch (_: ActivityHistoryRangeLimitExceeded) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			} catch (_: ArithmeticException) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
			} catch (_: RuntimeException) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.STORED_ZONE_INVALID)
			}
			ActivityRangeWorkItem.Local(row, composed, rangeEntry)
		}

		val importedPage = try {
			importedProductReader.selectOrderedRangePageInTransaction(
				fromInclusiveMs = bounds.fromInclusiveMs,
				toExclusiveMs = bounds.toExclusiveMs,
				limit = RANGE_SOURCE_CANDIDATE_LIMIT,
				beforeRecencyStartTimeMs = cursor.importedCursor?.recencyStartTimeMs,
				beforeRecencyMemberIdentity = cursor.importedCursor?.recencyMemberIdentity?.let(
					::PortableActivityOpaqueIdentity,
				),
			)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
		} catch (_: RuntimeException) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val importedOrdered = when (importedPage) {
			is ImportedActivityOrderedProductPage.Unverifiable ->
				return ActivityHistoryRangePage.Failed(importedPage.reason.toHistoryCause())
			is ImportedActivityOrderedProductPage.Ready -> importedPage.evaluations
		}
		if (request.scope is ActivityHistoryRangeScope.StructuralDays) {
			importedOrdered.firstOrNull {
				it.evaluation is ImportedActivityProductEvaluation.Unverifiable
			}?.let { failed ->
				return ActivityHistoryRangePage.Failed(
					(failed.evaluation as ImportedActivityProductEvaluation.Unverifiable)
						.reason.toHistoryCause(),
				)
			}
		}
		val localLogicalIds = localPrepared.mapTo(linkedSetOf()) {
			it.composed.logicalTrackingId
		}
		val localOpaqueIdentities = localLogicalIds.associateBy { logicalId ->
			PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		if (localOpaqueIdentities.size != localLogicalIds.size) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		val importedEvaluations = importedOrdered.map { it.evaluation }
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(
				hasImportedOwnership = importedEvaluations.any {
					it is ImportedActivityProductEvaluation.Readable ||
						it is ImportedActivityProductEvaluation.Retained
				},
				localEntries = localPrepared.map { it.composed.entry },
				localEntryIdentities = localOpaqueIdentities.keys,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val localProtectedIdentities = try {
			loadValueFreeLocalProtectedOwnership(
				localPrepared.map(ActivityRangeWorkItem.Local::composed),
				localPortableEntries,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val mappedImported = try {
			ActivityHistoryOriginComposer.composeImported(
				liveLogicalTrackingIds = localLogicalIds,
				imported = importedEvaluations,
				localPortableEntriesByIdentity = localPortableEntries,
				localProtectedIdentitiesByEntry = localProtectedIdentities,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val mappedByIdentity = mappedImported.associateBy { it.evaluation.candidate.identity }
		val importedPrepared = importedOrdered.map { ordered ->
			val mapped = mappedByIdentity[ordered.evaluation.candidate.identity]
				?: return ActivityHistoryRangePage.Failed(
					ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
				)
			val temporal = try {
				importedActivityTemporalAuthority(ordered.evaluation)
			} catch (_: ActivityHistoryRangeLimitExceeded) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			} catch (_: RuntimeException) {
				if (request.scope is ActivityHistoryRangeScope.StructuralDays) {
					return ActivityHistoryRangePage.Failed(
						ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
				}
				null
			}
			val rangeEntry = mapped.entry?.let { publicEntry ->
				try {
					activityHistoryRangeEntry(publicEntry, temporal, request.scope)
				} catch (_: ActivityHistoryRangeLimitExceeded) {
					return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
				} catch (_: ArithmeticException) {
					return ActivityHistoryRangePage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
				} catch (_: RuntimeException) {
					return ActivityHistoryRangePage.Failed(
						ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
				}
			}
			ActivityRangeWorkItem.Imported(ordered, rangeEntry)
		}
		val work = (localPrepared + importedPrepared).sortedWith(ACTIVITY_RANGE_WORK_ORDER)
		val localHasMore = localRows.size > RANGE_SOURCE_CANDIDATE_LIMIT
		val importedHasMore =
			(importedPage as ImportedActivityOrderedProductPage.Ready).hasMore
		val localFrontier = localPrepared.takeIf { localHasMore }
			?.lastOrNull()?.orderKey()
		val importedFrontier = importedPrepared.takeIf { importedHasMore }
			?.lastOrNull()?.orderKey()
		val output = mutableListOf<com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeEntry>()
		var localCursor = cursor.localCursor
		var importedCursor = cursor.importedCursor
		var consumed = 0
		for (item in work) {
			currentCoroutineContext().ensureActive()
			if (!item.orderKey().isSafeBeforeUnseen(localFrontier, importedFrontier)) break
			when (item) {
				is ActivityRangeWorkItem.Local -> {
					localCursor = ActivityLocalRangeCursor(
						item.row.logicalRecencyStartMs,
						item.row.logicalRecencySegmentId,
					)
				}
				is ActivityRangeWorkItem.Imported -> {
					importedCursor = ActivityImportedRangeCursor(
						item.ordered.recencyStartTimeMs,
						item.ordered.recencyMemberIdentity.value,
					)
				}
			}
			item.rangeEntry?.let(output::add)
			consumed++
			if (output.size == request.limit) break
		}
		val hasMore = consumed < work.size ||
			localHasMore || importedHasMore
		val continuation = if (hasMore) {
			ActivityHistoryRangeContinuationSnapshot(
				issuer = rangeIssuer,
				scope = request.scope,
				revision = revision,
				localCursor = localCursor,
				importedCursor = importedCursor,
			)
		} else {
			null
		}
		return ActivityHistoryRangePage.Available(output, continuation)
	}

	private suspend fun currentRangeRevision(): ActivityHistorySnapshotRevision? {
		val evidence = database.sourceEvidenceStateDao().get() ?: return null
		if (evidence.revision < 0L || evidence.collectedDataEpoch < 0L) return null
		return ActivityHistorySnapshotRevision(
			evidenceRevision = evidence.revision,
			collectedDataEpoch = evidence.collectedDataEpoch,
			local = database.activityCapturedFactDao().productRevisionSnapshot(ACTIVITY_SOURCE),
			imported = importedProductReader.productRevisionSnapshotInTransaction(),
		)
	}

	private suspend fun loadLocalPortableOwnershipSnapshot(
		hasImportedOwnership: Boolean,
		localEntries: List<ActivityHistoryEntry>,
		localEntryIdentities: Set<String>,
	): Map<String, PortableActivityEntryV1> {
		if (!hasImportedOwnership || localEntryIdentities.isEmpty()) return emptyMap()
		if (localEntries.size != localEntryIdentities.size) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		val earliest = localEntries.minOf { it.startTime.raw }
		val latest = localEntries.maxOf { it.endTime.raw }
		val latestExclusive = if (latest > earliest) latest else try {
			Math.addExact(latest, 1L)
		} catch (_: ArithmeticException) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		return when (val result = localPortableReader.read(
			ExportPortableCapturedActivityRequest(earliest, latestExclusive),
		)) {
			is ReadLocalPortableCapturedActivityResult.Ready -> {
				// Seed every overlapping local owner, including bounded rows outside the visible page.
				val completeSnapshot = result.envelope.entries.associateBy { it.identity.value }
				if (completeSnapshot.size != result.envelope.entries.size) {
					throw ImportedActivityHistoryCompositionFailure()
				}
				completeSnapshot
			}
			is ReadLocalPortableCapturedActivityResult.Unverifiable,
			ReadLocalPortableCapturedActivityResult.StorageUnavailable,
			-> throw ImportedActivityHistoryCompositionFailure()
			ReadLocalPortableCapturedActivityResult.NoEntries -> if (localEntries.all {
				it.activeTime == null && it.fragments.isEmpty()
			}) {
				emptyMap()
			} else {
				throw ImportedActivityHistoryCompositionFailure()
			}
		}
	}

	private suspend fun loadValueFreeLocalProtectedOwnership(
		local: List<ComposedActivityEntry>,
		portableEntriesByIdentity: Map<String, PortableActivityEntryV1>,
	): Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> {
		if (local.isEmpty()) return emptyMap()
		val segmentIds = local.flatMap(ComposedActivityEntry::physicalSegmentIds).distinct()
		if (segmentIds.size > MAX_ACTIVITY_LOGICAL_MEMBERS) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
		if (segments.size != segmentIds.size) throw ImportedActivityHistoryCompositionFailure()
		val segmentsByLogical = segments.groupBy(SessionSegment::logicalTrackingId)
		return local.mapNotNull { composed ->
			val entryIdentity = PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				composed.logicalTrackingId,
			)
			if (entryIdentity.value in portableEntriesByIdentity) return@mapNotNull null
			if (composed.entry.activeTime != null || composed.entry.fragments.isNotEmpty()) {
				throw ImportedActivityHistoryCompositionFailure()
			}
			val members = segmentsByLogical[composed.logicalTrackingId].orEmpty()
			if (members.size != composed.physicalSegmentIds.size ||
				members.map(SessionSegment::id).toSet() != composed.physicalSegmentIds.toSet()
			) throw ImportedActivityHistoryCompositionFailure()
			val retained = buildList {
				add(RetainedImportedActivityIdentity.Entry(entryIdentity))
				members.forEach { segment ->
					val runId = segment.serviceRunId ?: throw ImportedActivityHistoryCompositionFailure()
					add(
						RetainedImportedActivityIdentity.Run(
							PortableActivityOpaqueIdentity.derive(
								PortableActivityIdentityKind.PHYSICAL_RUN,
								runId,
							),
						),
					)
					add(
						RetainedImportedActivityIdentity.DeletionScope(
							com.adsamcik.tracker.shared.base.database
								.PortableActivityDeletionScopeDigest.derive(
									composed.logicalTrackingId,
									runId,
								),
						),
					)
				}
			}
			entryIdentity to retained
		}.toMap()
	}

	/** Exact Activity groups for a bounded caller-owned physical candidate generation. */
	internal suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
	): ActivityComposedPage {
		require(segmentIds.size <= MAX_ACTIVITY_HISTORY_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) return ActivityComposedPage.Available(emptyList())
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		val expansion = expandActivityMembership(seeds)
		val snapshot = loadActivitySnapshot(expansion)
		if (snapshot.overflow) return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
		return ActivityComposedPage.Available(
			ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in logicalIds },
		)
	}

	/** Intent-first recent Activity-only rows; absence of a captured fact is not a filter. */
	internal suspend fun recentActivityOnlyInTransaction(limit: Int): ActivityComposedPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		return loadRecentActivityCompositions(limit) { it.entry.capturesOnlyActivity }
	}

	@Suppress("CyclomaticComplexMethod")
	private suspend fun loadRecentActivityCompositions(
		limit: Int,
		accept: (ComposedActivityEntry) -> Boolean,
	): ActivityComposedPage {
		val accepted = mutableListOf<ComposedActivityEntry>()
		var scanned = 0
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		while (accepted.size < limit) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_ACTIVITY_CANDIDATE_SCAN - scanned
			if (remaining == 0) {
				return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val pageLimit = minOf(ACTIVITY_CANDIDATE_PAGE_SIZE, remaining)
			val page = database.activityCapturedFactDao().logicalHistoryCandidatePage(
				limit = pageLimit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeSegmentId = beforeSegmentId,
				activitySourceKind = ACTIVITY_SOURCE,
			)
			if (page.isEmpty()) break
			if (page.size > pageLimit) {
				return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			}
			scanned += page.size
			val seeds = page.map { it.segment }
			val expansion = expandActivityMembership(seeds)
			val snapshot = loadActivitySnapshot(expansion)
			if (snapshot.overflow) {
				return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val candidateIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			accepted += ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in candidateIds && accept(it) }
				.sortedWith(activityCompositionOrder)
			val last = page.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (page.size < pageLimit) break
		}
		return ActivityComposedPage.Available(accepted.take(limit))
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
	val physicalSegmentIds: List<Long>,
	val entry: com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry,
)

internal sealed interface ActivityComposedPage {
	data class Available(val entries: List<ComposedActivityEntry>) : ActivityComposedPage
	data class Failed(val cause: ActivityHistoryCause) : ActivityComposedPage
}

internal sealed interface ActivitySourceComposedEntry {
	val entry: ActivityHistoryEntry
	val recencyStartTimeMs: Long
	val recencyTieBreaker: String
	val originOrder: Int

	data class Local(
		val composed: ComposedActivityEntry,
	) : ActivitySourceComposedEntry {
		override val entry: ActivityHistoryEntry get() = composed.entry
		override val recencyStartTimeMs: Long get() = composed.recencyStartTimeMs
		override val recencyTieBreaker: String
			get() = composed.recencySegmentId.toString().padStart(20, '0')
		override val originOrder: Int = 0
	}

	data class Imported(
		override val entry: ActivityHistoryEntry,
		val evaluation: ImportedActivityProductEvaluation,
		override val recencyStartTimeMs: Long,
		val recencyMemberIdentity: String,
	) : ActivitySourceComposedEntry {
		override val recencyTieBreaker: String get() = recencyMemberIdentity
		override val originOrder: Int = 1
	}
}

internal sealed interface ActivitySourceComposedPage {
	data class Available(
		val entries: List<ActivitySourceComposedEntry>,
	) : ActivitySourceComposedPage

	data class Failed(
		val cause: ActivityHistoryCause,
	) : ActivitySourceComposedPage
}

internal sealed interface ActivitySourceQuery {
	data object NotFound : ActivitySourceQuery

	data class Found(
		val entry: ActivitySourceComposedEntry.Local,
	) : ActivitySourceQuery
}

private sealed interface ActivityLocalOrderedPage {
	data class Ready(
		val entries: List<ComposedActivityEntry>,
		val hasMore: Boolean,
	) : ActivityLocalOrderedPage

	data class Failed(
		val cause: ActivityHistoryCause,
	) : ActivityLocalOrderedPage
}

private sealed interface ActivityRangeWorkItem {
	val recencyStartTimeMs: Long
	val recencyTieBreaker: String
	val originOrder: Int
	val rangeEntry: ActivityHistoryRangeEntry?

	data class Local(
		val row: ActivityLogicalRangeCandidate,
		val composed: ComposedActivityEntry,
		override val rangeEntry: ActivityHistoryRangeEntry?,
	) : ActivityRangeWorkItem {
		override val recencyStartTimeMs: Long get() = row.logicalRecencyStartMs
		override val recencyTieBreaker: String
			get() = row.logicalRecencySegmentId.toString().padStart(20, '0')
		override val originOrder: Int = 0
	}

	data class Imported(
		val ordered: ImportedActivityOrderedProductEvaluation,
		override val rangeEntry: ActivityHistoryRangeEntry?,
	) : ActivityRangeWorkItem {
		override val recencyStartTimeMs: Long get() = ordered.recencyStartTimeMs
		override val recencyTieBreaker: String get() = ordered.recencyMemberIdentity.value
		override val originOrder: Int = 1
	}
}

private data class ActivityOrderKey(
	val recencyStartTimeMs: Long,
	val originOrder: Int,
	val tieBreaker: String,
)

private fun ActivitySourceComposedEntry.orderKey() = ActivityOrderKey(
	recencyStartTimeMs,
	originOrder,
	recencyTieBreaker,
)

private fun ImportedActivityOrderedProductEvaluation.orderKey() = ActivityOrderKey(
	recencyStartTimeMs,
	1,
	recencyMemberIdentity.value,
)

private fun ActivityRangeWorkItem.orderKey() = ActivityOrderKey(
	recencyStartTimeMs,
	originOrder,
	recencyTieBreaker,
)

private fun ActivityOrderKey.isSafeBeforeUnseen(
	localFrontier: ActivityOrderKey?,
	importedFrontier: ActivityOrderKey?,
): Boolean = sequenceOf(localFrontier, importedFrontier)
	.filterNotNull()
	.all { frontier -> ACTIVITY_ORDER_KEY_COMPARATOR.compare(this, frontier) <= 0 }

private fun isValidLocalRecentPage(
	rows: List<com.adsamcik.tracker.shared.base.database.dao.ActivityLogicalHistoryCandidate>,
): Boolean = rows.size <= MAX_ACTIVITY_HISTORY_RESULTS + 1 &&
	rows.mapNotNull { it.segment.logicalTrackingId }.distinct().size == rows.size &&
	rows.all {
		!it.segment.logicalTrackingId.isNullOrBlank() &&
			it.logicalRecencyStartMs == it.segment.startTimeMs &&
			it.logicalRecencySegmentId == it.segment.id
	} &&
	rows.zipWithNext().all { (left, right) ->
		left.logicalRecencyStartMs > right.logicalRecencyStartMs ||
			left.logicalRecencyStartMs == right.logicalRecencyStartMs &&
			left.logicalRecencySegmentId > right.logicalRecencySegmentId
	}

private fun isValidLocalRangePage(
	rows: List<ActivityLogicalRangeCandidate>,
	cursor: ActivityLocalRangeCursor?,
): Boolean {
	if (rows.size > RANGE_SOURCE_CANDIDATE_LIMIT + 1 ||
		rows.mapNotNull { it.segment.logicalTrackingId }.distinct().size != rows.size ||
		rows.any {
			it.segment.logicalTrackingId.isNullOrBlank() ||
				it.logicalStartTimeMs < 0L ||
				it.logicalEndTimeMs < it.logicalStartTimeMs ||
				it.logicalRecencyStartMs != it.segment.startTimeMs ||
				it.logicalRecencySegmentId != it.segment.id
		}
	) return false
	val tuples = buildList {
		cursor?.let { add(it.recencyStartTimeMs to it.recencySegmentId) }
		addAll(rows.map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
	}
	return tuples.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

internal val activityCompositionOrder =
	compareByDescending<ComposedActivityEntry> { it.recencyStartTimeMs }
		.thenByDescending { it.recencySegmentId }

private val ACTIVITY_SOURCE_COMPOSITION_ORDER =
	compareByDescending<ActivitySourceComposedEntry> { it.recencyStartTimeMs }
		.thenBy(ActivitySourceComposedEntry::originOrder)
		.thenByDescending(ActivitySourceComposedEntry::recencyTieBreaker)

private val ACTIVITY_RANGE_WORK_ORDER =
	compareByDescending<ActivityRangeWorkItem> { it.recencyStartTimeMs }
		.thenBy(ActivityRangeWorkItem::originOrder)
		.thenByDescending(ActivityRangeWorkItem::recencyTieBreaker)

private val ACTIVITY_ORDER_KEY_COMPARATOR =
	compareByDescending(ActivityOrderKey::recencyStartTimeMs)
		.thenBy(ActivityOrderKey::originOrder)
		.thenByDescending(ActivityOrderKey::tieBreaker)

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
private const val RANGE_SOURCE_CANDIDATE_LIMIT = 100
