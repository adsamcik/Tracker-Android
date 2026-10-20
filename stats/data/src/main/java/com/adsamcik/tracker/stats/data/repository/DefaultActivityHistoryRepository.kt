package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityExportUnverifiableReason
import com.adsamcik.tracker.shared.base.database.dao.ActivityCapturedFactDao
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.dao.ActivityLogicalHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryRange

import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.ExportSelectedPortableCapturedActivityRequest
import com.adsamcik.tracker.shared.base.database.ImportedActivityOrderedProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityOrderedProductPage
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductScanLimits
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductScanPage
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
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
import com.adsamcik.tracker.shared.base.database.data.MAX_ACTIVITY_WAL_PAYLOAD_BYTES
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
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryQuery
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeUnavailableReason
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRepository
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryRunDeletionScope
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Activity history facade. All dependencies are read in one bounded Room snapshot. */
// One source-owned Room snapshot coordinates Activity membership, native and imported history without splitting its authority.
@Suppress("LargeClass", "TooManyFunctions")
internal class DefaultActivityHistoryRepository private constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val currentClockDomainProvider: (() -> String)?,
) : ActivityHistoryRepository, ActivityImportedHistoryEligibleReader {
	@Inject
	internal constructor(
		database: AppDatabase,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(database, laneExecutionAuthority, ioDispatcher,
		currentClockDomainProvider = bootClockDomainProvider::current)

	internal constructor(
		database: AppDatabase,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		ioDispatcher: CoroutineDispatcher,
	) : this(database, laneExecutionAuthority, ioDispatcher,
		currentClockDomainProvider = null)

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
			database.withTransaction { recentInTransaction(limit) }
		}
	}

	/**
	 * Shared-facade bridge over the authenticated combined Activity producer.
	 *
	 * The caller owns the Room transaction; newest-member ordering, origin normalization, complete
	 * source authentication, and final cutoff remain inside [recentActivityHistoryInTransaction].
	 */
	internal suspend fun recentInTransaction(limit: Int): ActivityHistoryPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		return when (val page = recentActivityHistoryInTransaction(limit)) {
			is ActivitySourceComposedPage.Available ->
				ActivityHistoryPage.Available(page.entries.map(ActivitySourceComposedEntry::entry))
			is ActivitySourceComposedPage.Failed -> ActivityHistoryPage.Failed(page.cause)
		}
	}

	/**
	 * Combined local/imported recent producer for the shared history facade. Both origins are
	 * normalized by newest physical-member recency before duplicate suppression and final cutoff.
	 */
	internal suspend fun recentActivityHistoryInTransaction(limit: Int): ActivitySourceComposedPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		val localPage = when (val page = loadOrderedRecentLocalPage()) {
			is ActivityLocalOrderedPage.Failed -> return ActivitySourceComposedPage.Failed(page.cause)
			is ActivityLocalOrderedPage.Ready -> page
		}
		return readRecentImportedActivity(localPage, limit)
	}

	private suspend fun readRecentImportedActivity(
		localPage: ActivityLocalOrderedPage.Ready,
		limit: Int,
	): ActivitySourceComposedPage {
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
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val imported = when (importedPage) {
			is ImportedActivityOrderedProductPage.Unverifiable ->
				return ActivitySourceComposedPage.Failed(importedPage.reason.toHistoryCause())
			is ImportedActivityOrderedProductPage.Ready -> importedPage
		}
		return qualifyRecentActivityOrigins(localPage, imported, limit)
	}

	private suspend fun qualifyRecentActivityOrigins(
		localPage: ActivityLocalOrderedPage.Ready,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		limit: Int,
	): ActivitySourceComposedPage {
		val localIdentities = localPage.entries.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
		if (activityLogicalOpaqueIdentities(localIdentities) == null) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		}
		val importedEvaluations = importedPage.evaluations.map { it.evaluation }
		val selectionSnapshot = if (importedEvaluations.any { it is ImportedActivityProductEvaluation.Readable }) {
			currentImportedSelectionReadSnapshot()
				?: return ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		} else {
			null
		}
		val ownershipLocal = when (val page = loadLocalOwnershipForImportedEligibility(importedEvaluations)) {
			is ActivityComposedPage.Failed -> return ActivitySourceComposedPage.Failed(page.cause)
			is ActivityComposedPage.Available -> page.entries
		}
		return readRecentActivityPortableOwnership(localPage, importedPage, selectionSnapshot, ownershipLocal, limit)
	}

	private suspend fun readRecentActivityPortableOwnership(
		localPage: ActivityLocalOrderedPage.Ready,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
		ownershipLocal: List<ComposedActivityEntry>,
		limit: Int,
	): ActivitySourceComposedPage {
		val ownershipLogicalIds = ownershipLocal.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
		val ownershipIdentities = activityLogicalOpaqueIdentities(ownershipLogicalIds)
			?: return ActivitySourceComposedPage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		val hasImportedOwnership = importedPage.evaluations.any {
			it.evaluation is ImportedActivityProductEvaluation.Readable ||
				it.evaluation is ImportedActivityProductEvaluation.Retained
		}
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(hasImportedOwnership, ownershipLocal, ownershipIdentities)
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return composeRecentActivityOwnership(localPage, importedPage, selectionSnapshot,
			ownershipLocal, localPortableEntries, limit)
	}

	private suspend fun composeRecentActivityOwnership(
		localPage: ActivityLocalOrderedPage.Ready,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
		ownershipLocal: List<ComposedActivityEntry>,
		localPortableEntries: Map<String, PortableActivityEntryV1>,
		limit: Int,
	): ActivitySourceComposedPage {
		val hasImportedOwnership = importedPage.evaluations.any {
			it.evaluation is ImportedActivityProductEvaluation.Readable ||
				it.evaluation is ImportedActivityProductEvaluation.Retained
		}
		val localProtectedIdentities = try {
			if (hasImportedOwnership) {
				loadValueFreeLocalProtectedOwnership(ownershipLocal, localPortableEntries)
			} else {
				emptyMap()
			}
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return try {
			val local = localPage.entries
			val imported = importedPage.evaluations
			val liveLogicalIds = local.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId) +
				ownershipLocal.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
			val combined = composeRecentActivityEntries(local, imported, selectionSnapshot,
				liveLogicalIds, localPortableEntries, localProtectedIdentities)
			recentActivityFrontierPage(localPage, importedPage, combined, limit)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			ActivitySourceComposedPage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun composeRecentActivityEntries(
		local: List<ComposedActivityEntry>,
		imported: List<ImportedActivityOrderedProductEvaluation>,
		selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
		liveLogicalTrackingIds: Set<String>,
		localPortableEntries: Map<String, PortableActivityEntryV1>,
		localProtectedIdentities: Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>>,
	): List<ActivitySourceComposedEntry> {
		val mappedImported = ActivityHistoryOriginComposer.composeImported(
			liveLogicalTrackingIds = liveLogicalTrackingIds,
			imported = imported.map { it.evaluation },
			localPortableEntriesByIdentity = localPortableEntries,
			localProtectedIdentitiesByEntry = localProtectedIdentities,
		)
		val importedByIdentity = imported.associateBy { it.evaluation.candidate.identity }
		return buildList {
			local.forEach { add(ActivitySourceComposedEntry.Local(it)) }
			mappedImported.forEach { mapped ->
				val entry = mapped.entry ?: return@forEach
				val ordered = importedByIdentity[mapped.evaluation.candidate.identity]
					?: throw ImportedActivityHistoryCompositionFailure()
				val importedSelection =
					if (entry.state == ActivityHistoryProductState.FAILED) {
						null
					} else {
						mapped.evaluation.toPublicSelection(
							key = entry.key,
							readSnapshot = selectionSnapshot,
						)
					}
				add(
					ActivitySourceComposedEntry.Imported(
						entry = entry.copy(importedSelection = importedSelection),
						evaluation = mapped.evaluation,
						recencyStartTimeMs = ordered.recencyStartTimeMs,
						recencyMemberIdentity = ordered.recencyMemberIdentity.value,
					),
				)
			}
		}.sortedWith(ACTIVITY_SOURCE_COMPOSITION_ORDER)
	}

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
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
		if (selected.isEmpty()) {
			return ActivityLocalOrderedPage.Ready(emptyList(), false)
		}
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
	internal suspend fun rangeInTransaction(
		request: ActivityHistoryRangeRequest,
	): ActivityHistoryRangePage {
		val revision = currentRangeRevision()
			?: return ActivityHistoryRangePage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		val cursor = activityRangeCursor(request, revision)
			?: return ActivityHistoryRangePage.Unavailable(
				ActivityHistoryRangeUnavailableReason.INVALID_CONTINUATION,
			)
		return readActivityRangeLocalPage(request, revision, cursor)
	}

	private fun activityRangeCursor(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
	): ActivityHistoryRangeContinuationSnapshot? = when (val supplied = request.continuation) {
		null -> ActivityHistoryRangeContinuationSnapshot(
			issuer = rangeIssuer,
			scope = request.scope,
			revision = revision,
			localCursor = null,
			importedCursor = null,
		)
		is ActivityHistoryRangeContinuationSnapshot -> supplied.takeIf {
			it.issuer === rangeIssuer && it.scope == request.scope && it.revision == revision
		}
		else -> null
	}

	private suspend fun readActivityRangeLocalPage(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
	): ActivityHistoryRangePage {
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
		return prepareLocalActivityRange(request, revision, cursor, bounds, localRows, localSnapshot)
	}

	private suspend fun prepareLocalActivityRange(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		bounds: ActivityHistoryQueryBounds,
		localRows: List<ActivityLogicalRangeCandidate>,
		localSnapshot: ActivityHistorySnapshot?,
	): ActivityHistoryRangePage {
		val localComposed = localSnapshot?.let { snapshot ->
			ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.associateBy(ComposedActivityEntry::logicalTrackingId)
		}.orEmpty()
		val localPrepared = localRows.take(RANGE_SOURCE_CANDIDATE_LIMIT).map { row ->
			val composed = row.segment.logicalTrackingId?.let(localComposed::get)
				?: return ActivityHistoryRangePage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			if (composed.recencyStartTimeMs != row.logicalRecencyStartMs ||
				composed.recencySegmentId != row.logicalRecencySegmentId ||
				composed.entry.startTime.raw != row.logicalStartTimeMs ||
				composed.entry.endTime.raw != row.logicalEndTimeMs
			) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
			}
			val projection = localActivityRangeProjection(composed, localSnapshot, request.scope)
			if (projection !is ActivityHistoryRangePage.Available) {
				return projection
			}
			ActivityRangeWorkItem.Local(row, composed, projection.entries.singleOrNull())
		}
		return readActivityRangeImportedPage(request, revision, cursor, bounds, localRows, localPrepared)
	}

	private fun localActivityRangeProjection(
		composed: ComposedActivityEntry,
		snapshot: ActivityHistorySnapshot?,
		scope: ActivityHistoryRangeScope,
	): ActivityHistoryRangePage {
		val temporal = try {
			localActivityTemporalAuthority(composed, requireNotNull(snapshot))
		} catch (_: ActivityHistoryRangeLimitExceeded) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: RuntimeException) {
			if (scope is ActivityHistoryRangeScope.StructuralDays) {
				return ActivityHistoryRangePage.Failed(
					composed.entry.causes.firstOrNull { it.isIntegrityFailure }
						?: ActivityHistoryCause.STORED_ZONE_INVALID,
				)
			}
			null
		}
		return projectedActivityRangePage(composed.entry, temporal, scope, ActivityHistoryCause.STORED_ZONE_INVALID)
	}

	private fun projectedActivityRangePage(
		entry: ActivityHistoryEntry,
		temporal: ActivityTemporalAuthority?,
		scope: ActivityHistoryRangeScope,
		invalidCause: ActivityHistoryCause,
	): ActivityHistoryRangePage = try {
		ActivityHistoryRangePage.Available(listOfNotNull(activityHistoryRangeEntry(entry, temporal, scope)), null)
	} catch (_: ActivityHistoryRangeLimitExceeded) {
		ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
	} catch (_: ArithmeticException) {
		ActivityHistoryRangePage.Failed(ActivityHistoryCause.VALUE_OVERFLOW)
	} catch (_: RuntimeException) {
		ActivityHistoryRangePage.Failed(invalidCause)
	}

	private suspend fun readActivityRangeImportedPage(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		bounds: ActivityHistoryQueryBounds,
		localRows: List<ActivityLogicalRangeCandidate>,
		localPrepared: List<ActivityRangeWorkItem.Local>,
	): ActivityHistoryRangePage {
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
		return when (importedPage) {
			is ImportedActivityOrderedProductPage.Unverifiable ->
				ActivityHistoryRangePage.Failed(importedPage.reason.toHistoryCause())
			is ImportedActivityOrderedProductPage.Ready ->
				importedActivityStructuralDisposition(importedPage.evaluations, request.scope)
					?: readActivityRangeOwnership(request, revision, cursor, localRows, localPrepared, importedPage)
		}
	}

	private fun importedActivityStructuralDisposition(
		importedOrdered: List<ImportedActivityOrderedProductEvaluation>,
		scope: ActivityHistoryRangeScope,
	): ActivityHistoryRangePage? {
		if (scope is ActivityHistoryRangeScope.StructuralDays) {
			if (importedOrdered.any { ordered ->
					(ordered.evaluation as? ImportedActivityProductEvaluation.Retained)
						?.hasTemporalAuthority == false
				}
			) {
				return ActivityHistoryRangePage.Unavailable(
					ActivityHistoryRangeUnavailableReason.TEMPORAL_AUTHORITY_UNAVAILABLE,
				)
			}
			importedOrdered.firstOrNull {
				it.evaluation is ImportedActivityProductEvaluation.Unverifiable
			}?.let { failed ->
				return ActivityHistoryRangePage.Failed(
					(failed.evaluation as ImportedActivityProductEvaluation.Unverifiable)
						.reason.toHistoryCause(),
				)
			}
		}
		return null
	}

	private suspend fun readActivityRangeOwnership(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		localRows: List<ActivityLogicalRangeCandidate>,
		localPrepared: List<ActivityRangeWorkItem.Local>,
		importedPage: ImportedActivityOrderedProductPage.Ready,
	): ActivityHistoryRangePage {
		val localLogicalIds = localPrepared.mapTo(linkedSetOf()) { it.composed.logicalTrackingId }
		val localOpaqueIdentities = activityLogicalOpaqueIdentities(localLogicalIds)
			?: return ActivityHistoryRangePage.Failed(ActivityHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		val importedEvaluations = importedPage.evaluations.map { it.evaluation }
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(
				hasImportedOwnership = importedEvaluations.any {
					it is ImportedActivityProductEvaluation.Readable ||
						it is ImportedActivityProductEvaluation.Retained
				},
				localEntries = localPrepared.map { it.composed },
				localEntryIdentities = localOpaqueIdentities,
			)
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return composeActivityRangeOrigins(request, revision, cursor, localRows, localPrepared,
			importedPage, localPortableEntries)
	}

	// Carry this one immutable source snapshot across ownership qualification without another context model.
	@Suppress("LongParameterList")
	private suspend fun composeActivityRangeOrigins(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		localRows: List<ActivityLogicalRangeCandidate>,
		localPrepared: List<ActivityRangeWorkItem.Local>,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		localPortableEntries: Map<String, PortableActivityEntryV1>,
	): ActivityHistoryRangePage {
		val localProtectedIdentities = try {
			loadValueFreeLocalProtectedOwnership(
				localPrepared.map(ActivityRangeWorkItem.Local::composed),
				localPortableEntries,
			)
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val mappedImported = try {
			ActivityHistoryOriginComposer.composeImported(
				liveLogicalTrackingIds = localPrepared.mapTo(linkedSetOf()) { it.composed.logicalTrackingId },
				imported = importedPage.evaluations.map { it.evaluation },
				localPortableEntriesByIdentity = localPortableEntries,
				localProtectedIdentitiesByEntry = localProtectedIdentities,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return ActivityHistoryRangePage.Failed(
				ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return prepareImportedActivityRange(request, revision, cursor, localRows,
			localPrepared, importedPage, mappedImported)
	}

	// Keep the selected source pages and issued continuation together through final import projection.
	@Suppress("LongParameterList")
	private suspend fun prepareImportedActivityRange(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		localRows: List<ActivityLogicalRangeCandidate>,
		localPrepared: List<ActivityRangeWorkItem.Local>,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		mappedImported: List<ComposedImportedActivityEntry>,
	): ActivityHistoryRangePage {
		val selectionReadSnapshot = ActivityImportedHistoryReadSnapshot(
			collectedDataEpoch = revision.collectedDataEpoch,
			sourceEvidenceRevision = revision.evidenceRevision,
		)
		val mappedByIdentity = mappedImported.associateBy { it.evaluation.candidate.identity }
		val importedPrepared = importedPage.evaluations.map { ordered ->
			val mapped = mappedByIdentity[ordered.evaluation.candidate.identity]
				?: return ActivityHistoryRangePage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			val projection = importedActivityRangeProjection(mapped, ordered.evaluation, selectionReadSnapshot, request.scope)
			if (projection !is ActivityHistoryRangePage.Available) {
				return projection
			}
			ActivityRangeWorkItem.Imported(ordered, projection.entries.singleOrNull())
		}
		return completeActivityRangePage(request, revision, cursor, localRows,
			localPrepared, importedPage, importedPrepared)
	}

	private fun importedActivityRangeProjection(
		mapped: ComposedImportedActivityEntry,
		evaluation: ImportedActivityProductEvaluation,
		selectionReadSnapshot: ActivityImportedHistoryReadSnapshot,
		scope: ActivityHistoryRangeScope,
	): ActivityHistoryRangePage {
		val temporal = try {
			importedActivityTemporalAuthority(evaluation)
		} catch (_: ActivityHistoryRangeLimitExceeded) {
			return ActivityHistoryRangePage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: RuntimeException) {
			if (scope is ActivityHistoryRangeScope.StructuralDays) {
				return ActivityHistoryRangePage.Failed(ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			}
			null
		}
		val publicEntry = mapped.entry ?: return ActivityHistoryRangePage.Available(emptyList(), null)
		val selectedEntry = publicEntry.copy(
			importedSelection = if (publicEntry.state == ActivityHistoryProductState.FAILED) {
				null
			} else {
				mapped.evaluation.toPublicSelection(key = publicEntry.key, readSnapshot = selectionReadSnapshot)
			},
		)
		return projectedActivityRangePage(selectedEntry, temporal, scope,
			ActivityHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
	}

	// Keep the same issued snapshot, source frontiers and cursor inputs without a second paging model.
	@Suppress("LongParameterList")
	private suspend fun completeActivityRangePage(
		request: ActivityHistoryRangeRequest,
		revision: ActivityHistorySnapshotRevision,
		cursor: ActivityHistoryRangeContinuationSnapshot,
		localRows: List<ActivityLogicalRangeCandidate>,
		localPrepared: List<ActivityRangeWorkItem.Local>,
		importedPage: ImportedActivityOrderedProductPage.Ready,
		importedPrepared: List<ActivityRangeWorkItem.Imported>,
	): ActivityHistoryRangePage {
		val work = (localPrepared + importedPrepared).sortedWith(ACTIVITY_RANGE_WORK_ORDER)
		val localHasMore = localRows.size > RANGE_SOURCE_CANDIDATE_LIMIT
		val importedHasMore = importedPage.hasMore
		val localFrontier = localPrepared.takeIf { localHasMore }
			?.lastOrNull()?.orderKey()
		val importedFrontier = importedPrepared.takeIf { importedHasMore }
			?.lastOrNull()?.orderKey()
		val output = mutableListOf<ActivityHistoryRangeEntry>()
		var localCursor = cursor.localCursor
		var importedCursor = cursor.importedCursor
		var consumed = 0
		for (item in work) {
			currentCoroutineContext().ensureActive()
			if (!item.orderKey().isSafeBeforeUnseen(localFrontier, importedFrontier)) {
				break
			}
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
			if (output.size == request.limit) {
				break
			}
		}
		val hasMore = consumed < work.size ||
			localHasMore || importedHasMore
		val continuation = activityRangeContinuation(request, revision, localCursor, importedCursor, hasMore)
		return ActivityHistoryRangePage.Available(output, continuation)
	}

	private fun activityRangeContinuation(
		request: ActivityHistoryRangeRequest, revision: ActivityHistorySnapshotRevision,
		localCursor: ActivityLocalRangeCursor?, importedCursor: ActivityImportedRangeCursor?, hasMore: Boolean,
	): ActivityHistoryRangeContinuationSnapshot? = if (hasMore) {
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

	private suspend fun currentRangeRevision(): ActivityHistorySnapshotRevision? {
		val evidence = database.sourceEvidenceStateDao().get() ?: return null
		if (evidence.revision < 0L || evidence.collectedDataEpoch < 0L) {
			return null
		}
		return ActivityHistorySnapshotRevision(
			evidenceRevision = evidence.revision,
			collectedDataEpoch = evidence.collectedDataEpoch,
			local = database.activityCapturedFactDao().productRevisionSnapshot(ACTIVITY_SOURCE),
			imported = importedProductReader.productRevisionSnapshotInTransaction(),
		)
	}

	/**
	 * Imported-only shared-history bridge. The caller owns the Room transaction.
	 *
	 * Complete imported and local ownership is authenticated before duplicates or conflicts are
	 * resolved. Only then are accepted imported entries ordered by authenticated newest-run
	 * recency and limited. Fresh retained receipts carry authenticated member recency; migrated
	 * receipts without that authority fail explicitly instead of borrowing envelope or receipt time.
	 */
	override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> =
		recentImportedEligibleForSharedHistoryInTransaction(
			limit,
			ImportedActivityProductScanLimits(),
		)

	internal suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
		scanLimits: ImportedActivityProductScanLimits,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		val importedPage = try {
			importedProductReader.selectAllForSharedHistoryInTransaction(scanLimits)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		} catch (_: RuntimeException) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val imported = when (importedPage) {
			is ImportedActivityProductScanPage.Ready -> importedPage.evaluations
			is ImportedActivityProductScanPage.Unverifiable -> return unavailable(
				when (importedPage.reason) {
					ImportedActivityProductFailure
						.DEPENDENCY_OVERFLOW ->
						SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
					ImportedActivityProductFailure
						.TEMPORAL_AUTHORITY_UNAVAILABLE ->
						SourceAwareHistoryPageUnavailableReason
							.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE
					else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
		}
		return qualifyImportedForSharedHistoryInTransaction(imported, limit)
	}

	/** Arbitrary-range keys reuse the same ownership, origin and selection qualification. */
	internal suspend fun selectedImportedForSharedHistoryInTransaction(
		identities: List<String>,
		range: HistoryRange,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
		require(identities.size in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		require(identities.distinct().size == identities.size)
		val imported = identities.map { identity ->
			currentCoroutineContext().ensureActive()
			importedProductReader.selectIdentityInTransaction(PortableActivityOpaqueIdentity(identity))
				?: return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		return qualifyImportedForSharedHistoryInTransaction(imported, identities.size, range, includeActivityOnly =
            true)
	}

	private suspend fun qualifyImportedForSharedHistoryInTransaction(
		imported: List<ImportedActivityProductEvaluation>,
		limit: Int,
		range: HistoryRange? = null,
		includeActivityOnly: Boolean = false,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
		val selectionSnapshot = if (imported.any { it is ImportedActivityProductEvaluation.Readable }) {
			currentImportedSelectionReadSnapshot()
				?: return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		} else {
			null
		}
		val authenticated = when (val page = authenticateImportedActivityEligibility(imported)) {
			is ImportedHistoryEligiblePage.Unavailable -> return page
			is ImportedHistoryEligiblePage.Available -> page.entries
		}
		val local = when (val page = loadLocalOwnershipForImportedEligibility(imported)) {
			is ActivityComposedPage.Failed -> return unavailable(
				if (page.cause == ActivityHistoryCause.READ_BUDGET_EXCEEDED) {
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
				} else {
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
			is ActivityComposedPage.Available -> page.entries
		}
		return qualifyImportedActivityNativeOwnership(authenticated, local, selectionSnapshot,
			limit, range, includeActivityOnly)
	}

	private suspend fun qualifyImportedActivityNativeOwnership(
		authenticated: List<AuthenticatedImportedActivityEligibility>,
		local: List<ComposedActivityEntry>,
		selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
		limit: Int,
		range: HistoryRange?,
		includeActivityOnly: Boolean,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
		val localLogicalIds = local.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
		if (activityLogicalOpaqueIdentities(localLogicalIds) == null) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val localProtectedIdentities = try {
			loadLocalProtectedOwnership(local)
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		return qualifyImportedActivityCollisions(authenticated, local, selectionSnapshot,
			localProtectedIdentities, limit, range, includeActivityOnly)
	}

	// Preserve the captured native/imported ownership and selection inputs without an extra context model.
	@Suppress("LongParameterList")
	private suspend fun qualifyImportedActivityCollisions(
		authenticated: List<AuthenticatedImportedActivityEligibility>,
		local: List<ComposedActivityEntry>,
		selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
		localProtectedIdentities: Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>>,
		limit: Int,
		range: HistoryRange?,
		includeActivityOnly: Boolean,
	): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
		val collisionLocal = activityCollisionEntries(authenticated, local, localProtectedIdentities)
		val collisionIdentities = collisionLocal.mapTo(linkedSetOf()) {
			PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY, it.logicalTrackingId,
			).value
		}
		val localPortableEntries = try {
			loadLocalPortableOwnershipSnapshot(
				hasImportedOwnership = collisionIdentities.isNotEmpty(),
				localEntries = collisionLocal,
				localEntryIdentities = collisionIdentities,
			)
		} catch (_: ActivityLocalOwnershipBudgetExceeded) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val remainingLocalProtectedIdentities = localProtectedIdentities.filterKeys {
			it.value !in localPortableEntries
		}
		val visibleLocalLogicalIds = if (range == null) {
			local.mapTo(linkedSetOf(), ComposedActivityEntry::logicalTrackingId)
		} else {
			// Only selected exact collisions need a metadata visibility probe; qualification is above.
			database.nativeLogicalIdsVisibleInRange(collisionLocal.mapTo(linkedSetOf()) {
				it.logicalTrackingId
			}, range)
		}
		val mapped = try {
			ActivityHistoryOriginComposer.composeImported(
				liveLogicalTrackingIds = visibleLocalLogicalIds,
				imported = authenticated.map { it.evaluation },
				localPortableEntriesByIdentity = localPortableEntries,
				localProtectedIdentitiesByEntry = remainingLocalProtectedIdentities,
			)
		} catch (_: ImportedActivityHistoryCompositionFailure) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		return acceptedImportedActivityPage(mapped, authenticated, selectionSnapshot, limit, includeActivityOnly)
	}

	private suspend fun loadLocalPortableOwnershipSnapshot(
		hasImportedOwnership: Boolean,
		localEntries: List<ComposedActivityEntry>,
		localEntryIdentities: Set<String>,
	): Map<String, PortableActivityEntryV1> {
		if (!hasImportedOwnership || localEntryIdentities.isEmpty()) {
			return emptyMap()
		}
		if (localEntries.size != localEntryIdentities.size) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		// Factless qualified owners still participate in the value-free collision checks.
		// Strict portable completeness applies only to owners with retained measurements.
		val valuedEntries = localEntries.filter { it.entry.activeTime != null || it.entry.fragments.isNotEmpty() }
		if (valuedEntries.isEmpty()) {
			return emptyMap()
		}
		return qualifiedLocalActivityPortableSnapshot(
			readValuedLocalActivityOwnership(valuedEntries),
			localEntryIdentities,
		)
	}

	private suspend fun readValuedLocalActivityOwnership(
		valuedEntries: List<ComposedActivityEntry>,
	): ReadLocalPortableCapturedActivityResult {
		val epoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch
			?: throw ImportedActivityHistoryCompositionFailure()
		val seeds = valuedEntries.map { entry ->
			entry.physicalSegmentIds.firstOrNull() ?: throw ImportedActivityHistoryCompositionFailure()
		}
		return localPortableReader.read(
			ExportSelectedPortableCapturedActivityRequest(seeds, emptyList(), epoch),
		)
	}

	private fun qualifiedLocalActivityPortableSnapshot(
		result: ReadLocalPortableCapturedActivityResult,
		localEntryIdentities: Set<String>,
	): Map<String, PortableActivityEntryV1> = when (result) {
		is ReadLocalPortableCapturedActivityResult.Ready ->
			completeLocalActivityPortableSnapshot(result.envelope.entries, localEntryIdentities)
		else -> {
			if (result is ReadLocalPortableCapturedActivityResult.Unverifiable &&
				result.reason == PortableActivityExportUnverifiableReason.DEPENDENCY_OVERFLOW
			) {
				throw ActivityLocalOwnershipBudgetExceeded()
			}
			throw ImportedActivityHistoryCompositionFailure()
		}
	}

	private fun completeLocalActivityPortableSnapshot(
		entries: List<PortableActivityEntryV1>,
		localEntryIdentities: Set<String>,
	): Map<String, PortableActivityEntryV1> {
		val completeSnapshot = entries.associateBy { it.identity.value }
		if (completeSnapshot.size != entries.size ||
			completeSnapshot.keys.any { it !in localEntryIdentities }
		) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		return completeSnapshot
	}

	private suspend fun loadLocalProtectedOwnership(
		local: List<ComposedActivityEntry>,
	): Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> {
		if (local.isEmpty()) {
			return emptyMap()
		}
		val segments = loadActivityOwnershipSegments(local)
		val segmentsByLogical = segments.groupBy(SessionSegment::logicalTrackingId)
		val logicalIds = local.map(ComposedActivityEntry::logicalTrackingId)
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).distinct()
		val revisionLoad = loadActivityRevisionPages(runIds, logicalIds)
		if (revisionLoad.overflow) {
			throw ActivityLocalOwnershipBudgetExceeded()
		}
		return local.associate { composed ->
			protectedActivityIdentities(
				composed,
				segmentsByLogical[composed.logicalTrackingId].orEmpty(),
				revisionLoad.revisions,
			)
		}
	}

	private suspend fun loadActivityOwnershipSegments(
		local: List<ComposedActivityEntry>,
	): List<SessionSegment> {
		val segmentIds = local.flatMap(ComposedActivityEntry::physicalSegmentIds).distinct()
		if (segmentIds.size > MAX_ACTIVITY_LOGICAL_MEMBERS) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
		if (segments.size != segmentIds.size) {
			throw ImportedActivityHistoryCompositionFailure()
		}
		return segments
	}

	private suspend fun loadValueFreeLocalProtectedOwnership(
		local: List<ComposedActivityEntry>,
		portableEntriesByIdentity: Map<String, PortableActivityEntryV1>,
	): Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> {
		val valueFree = local.filter { composed ->
			val entryIdentity = PortableActivityOpaqueIdentity.derive(
				PortableActivityIdentityKind.LOGICAL_ENTRY,
				composed.logicalTrackingId,
			)
			if (entryIdentity.value in portableEntriesByIdentity) {
				return@filter false
			}
			if (composed.entry.activeTime != null || composed.entry.fragments.isNotEmpty()) {
				throw ImportedActivityHistoryCompositionFailure()
			}
			true
		}
		return loadLocalProtectedOwnership(valueFree)
	}

	/** Exact Activity groups for a bounded caller-owned physical candidate generation. */
	internal suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
		sourceOnly: Boolean = false,
	): ActivityComposedPage {
		require(segmentIds.size <= MAX_ACTIVITY_HISTORY_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) {
			return ActivityComposedPage.Available(emptyList())
		}
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		val selectedSeeds = if (sourceOnly) {
			database.sourceOnlyHistoryCandidates(seeds, ACTIVITY_SOURCE)
		} else {
			seeds
		}
		val expansion = expandActivityMembership(selectedSeeds)
		val snapshot = loadActivitySnapshot(expansion)
		if (snapshot.overflow) {
			return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
		return ActivityComposedPage.Available(
			ActivityHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in logicalIds },
		)
	}

	/** Exact measured lineage qualification; the outer facade owns the Room read transaction. */
	internal suspend fun selectMeasuredWindowsInTransaction(keys: List<ActivityRevisionKey>): ActivityMeasuredWindowsRead {
		require(keys.size <= MAX_ACTIVITY_HISTORY_RESULTS && keys.distinct().size == keys.size &&
			keys.map { Triple(it.writerProjectionId, it.writerProjectionVersion,
                it.logicalWindowId) }.distinct().size == keys.size)
		if (keys.isEmpty()) {
			return ActivityMeasuredWindowsRead()
		}
		val revisionLoad = loadMeasuredActivityLineages(keys)
		if (revisionLoad.overflow) {
			return ActivityMeasuredWindowsRead(causes = setOf(ActivityHistoryCause.READ_BUDGET_EXCEEDED))
		}
		val snapshot = loadMeasuredActivitySnapshot(revisionLoad)
		if (snapshot.overflow) {
			return ActivityMeasuredWindowsRead(causes = setOf(ActivityHistoryCause.READ_BUDGET_EXCEEDED))
		}
		return qualifyMeasuredActivityWindows(keys, revisionLoad, snapshot)
	}

	private suspend fun loadMeasuredActivitySnapshot(
		revisionLoad: ActivityRevisionLoad,
	): ActivityHistorySnapshot {
		val segmentIds = revisionLoad.revisions.map(ActivityCapturedWindowRevisionEntity::sessionSegmentId).distinct()
		val seeds = if (segmentIds.isEmpty()) {
			emptyList()
		} else {
			database.trackingHistoryReadDao().segments(segmentIds)
		}
		return loadActivitySnapshot(ActivityMembershipExpansion(seeds, emptyMap(), overflow = false),
            revisionLoad)
	}

	private fun qualifyMeasuredActivityWindows(
		keys: List<ActivityRevisionKey>,
		revisionLoad: ActivityRevisionLoad,
		snapshot: ActivityHistorySnapshot,
	): ActivityMeasuredWindowsRead {
		val qualified = linkedMapOf<ActivityRevisionKey, ActivityMeasuredWindow>()
		val causes = linkedSetOf<ActivityHistoryCause>()
		val logicalIds = revisionLoad.revisions.map(ActivityCapturedWindowRevisionEntity::logicalTrackingId).distinct()
		for (logicalId in logicalIds) {
			val composed = ActivityHistoryComposer.composeMeasuredWindows(logicalId, snapshot, laneExecutionAuthority)
			if (composed == null || composed.fragments.isEmpty()) {
				causes += composed?.causes ?: setOf(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
				continue
			}
			appendMeasuredActivityWindows(logicalId, keys, snapshot, qualified, causes)
		}
		if (qualified.keys != keys.toSet() && causes.isEmpty()) {
			causes += ActivityHistoryCause.FACT_INTEGRITY_FAILED
		}
		return ActivityMeasuredWindowsRead(qualified, causes)
	}

	private fun appendMeasuredActivityWindows(
		logicalId: String,
		keys: List<ActivityRevisionKey>,
		snapshot: ActivityHistorySnapshot,
		qualified: MutableMap<ActivityRevisionKey, ActivityMeasuredWindow>,
		causes: MutableSet<ActivityHistoryCause>,
	) {
		for (key in keys) {
			val current = snapshot.revisions.filter { it.writerProjectionId == key.writerProjectionId &&
				it.writerProjectionVersion == key.writerProjectionVersion &&
                        it.logicalWindowId == key.logicalWindowId }
				.maxByOrNull(ActivityCapturedWindowRevisionEntity::semanticRevision) ?: continue
			if (current.logicalTrackingId != logicalId) {
				continue
			}
			if (revisionKey(current) != key) {
				causes += ActivityHistoryCause.FACT_INTEGRITY_FAILED
				continue
			}
			val saved =
				snapshot.fragmentsByRevision[key].orEmpty()
					.sortedBy(ActivityCapturedFragmentEntity::fragmentOrdinal)
			val fragments = ActivityHistoryComposer.toPublicFragments(current, snapshot)
			qualified[key] = ActivityMeasuredWindow(current, saved.zip(fragments).associate { (row, fragment) ->
				row.fragmentOrdinal to fragment })
		}
	}

	private suspend fun loadMeasuredActivityLineages(keys: List<ActivityRevisionKey>): ActivityRevisionLoad {
		val revisions = mutableListOf<ActivityCapturedWindowRevisionEntity>()
		for (key in keys) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_ACTIVITY_REVISIONS - revisions.size
			val lineage = database.activityCapturedFactDao().revisionsForWindow(key.writerProjectionId,
				key.writerProjectionVersion, key.logicalWindowId, remaining + 1)
			if (lineage.size > remaining) {
				return ActivityRevisionLoad(revisions, overflow = true)
			}
			revisions += lineage
		}
		return ActivityRevisionLoad(revisions, overflow = false)
	}

	/** Intent-first recent Activity-only rows; absence of a captured fact is not a filter. */
	internal suspend fun recentActivityOnlyInTransaction(limit: Int): ActivityComposedPage {
		require(limit in 1..MAX_ACTIVITY_HISTORY_RESULTS)
		return loadRecentActivityCompositions(limit) { it.entry.capturesOnlyActivity }
	}

	private suspend fun loadLocalOwnershipForImportedEligibility(
		imported: List<ImportedActivityProductEvaluation>,
	): ActivityComposedPage {
		val identities = importedActivityProtectedIdentities(imported)
		return try {
			val seeds = database.nativePortableOwnerSeeds(ACTIVITY_SOURCE, identities, MAX_ACTIVITY_HISTORY_RESULTS)
			when (val selected = selectBySegmentIdsInTransaction(seeds.map(SessionSegment::id))) {
				is ActivityComposedPage.Failed -> selected
				is ActivityComposedPage.Available -> if (selected.entries.map { it.logicalTrackingId }.toSet() ==
					seeds.map { it.logicalTrackingId }.toSet()) {
					selected
				}
				else {
					ActivityComposedPage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
				}
			}
		} catch (_: NativePortableOwnerBudgetExceeded) {
			ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		} catch (_: NativePortableOwnerMembershipInvalid) {
			ActivityComposedPage.Failed(ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
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
			if (page.isEmpty()) {
				break
			}
			if (page.size > pageLimit) {
				return ActivityComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
			}
			scanned += page.size
			val seeds = page.map { it.segment }
			val selectedSeeds = database.sourceOnlyHistoryCandidates(seeds, ACTIVITY_SOURCE)
			val expansion = expandActivityMembership(selectedSeeds)
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
			if (page.size < pageLimit) {
				break
			}
		}
		return ActivityComposedPage.Available(accepted.take(limit))
	}

	private suspend fun expandActivityMembership(
		seeds: List<SessionSegment>,
	): ActivityMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank)
			.distinct()
		if (logicalIds.isEmpty()) {
			return ActivityMembershipExpansion(seeds, emptyMap(), false)
		}
		val runs = loadLogicalActivityRuns(logicalIds)
			?: return ActivityMembershipExpansion(seeds, emptyMap(), overflow = true)
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loadedSegments = if (segmentIds.isEmpty()) {
			emptyList()
		} else {
			database.trackingHistoryReadDao().segments(segmentIds)
		}
		val segments = (loadedSegments + seeds).distinctBy(SessionSegment::id)
		return ActivityMembershipExpansion(
			segments,
			activityMembershipFailures(logicalIds, runs, segments, seeds),
			overflow = false,
		)
	}

	private suspend fun loadLogicalActivityRuns(
		logicalIds: List<String>,
	): List<SourceServiceRunEntity>? {
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
				return null
			}
			if (page.isEmpty()) {
				break
			}
			runs += page
			val last = page.last()
			afterLogicalId = last.logicalTrackingId
			afterStartedAtMs = last.startedAtMs
			afterRunId = last.serviceRunId
			if (page.size < pageLimit) {
				break
			}
		}
		return runs
	}

	// One fixed Room snapshot reads its dependencies in order and assembles the source-owned projection.
	@Suppress("LongMethod")
	private suspend fun loadActivitySnapshot(
		expansion: ActivityMembershipExpansion,
		selectedLineages: ActivityRevisionLoad? = null,
	): ActivityHistorySnapshot {
		if (expansion.overflow) {
			return ActivityHistorySnapshot.overflow(expansion)
		}
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
			.filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) {
			return ActivityHistorySnapshot.empty(expansion)
		}
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
		val revisionLoad = selectedLineages ?: loadActivityRevisionPages(runIds, logicalIds)
		val revisions = revisionLoad.revisions
		currentCoroutineContext().ensureActive()
		val windowIds = revisions.map(ActivityCapturedWindowRevisionEntity::logicalWindowId).distinct()
		val cursors = factDao.readActivityHistoryCursors(windowIds, selectedLineages, revisions)
		val fragments = factDao.readActivityHistoryFragments(windowIds, selectedLineages, revisions)
		val evidence = factDao.readActivityHistoryEvidence(windowIds, selectedLineages, revisions)
		val walByEventId = readActivityHistoryWal(evidence)
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
		val registrationPlans = factDao.readActivityHistoryRegistrationPlans(
			sourceInstanceIds, registrationGenerations,
		)
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val acquisitionPlanRevisions = if (planRevisions.isEmpty()) {
			emptyList()
		} else {
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_ACTIVITY_ACQUISITION_PLANS + 1)
		}
		val desiredPlans = if (planRevisions.isEmpty()) {
			emptyList()
		} else {
			factDao.historyDesiredPlans(ACTIVITY_SOURCE, planRevisions, MAX_ACTIVITY_DESIRED_PLANS + 1)
		}
		val providerRegistrations = if (registrationGenerations.isEmpty()) {
			emptyList()
		} else {
			factDao.historyProviderRegistrations(
				ACTIVITY_SOURCE, registrationGenerations, MAX_ACTIVITY_PROVIDER_REGISTRATIONS + 1,
			)
		}
		val currentProviderRows = database.sourceBrokerDao().currentPhysicalRegistrationsBounded(
			ACTIVITY_SOURCE,
			MAX_ACTIVITY_PROVIDER_REGISTRATIONS + 1,
		)
		val retiringProviderRows = database.sourceBrokerDao().pendingProviderRemovalsBounded(
			ACTIVITY_SOURCE,
			MAX_ACTIVITY_PROVIDER_REGISTRATIONS + 1,
		)
		val currentProviderRegistrations = (currentProviderRows + retiringProviderRows)
			.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
		val authorizations = if (registrationGenerations.isEmpty()) {
			emptyList()
		} else {
			factDao.historyAuthorizations(
				ACTIVITY_SOURCE, registrationGenerations, MAX_ACTIVITY_AUTHORIZATIONS + 1,
			)
		}
		val consents = readActivityHistoryConsents(manifestSources)
		val deletionFences = readDao.readActivityHistoryDeletionFences(segments)
		currentCoroutineContext().ensureActive()
		val lanes = readDao.productLanesForServiceRuns(
			ACTIVITY_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			runIds,
		)
		val terminalFailures = readDao.readActivityHistoryTerminalFailures(
			runIds, lanes, evidence, completeness, selectedLineages,
		)
		val overflow = revisionLoad.overflow || listOf(
			manifests.size to MAX_ACTIVITY_MANIFESTS,
			manifestSources.size to MAX_ACTIVITY_MANIFEST_SOURCES,
			completeness.size to MAX_ACTIVITY_COMPLETENESS,
			revisions.size to MAX_ACTIVITY_REVISIONS,
			cursors.size to ACTIVITY_HISTORY_MAX_CURSORS,
			fragments.size to ACTIVITY_HISTORY_MAX_FRAGMENTS,
			evidence.size to ACTIVITY_HISTORY_MAX_EVIDENCE,
			registrationPlans.size to ACTIVITY_HISTORY_MAX_REGISTRATION_PLANS,
			acquisitionPlanRevisions.size to MAX_ACTIVITY_ACQUISITION_PLANS,
			desiredPlans.size to MAX_ACTIVITY_DESIRED_PLANS,
			providerRegistrations.size to MAX_ACTIVITY_PROVIDER_REGISTRATIONS,
			currentProviderRows.size to MAX_ACTIVITY_PROVIDER_REGISTRATIONS,
			retiringProviderRows.size to MAX_ACTIVITY_PROVIDER_REGISTRATIONS,
			currentProviderRegistrations.size to MAX_ACTIVITY_PROVIDER_REGISTRATIONS,
			authorizations.size to MAX_ACTIVITY_AUTHORIZATIONS,
			terminalFailures.size to MAX_ACTIVITY_TERMINAL_FAILURES,
		).any { (size, maximum) -> size > maximum }
		return ActivityHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			savedSessionCutoffs = sessions.associate { it.logicalTrackingId to database.savedSessionCutoff(it) },
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
			cursors = cursors.take(ACTIVITY_HISTORY_MAX_CURSORS),
			fragmentsByRevision = fragments.take(ACTIVITY_HISTORY_MAX_FRAGMENTS).groupBy(::revisionKey),
			evidenceByRevision = evidence.take(ACTIVITY_HISTORY_MAX_EVIDENCE).groupBy(::revisionKey),
			walByEventId = walByEventId,
			registrationPlans = registrationPlans.take(ACTIVITY_HISTORY_MAX_REGISTRATION_PLANS)
				.associateBy { it.sourceInstanceId to it.registrationGeneration },
			acquisitionPlanRevisions = acquisitionPlanRevisions.take(MAX_ACTIVITY_ACQUISITION_PLANS)
				.associateBy(AcquisitionPlanRevisionEntity::revision),
			desiredPlans = desiredPlans.take(MAX_ACTIVITY_DESIRED_PLANS)
				.associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providerRegistrations.take(MAX_ACTIVITY_PROVIDER_REGISTRATIONS)
				.associateBy(ProviderRegistrationGenerationEntity::registrationGeneration),
			currentProviderRegistrations =
				currentProviderRegistrations.take(MAX_ACTIVITY_PROVIDER_REGISTRATIONS),
			currentClockDomainId = activityHistoryClockDomain(currentProviderRegistrations),
			authorizationsByRegistration = authorizations.take(MAX_ACTIVITY_AUTHORIZATIONS)
				.groupBy(SourceAuthorizationEntity::registrationGeneration),
			deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { it.scopeIdentityDigest },
			lanes = lanes,
			terminalFailures = terminalFailures.take(MAX_ACTIVITY_TERMINAL_FAILURES),
			evidenceState = database.sourceEvidenceStateDao().get(),
			overflow = overflow,
		)
	}

	private suspend fun TrackingHistoryReadDao.readActivityHistoryTerminalFailures(
		runIds: List<String>,
		lanes: List<SourceProductProjectionLaneEntity>,
		evidence: List<ActivityCapturedEvidenceEntity>,
		completeness: List<SourceSessionCompletenessEntity>,
		selectedLineages: ActivityRevisionLoad?,
	): List<SourceProjectionFailureEntity> {
		val afterOrdinal = lanes.mapNotNull { it.activationOrdinal.takeIf { ordinal -> ordinal > 0L } }
			.minOrNull()?.minus(1L)
		val throughOrdinal = sequenceOf(
			evidence.maxOfOrNull(ActivityCapturedEvidenceEntity::sourceAdmissionOrdinal),
			completeness.asSequence().filter { it.sourceKind == ACTIVITY_SOURCE }
				.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull(),
		).filterNotNull().maxOrNull()
		return if (selectedLineages != null) {
			val projectionDao = database.sourceProjectionStateDao()
			evidence.map(ActivityCapturedEvidenceEntity::sourceAdmissionOrdinal).distinct().mapNotNull { ordinal ->
				currentCoroutineContext().ensureActive()
				projectionDao.failure(SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION, ordinal)?.takeIf { it.terminal }
			}
		} else if (afterOrdinal == null || throughOrdinal == null ||
			afterOrdinal >= throughOrdinal
		) {
			emptyList()
		} else {
			terminalFailuresForServiceRuns(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				runIds,
				afterOrdinal,
				throughOrdinal,
				MAX_ACTIVITY_TERMINAL_FAILURES + 1,
			)
		}
	}

	private suspend fun readActivityHistoryConsents(
		manifestSources: List<SessionManifestSourceEntity>,
	): List<SourceConsentEpochEntity> {
		val consentEpochs = manifestSources.asSequence()
			.filter(::isActivityCaptureMembership)
			.map(SessionManifestSourceEntity::consentEpoch)
			.distinct().toList()
		return if (consentEpochs.isEmpty()) {
			emptyList()
		} else {
			database.sourcePolicyDao().consentEpochs(
				ACTIVITY_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE,
				consentEpochs,
			)
		}
	}

	private fun activityHistoryClockDomain(
		currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	): String? = currentClockDomainProvider?.invoke()
		?: currentProviderRegistrations.singleOrNull {
			it.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		}?.clockDomainId

	private suspend fun readActivityHistoryWal(
		evidence: List<ActivityCapturedEvidenceEntity>,
	): Map<String, SourceEventWalEntity> {
		val activityEventIds = evidence.map(ActivityCapturedEvidenceEntity::sourceEventId).distinct()
		val activityWalRows = mutableListOf<SourceEventWalEntity>()
		for (eventIds in activityEventIds.chunked(ACTIVITY_WAL_ID_BATCH_SIZE)) {
			currentCoroutineContext().ensureActive()
			activityWalRows += database.sourceEventWalDao().boundedPayloadsByEventIds(
				sourceKind = ACTIVITY_SOURCE,
				eventIds = eventIds,
				maximumPayloadBytes = MAX_ACTIVITY_WAL_PAYLOAD_BYTES,
				limit = eventIds.size,
			)
		}
		return activityWalRows.associateBy(SourceEventWalEntity::eventId)
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
			if (page.isEmpty()) {
				return ActivityRevisionLoad(revisions, overflow = false)
			}
			if (page.size > remaining) {
				return ActivityRevisionLoad(revisions, overflow = true)
			}
			revisions += page
			cursor = revisionKey(page.last())
			if (page.size < pageLimit) {
				return ActivityRevisionLoad(revisions, overflow = false)
			}
		}
	}

	private suspend fun currentImportedSelectionReadSnapshot():
		ActivityImportedHistoryReadSnapshot? {
		val state = database.sourceEvidenceStateDao().get() ?: return null
		if (state.revision < 0L || state.collectedDataEpoch < 0L) {
			return null
		}
		return ActivityImportedHistoryReadSnapshot(
			collectedDataEpoch = state.collectedDataEpoch,
			sourceEvidenceRevision = state.revision,
		)
	}
}

private fun ImportedActivityProductEvaluation.toPublicSelection(
	key: ActivityHistoryEntryKey,
	readSnapshot: ActivityImportedHistoryReadSnapshot?,
): ActivityImportedHistorySelection? = when (this) {
	is ImportedActivityProductEvaluation.Readable -> ActivityImportedHistorySelection(
		key = key,
		identity = ActivityImportedHistoryIdentity(entry.identity.value),
		importRevision = candidate.importRevision,
		contentChecksum = ActivityImportedHistoryDigest(entry.contentChecksum.value),
		runDeletionScopes = entry.runs.map { run ->
			ActivityImportedHistoryRunDeletionScope(
				runIdentity = ActivityImportedHistoryIdentity(run.identity.value),
				deletionScopeDigest = ActivityImportedHistoryDeletionScopeDigest(
					run.deletionScopeDigest.value,
				),
			)
		},
		windowIdentities = entry.runs.flatMap { run ->
			run.windows.map { window ->
				ActivityImportedHistoryIdentity(window.identity.value)
			}
		},
		readSnapshot = requireNotNull(readSnapshot),
	)
	is ImportedActivityProductEvaluation.Retained,
	is ImportedActivityProductEvaluation.Unverifiable,
	-> null
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
	val walByEventId: Map<String, SourceEventWalEntity> = emptyMap(),
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
	val currentProviderRegistrations: List<ProviderRegistrationGenerationEntity> = emptyList(),
	val currentClockDomainId: String? = null,
	val savedSessionCutoffs: Map<String, SavedSessionCutoff> = emptyMap(),
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
			walByEventId = emptyMap(),
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

private const val ACTIVITY_WAL_ID_BATCH_SIZE = 400

internal data class ComposedActivityEntry(
	val logicalTrackingId: String,
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
	val physicalSegmentIds: List<Long>,
	val entry: ActivityHistoryEntry,
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
	rows: List<ActivityLogicalHistoryCandidate>,
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
		rows.any { !it.hasValidActivityRangeMembership() }
	) {
		return false
	}
	val tuples = buildList {
		cursor?.let { add(it.recencyStartTimeMs to it.recencySegmentId) }
		addAll(rows.map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
	}
	return tuples.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

private fun authenticateImportedActivityEligibility(
	imported: List<ImportedActivityProductEvaluation>,
): ImportedHistoryEligiblePage<AuthenticatedImportedActivityEligibility> {
	val authenticated = imported.map { evaluation ->
		when (evaluation) {
			is ImportedActivityProductEvaluation.Retained -> {
				if (!evaluation.hasTemporalAuthority) {
					return unavailable(
						SourceAwareHistoryPageUnavailableReason
							.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
					)
				}
				AuthenticatedImportedActivityEligibility(
					evaluation = evaluation,
					identity = PortableActivityOpaqueIdentity(evaluation.candidate.identity),
					contentChecksum = PortableActivityDigest(
						evaluation.candidate.contentChecksum,
					),
					newestMemberStartTimeMs = requireNotNull(
						evaluation.latestMemberStartTimeMs,
					),
					newestMemberTieIdentity = requireNotNull(
						evaluation.latestMemberIdentity,
					).value,
				)
			}
			is ImportedActivityProductEvaluation.Unverifiable -> return unavailable(
				when (evaluation.reason) {
					ImportedActivityProductFailure
						.DEPENDENCY_OVERFLOW ->
						SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
					else -> SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
			is ImportedActivityProductEvaluation.Readable -> evaluation.activityEligibility()
				?: return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
	}
	return ImportedHistoryEligiblePage.Available(authenticated)
}

private fun ImportedActivityProductEvaluation.Readable.activityEligibility():
	AuthenticatedImportedActivityEligibility? {
	val newestRun = entry.runs.maxWith(
		compareBy({ it.startTimeMs }, { it.identity.value }),
	)
	if (candidate.identity != entry.identity.value ||
		candidate.importRevision <= 0L ||
		candidate.contentChecksum != entry.contentChecksum.value ||
		newestRun.startTimeMs !in
		entry.startTimeMs..entry.endTimeMs
	) {
		return null
	}
	return AuthenticatedImportedActivityEligibility(
		evaluation = this,
		identity = entry.identity,
		contentChecksum = entry.contentChecksum,
		newestMemberStartTimeMs = newestRun.startTimeMs,
		newestMemberTieIdentity = newestRun.identity.value,
	)
}

private fun acceptedImportedActivityPage(
	mapped: List<ComposedImportedActivityEntry>,
	authenticated: List<AuthenticatedImportedActivityEligibility>,
	selectionSnapshot: ActivityImportedHistoryReadSnapshot?,
	limit: Int,
	includeActivityOnly: Boolean,
): ImportedHistoryEligiblePage<ActivityImportedHistoryEligibleEntry> {
	val authenticatedByIdentity = authenticated.associateBy {
		it.evaluation.candidate.identity
	}
	val accepted = mapped.mapNotNull { result ->
		val entry = result.entry ?: return@mapNotNull null
		if (entry.origin != ActivityHistoryOrigin.IMPORTED ||
			(!includeActivityOnly && entry.capturesOnlyActivity) ||
			entry.state == ActivityHistoryProductState.FAILED
		) {
			return unavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		val source = authenticatedByIdentity[result.evaluation.candidate.identity]
			?: return unavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		val selectedEntry = entry.copy(
			importedSelection = source.evaluation.toPublicSelection(
				key = entry.key,
				readSnapshot = selectionSnapshot,
			),
		)
		ActivityImportedHistoryEligibleEntry(
			entry = selectedEntry,
			selector = selectedEntry.key,
			identity = source.identity,
			importRevision = source.evaluation.candidate.importRevision,
			contentChecksum = source.contentChecksum,
			recency = ImportedHistoryRecency(
				source = HistorySource.ACTIVITY,
				newestMemberStartTimeMs = source.newestMemberStartTimeMs,
				newestMemberTieIdentity = ImportedHistoryRecencyTieIdentity(
					source.newestMemberTieIdentity,
				),
			),
		)
	}.sortedWith { left, right ->
		importedHistoryRecencyOrder.compare(left.recency, right.recency)
	}
	return ImportedHistoryEligiblePage.Available(accepted.take(limit))
}

private fun activityCollisionEntries(
	authenticated: List<AuthenticatedImportedActivityEligibility>,
	local: List<ComposedActivityEntry>,
	localProtectedIdentities: Map<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>>,
): List<ComposedActivityEntry> {
	val importedEntryIdentities = authenticated.mapTo(linkedSetOf()) { it.identity }
	val collisionIdentities = localProtectedIdentities.keys.filterTo(linkedSetOf()) { it in importedEntryIdentities }
	return local.filter { composed ->
		PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY, composed.logicalTrackingId,
		) in collisionIdentities
	}
}

private fun recentActivityFrontierPage(
	localPage: ActivityLocalOrderedPage.Ready,
	importedPage: ImportedActivityOrderedProductPage.Ready,
	combined: List<ActivitySourceComposedEntry>,
	limit: Int,
): ActivitySourceComposedPage {
	val local = localPage.entries
	val imported = importedPage.evaluations
	val localFrontier = local.takeIf { localPage.hasMore }?.lastOrNull()
		?.let { ActivitySourceComposedEntry.Local(it).orderKey() }
	val importedFrontier = imported.takeIf { importedPage.hasMore }?.lastOrNull()?.orderKey()
	val safe = combined.takeWhile {
		it.orderKey().isSafeBeforeUnseen(localFrontier, importedFrontier)
	}.take(limit)
	return if (safe.size < limit && (localPage.hasMore || importedPage.hasMore)) {
		ActivitySourceComposedPage.Failed(ActivityHistoryCause.READ_BUDGET_EXCEEDED)
	} else {
		ActivitySourceComposedPage.Available(safe)
	}
}

private fun activityLogicalOpaqueIdentities(logicalIds: Set<String>): Set<String>? {
	val identities = logicalIds.associateBy { logicalId ->
		PortableActivityOpaqueIdentity.derive(
			PortableActivityIdentityKind.LOGICAL_ENTRY,
			logicalId,
		).value
	}
	return identities.keys.takeIf { identities.size == logicalIds.size }
}

private fun ActivityLogicalRangeCandidate.hasValidActivityRangeMembership(): Boolean {
	if (segment.logicalTrackingId.isNullOrBlank() ||
		logicalStartTimeMs < 0L || logicalEndTimeMs < logicalStartTimeMs
	) {
		return false
	}
	return logicalRecencyStartMs == segment.startTimeMs && logicalRecencySegmentId == segment.id
}

private fun importedActivityProtectedIdentities(
	imported: List<ImportedActivityProductEvaluation>,
): Sequence<String> {
	return imported.asSequence().flatMap { evaluation ->
		when (evaluation) {
			is ImportedActivityProductEvaluation.Readable -> sequence {
				yield(evaluation.entry.identity.value)
				for (run in evaluation.entry.runs) {
					yield(run.identity.value)
					yield(run.deletionScopeDigest.value)
					run.windows.forEach { yield(it.identity.value) }
				}
			}
			is ImportedActivityProductEvaluation.Retained ->
                    evaluation.protectedIdentities.asSequence().map { it.value }
			is ImportedActivityProductEvaluation.Unverifiable -> emptySequence()
		}
	}
}

private fun protectedActivityIdentities(
	composed: ComposedActivityEntry,
	members: List<SessionSegment>,
	revisions: List<ActivityCapturedWindowRevisionEntity>,
): Pair<PortableActivityOpaqueIdentity, List<RetainedImportedActivityIdentity>> {
	val entryIdentity = PortableActivityOpaqueIdentity.derive(
		PortableActivityIdentityKind.LOGICAL_ENTRY,
		composed.logicalTrackingId,
	)
	if (members.size != composed.physicalSegmentIds.size ||
		members.map(SessionSegment::id).toSet() != composed.physicalSegmentIds.toSet()
	) {
		throw ImportedActivityHistoryCompositionFailure()
	}
	return entryIdentity to buildList {
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
		revisions.asSequence()
			.filter { it.logicalTrackingId == composed.logicalTrackingId }
			.map { revision ->
				RetainedImportedActivityIdentity.Window(
					PortableActivityOpaqueIdentity.derive(
						PortableActivityIdentityKind.CAPTURE_WINDOW,
						revision.logicalWindowId,
					),
				)
			}
			.distinct()
			.forEach(::add)
	}
}

private fun activityMembershipFailures(
	logicalIds: List<String>,
	runs: List<SourceServiceRunEntity>,
	segments: List<SessionSegment>,
	seeds: List<SessionSegment>,
): Map<String, ActivityHistoryCause> {
	val segmentsById = segments.associateBy(SessionSegment::id)
	val runsByLogical = runs.groupBy(SourceServiceRunEntity::logicalTrackingId)
	val failures = logicalIds.filter { runsByLogical[it].isNullOrEmpty() }
		.associateWithTo(linkedMapOf()) { ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID }
	runs.forEach { run ->
		if (!run.ownsActivitySegment(run.sessionSegmentId?.let(segmentsById::get))) {
			failures[run.logicalTrackingId] = ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
	}
	seeds.forEach { seed ->
		val logicalId = seed.logicalTrackingId ?: return@forEach
		val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
		if (!run.ownsActivitySegment(seed)) {
			failures[logicalId] = ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
	}
	return failures
}

private fun SourceServiceRunEntity?.ownsActivitySegment(segment: SessionSegment?): Boolean {
	if (this == null || segment == null) {
		return false
	}
	return segment.serviceRunId == serviceRunId &&
		segment.logicalTrackingId == logicalTrackingId && sessionSegmentId == segment.id
}

private data class AuthenticatedImportedActivityEligibility(
	val evaluation: ImportedActivityProductEvaluation,
	val identity: PortableActivityOpaqueIdentity,
	val contentChecksum: PortableActivityDigest,
	val newestMemberStartTimeMs: Long,
	val newestMemberTieIdentity: String,
)

private class ActivityLocalOwnershipBudgetExceeded :
	RuntimeException(null, null, false, false)

private fun unavailable(
	reason: SourceAwareHistoryPageUnavailableReason,
): ImportedHistoryEligiblePage.Unavailable = ImportedHistoryEligiblePage.Unavailable(reason)

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
private const val MAX_ACTIVITY_ACQUISITION_PLANS = 512
private const val MAX_ACTIVITY_DESIRED_PLANS = 512
private const val MAX_ACTIVITY_PROVIDER_REGISTRATIONS = 512
private const val MAX_ACTIVITY_AUTHORIZATIONS = 4_096
private const val MAX_ACTIVITY_TERMINAL_FAILURES = 512
private const val RANGE_SOURCE_CANDIDATE_LIMIT = 100

/** A qualified immutable window's actual fragments, before optional date selection. */
internal data class ActivityMeasuredWindow(
	val revision: ActivityCapturedWindowRevisionEntity,
	val fragments: Map<Int, ActivityHistoryFragment>,
)
internal data class ActivityMeasuredWindowsRead(
	val windows: Map<ActivityRevisionKey, ActivityMeasuredWindow> = emptyMap(),
	val causes: Set<ActivityHistoryCause> = emptySet(),
	val storageFailed: Boolean = false,
)
