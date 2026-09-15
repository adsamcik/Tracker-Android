@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiAuthorityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalObservationOwner
import com.adsamcik.tracker.shared.base.database.dao.WifiLocalRunOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunZoneEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WifiSelectedDeletionReceiptEntity
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductReadLimitExceeded
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentScan
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete imported Wi-Fi product authentication over one caller-owned Room snapshot. */
@Singleton
@Suppress(
	"ComplexCondition",
	"CyclomaticComplexMethod",
	"LargeClass",
	"LongMethod",
	"NestedBlockDepth",
	"ReturnCount",
)
internal class RoomImportedWifiProductEvaluator internal constructor(
	private val database: AppDatabase,
	private val checkpoint: suspend (ImportedWifiProductReadCheckpoint) -> Unit,
	private val limits: ImportedWifiProductLimits,
) : ImportedWifiProductEvaluator, ImportedWifiProductRecentPageEvaluator {
	@Inject
	constructor(database: AppDatabase) : this(
		database,
		{ currentCoroutineContext().ensureActive() },
		ImportedWifiProductLimits(),
	)

	override suspend fun selectIdentityInTransaction(
		selection: WifiImportedHistorySelectionKey,
	): ImportedWifiProductEvaluation? {
		val state = ImportedWifiProductReadState(limits)
		state.budget.recordQuery()
		val candidate = database.importedWifiDao().latestHistoryCandidate(selection.value) ?: return null
		state.budget.recordRows(listOf(candidate))
		if (!isValidCandidatePage(listOf(candidate), null, null)) {
			throw IllegalArgumentException("Invalid imported Wi-Fi candidate")
		}
		return evaluateSafely(listOf(candidate), state).single()
	}

	override suspend fun selectRecentInTransaction(limit: Int): List<ImportedWifiProductEvaluation> {
		require(limit in 1..ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES)
		return selectRecentPageInTransaction(
			ImportedWifiProductRecentRequest(limit),
		).evaluations
	}

	override suspend fun selectRecentPageInTransaction(
		request: ImportedWifiProductRecentRequest,
	): ImportedWifiProductRecentPage =
		openRecentScanInTransaction().selectPageInTransaction(request)

	override suspend fun openRecentScanInTransaction(): ImportedWifiProductRecentScan =
		RoomImportedWifiProductRecentScan(ImportedWifiProductReadState(limits))

	private suspend fun selectRecentPageInTransaction(
		request: ImportedWifiProductRecentRequest,
		state: ImportedWifiProductReadState,
	): ImportedWifiProductRecentPage {
		val queryLimit = Math.addExact(request.limit, 1)
		state.budget.recordQuery()
		val candidates = database.importedWifiDao().recentHistoryCandidatePage(
			queryLimit,
			request.beforeNewestMemberStartTimeMs,
			request.beforeNewestMemberIdentity?.value,
		)
		state.budget.recordRows(candidates)
		if (!isValidCandidatePage(
				candidates,
				request.beforeNewestMemberStartTimeMs,
				request.beforeNewestMemberIdentity?.value,
			)
		) throw IllegalArgumentException("Invalid imported Wi-Fi recent candidate page")
		return ImportedWifiProductRecentPage(
			evaluations = evaluateSafely(candidates.take(request.limit), state),
			hasMore = candidates.size > request.limit,
		)
	}

	override suspend fun selectRangeInTransaction(
		request: ImportedWifiProductRangeRequest,
	): ImportedWifiProductRangePage {
		val state = ImportedWifiProductReadState(limits)
		val queryLimit = Math.addExact(request.limit, 1)
		state.budget.recordQuery()
		val candidates = database.importedWifiDao().historyCandidateRangePage(
			fromInclusiveMs = request.fromInclusiveMs,
			toExclusiveMs = request.toExclusiveMs,
			limit = queryLimit,
			beforeStartTimeMs = request.beforeStartTimeMs,
			beforeIdentity = request.beforeIdentity?.value,
		)
		state.budget.recordRows(candidates)
		if (!isValidCandidatePage(
				candidates,
				request.beforeStartTimeMs,
				request.beforeIdentity?.value,
			)
		) throw IllegalArgumentException("Invalid imported Wi-Fi range candidate page")
		val selected = candidates.take(request.limit)
		return ImportedWifiProductRangePage(
			evaluations = evaluateSafely(selected, state),
			hasMore = candidates.size > request.limit,
		)
	}

	private suspend fun evaluateSafely(
		candidates: List<ImportedWifiHistoryCandidate>,
		state: ImportedWifiProductReadState,
	): List<ImportedWifiProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		val authenticatedSelections = linkedSetOf<String>()
		return try {
			val snapshot = state.localOwners ?: loadLocalOwnerSnapshot(state.budget).also {
				state.localOwners = it
			}
			evaluate(candidates, snapshot, authenticatedSelections, state.budget)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ImportedWifiProductReadLimitExceeded) {
			candidates.unverifiable(
				ImportedWifiProductFailure.DEPENDENCY_OVERFLOW,
				state.localOwners,
			)
		} catch (failure: ImportedWifiProductAbort) {
			candidates.unverifiable(failure.reason, state.localOwners)
		} catch (storage: SQLiteException) {
			throw storage
		} catch (_: IllegalArgumentException) {
			candidates.unverifiable(
				ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				state.localOwners,
			)
		} catch (_: ArithmeticException) {
			candidates.unverifiable(
				ImportedWifiProductFailure.VALUE_OVERFLOW,
				state.localOwners,
				authenticatedSelections,
			)
		} catch (_: DateTimeException) {
			candidates.unverifiable(
				ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				state.localOwners,
			)
		} catch (_: RuntimeException) {
			candidates.unverifiable(
				ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				state.localOwners,
			)
		}
	}

	@Suppress("LongMethod")
	private suspend fun evaluate(
		candidates: List<ImportedWifiHistoryCandidate>,
		localOwners: LocalWifiOwnerSnapshot,
		authenticatedSelections: MutableSet<String>,
		budget: ImportedWifiProductReadBudget,
	): List<ImportedWifiProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		checkpoint(ImportedWifiProductReadCheckpoint.CANDIDATES_SELECTED)
		if (!isValidCandidatePage(candidates, null, null) ||
			candidates.size > limits.maximumCandidates
		) abort(ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
		budget.recordQuery()
		val state = database.sourceEvidenceStateDao().get()
			?: return candidates.unverifiable(
				ImportedWifiProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
				localOwners,
			)
		budget.recordRows(listOf(state))
		if (!state.hasValidImportedWifiProductShape()) storedCorrupt()

		val identities = candidates.map(ImportedWifiHistoryCandidate::identity)
		val batch = loadBatch(identities, budget)
		if (!batch.belongsOnlyTo(identities)) storedCorrupt()
		checkpoint(ImportedWifiProductReadCheckpoint.LINEAGES_LOADED)

		val headersByIdentity = batch.headers.groupBy(ImportedWifiEntryRevisionEntity::identity)
		val receiptsByIdentity = batch.receipts.groupBy(ImportedWifiReceiptEntity::entryIdentity)
		val runsByIdentity = batch.runs.groupBy(ImportedWifiRunEntity::entryIdentity)
		val zonesByIdentity = batch.zones.groupBy(ImportedWifiRunZoneEntity::entryIdentity)
		val observationsByIdentity = batch.observations.groupBy(ImportedWifiObservationEntity::entryIdentity)
		val current = linkedMapOf<String, AuthenticatedImportedWifiRevision>()
		val stale = linkedSetOf<String>()
		for (candidate in candidates) {
			currentCoroutineContext().ensureActive()
			val headers = headersByIdentity[candidate.identity].orEmpty()
			val receipts = receiptsByIdentity[candidate.identity].orEmpty()
			val runs = runsByIdentity[candidate.identity].orEmpty()
			val epochs = buildSet {
				addAll(headers.map(ImportedWifiEntryRevisionEntity::collectedDataEpoch))
				addAll(receipts.map(ImportedWifiReceiptEntity::collectedDataEpoch))
				addAll(runs.map(ImportedWifiRunEntity::collectedDataEpoch))
			}
			if (epochs.size != 1) storedCorrupt()
			val lineage = try {
				ImportedWifiLineageAuthenticator.authenticate(
					identity = candidate.identity,
					expectedCollectedDataEpoch = epochs.single(),
					headers = headers,
					receipts = receipts,
					runs = runs,
					zones = zonesByIdentity[candidate.identity].orEmpty(),
					observations = observationsByIdentity[candidate.identity].orEmpty(),
				)
			} catch (failure: ImportedWifiLineageFailure) {
				abort(failure.reason.toProductFailure())
			}
			val latest = lineage.revisions.lastOrNull() ?: storedCorrupt()
			if (!candidate.exactlyMatches(latest)) storedCorrupt()
			if (epochs.single() != state.collectedDataEpoch) {
				stale += candidate.identity
			} else {
				current[candidate.identity] = latest
				authenticatedSelections += candidate.identity
			}
		}
		checkpoint(ImportedWifiProductReadCheckpoint.LINEAGES_AUTHENTICATED)
		if (current.isEmpty()) {
			return candidates.map {
				it.unverifiable(ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH, localOwners)
			}
		}

		val ownership = WifiPortableOpaqueOwnershipSet.from(current.values.map { it.entry })
			?: originConflict()
		authenticateImportedOwnership(ownership, state, budget)
		val localCollisions = authenticateLocalOwnership(ownership, localOwners)
		checkpoint(ImportedWifiProductReadCheckpoint.OWNERSHIP_AUTHENTICATED)

		budget.recordQuery()
		val entryDeletions = database.importedWifiDao().entryDeletionsForHistory(
			current.keys.toList(),
			checkedLimit(current.size),
		)
		budget.recordRows(entryDeletions)
		budget.recordQuery()
		val generations = database.importedWifiDao().deletionGenerationsForHistory(
			current.keys.toList(),
			checkedLimit(limits.maximumAuthorityRows),
		)
		budget.recordRows(generations)
		countRows(entryDeletions.size, generations.size)
		if (entryDeletions.distinctBy(ImportedWifiEntryDeletionEntity::entryIdentity).size !=
			entryDeletions.size ||
			generations.distinctBy(ImportedWifiDeletionGenerationEntity::runIdentity).size !=
			generations.size ||
			entryDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			generations.any { it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L }
		) storedCorrupt()
		val entryDeletionByIdentity = entryDeletions.associateBy(
			ImportedWifiEntryDeletionEntity::entryIdentity,
		)
		val generationByRun = generations.associateBy(ImportedWifiDeletionGenerationEntity::runIdentity)
		val exactFences = loadExactWifiFences(ownership, state, budget)

		return candidates.map { candidate ->
			if (candidate.identity in stale) {
				candidate.unverifiable(
					ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH,
					localOwners,
				)
			} else {
				val latest = current.getValue(candidate.identity)
				val entry = latest.entry
				val entryDeletion = entryDeletionByIdentity[candidate.identity]
				if (entryDeletion != null &&
					entryDeletion.deletedImportRevision != latest.header.importRevision
				) storedCorrupt()
				val deletedRuns = entry.runs.filterTo(linkedSetOf()) { run ->
					val marker = generationByRun[run.identity.value]
					if (marker != null && (
							marker.entryIdentity != entry.identity.value ||
								marker.deletionScopeDigest != run.deletionScopeDigest.value
							)
					) storedCorrupt()
					marker != null || run.deletionScopeDigest.value in exactFences
				}.mapTo(linkedSetOf(), PortableCapturedWifiRunV1::identity)
				if (entryDeletion != null && deletedRuns.size != entry.runs.size) storedCorrupt()
				val retention = retainedObservationIdentities(entry, state.retainedFromMs)
				ImportedWifiProductEvaluation.Readable(
					candidate = candidate.toProductCandidate(),
					entry = entry,
					entryDeleted = entryDeletion != null,
					deletedRunIdentities = deletedRuns,
					retainedObservationIdentities = retention.identities,
					retentionLimited = retention.limited,
					collidingLocalLogicalTrackingId = localCollisions[entry.identity.value],
				)
			}
		}
	}

	private suspend fun loadBatch(
		identities: List<String>,
		budget: ImportedWifiProductReadBudget,
	): ImportedWifiProductBatch {
		val dao = database.importedWifiDao()
		var remaining = minOf(limits.maximumAuthorityRows, budget.remainingRows())
		budget.recordQuery()
		val headers = dao.entryRevisionsForHistory(identities, checkedLimit(remaining))
		budget.recordRows(headers)
		remaining = consumeRows(remaining, headers.size)
		budget.recordQuery()
		val receipts = dao.receiptsForHistory(identities, checkedLimit(remaining))
		budget.recordRows(receipts)
		remaining = consumeRows(remaining, receipts.size)
		budget.recordQuery()
		val runs = dao.runsForHistory(identities, checkedLimit(remaining))
		budget.recordRows(runs)
		remaining = consumeRows(remaining, runs.size)
		budget.recordQuery()
		val zones = dao.runZonesForHistory(identities, checkedLimit(remaining))
		budget.recordRows(zones)
		remaining = consumeRows(remaining, zones.size)
		budget.recordQuery()
		val observations = dao.observationsForHistory(identities, checkedLimit(remaining))
		budget.recordRows(observations)
		consumeRows(remaining, observations.size)
		return ImportedWifiProductBatch(headers, receipts, runs, zones, observations)
	}

	private suspend fun authenticateImportedOwnership(
		ownership: WifiPortableOpaqueOwnershipSet,
		state: SourceEvidenceState,
		budget: ImportedWifiProductReadBudget,
	) {
		val dao = database.importedWifiDao()
		var authenticatedRows = 0L
		for (values in ownership.allValues.chunked(SQLITE_BIND_BATCH)) {
			currentCoroutineContext().ensureActive()
			val limit = minOf(
				checkedLimit(minOf(limits.maximumAuthorityRows, budget.remainingRows())),
				Math.addExact(Math.multiplyExact(values.size, MAX_AUTHORITY_OWNER_ROWS_PER_VALUE), 1),
			)
			budget.recordQuery()
			val owners = dao.authorityOwners(values, limit)
			budget.recordRows(owners)
			authenticatedRows = addRowCount(authenticatedRows, owners.size.toLong())
			if (owners.size >= limit) dependencyOverflow()
			if (owners.any { owner ->
					owner.collectedDataEpoch != state.collectedDataEpoch ||
						!owner.matchesExpectedOwnership(ownership)
				}
			) originConflict()
		}
	}

	private suspend fun authenticateLocalOwnership(
		ownership: WifiPortableOpaqueOwnershipSet,
		localOwners: LocalWifiOwnerSnapshot,
	): Map<String, String> {
		if (ownership.allValues.isEmpty()) return emptyMap()
		val collisions = linkedMapOf<String, LocalWifiCollision>()
		localOwners.entries.forEach { logicalId ->
			recordLocalEntry(logicalId, ownership, collisions)
		}
		localOwners.runs.forEach { owner ->
			recordLocalRun(owner, ownership, collisions)
		}
		localOwners.observations.forEach { owner ->
			recordLocalObservation(owner, ownership, collisions)
		}
		localOwners.deletedRuns.forEach { owner ->
			recordLocalRun(owner, ownership, collisions)
		}
		return collisions.mapValues { (entryIdentity, collision) ->
			val logicalId = collision.logicalTrackingIds.singleOrNull() ?: originConflict()
			if (collision.values != ownership.valuesByEntry[entryIdentity]) originConflict()
			logicalId
		}
	}

	private suspend fun loadLocalOwnerSnapshot(
		budget: ImportedWifiProductReadBudget,
	): LocalWifiOwnerSnapshot {
		val dao = database.importedWifiDao()
		budget.recordQuery()
		val entryCount = dao.localEntryOwnerCount()
		budget.recordScalar(entryCount)
		budget.recordQuery()
		val runCount = dao.localRunOwnerCount()
		budget.recordScalar(runCount)
		budget.recordQuery()
		val observationCount = dao.localObservationOwnerCount()
		budget.recordScalar(observationCount)
		budget.recordQuery()
		val deletionCount = dao.localDeletionOwnerCount()
		budget.recordScalar(deletionCount)
		val expectedCount = listOf(
			entryCount,
			runCount,
			observationCount,
			deletionCount,
		)
		if (expectedCount.any { it < 0L }) storedCorrupt()
		expectedCount.fold(0L) { total, count -> addRowCount(total, count) }
		val entries = mutableListOf<String>()
		val runs = mutableListOf<WifiLocalRunOwner>()
		val observations = mutableListOf<WifiLocalObservationOwner>()
		val deletedRuns = mutableListOf<WifiLocalRunOwner>()
		pageStrings(dao::localEntryOwnerPage, expectedCount[0], budget) { entries += it }
		pageRuns(dao::localRunOwnerPage, expectedCount[1], budget) { runs += it }
		pageObservations(dao::localObservationOwnerPage, expectedCount[2], budget) {
			observations += it
		}
		pageRuns(dao::localDeletionOwnerPage, expectedCount[3], budget) { deletedRuns += it }
		return LocalWifiOwnerSnapshot(entries, runs, observations, deletedRuns)
	}

	private fun recordLocalEntry(
		logicalId: String,
		ownership: WifiPortableOpaqueOwnershipSet,
		collisions: MutableMap<String, LocalWifiCollision>,
	) {
		val entry = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.LOGICAL_ENTRY,
			logicalId,
		).value
		recordLocalValue(
			entry,
			WifiPortableOpaqueOwner(PortableWifiIdentityKind.LOGICAL_ENTRY, entry),
			logicalId,
			ownership,
			collisions,
		)
	}

	private fun recordLocalRun(
		local: WifiLocalRunOwner,
		ownership: WifiPortableOpaqueOwnershipSet,
		collisions: MutableMap<String, LocalWifiCollision>,
	) {
		val entry = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.LOGICAL_ENTRY,
			local.logicalTrackingId,
		).value
		val run = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.PHYSICAL_RUN,
			local.serviceRunId,
		).value
		val scope = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			SessionManifestPurposeCode.SESSION_CAPTURE,
			local.logicalTrackingId,
			local.serviceRunId,
		)
		recordLocalValue(
			entry,
			WifiPortableOpaqueOwner(PortableWifiIdentityKind.LOGICAL_ENTRY, entry),
			local.logicalTrackingId,
			ownership,
			collisions,
		)
		val runOwner = WifiPortableOpaqueOwner(
			PortableWifiIdentityKind.PHYSICAL_RUN,
			entry,
			run,
			scope,
		)
		recordLocalValue(run, runOwner, local.logicalTrackingId, ownership, collisions)
		recordLocalValue(scope, runOwner, local.logicalTrackingId, ownership, collisions)
	}

	private fun recordLocalObservation(
		local: WifiLocalObservationOwner,
		ownership: WifiPortableOpaqueOwnershipSet,
		collisions: MutableMap<String, LocalWifiCollision>,
	) {
		val entry = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.LOGICAL_ENTRY,
			local.logicalTrackingId,
		).value
		val run = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.PHYSICAL_RUN,
			local.serviceRunId,
		).value
		val observation = PortableWifiOpaqueIdentity.derive(
			PortableWifiIdentityKind.OBSERVATION,
			local.logicalFactId,
		).value
		recordLocalValue(
			observation,
			WifiPortableOpaqueOwner(PortableWifiIdentityKind.OBSERVATION, entry, run),
			local.logicalTrackingId,
			ownership,
			collisions,
		)
	}

	private fun recordLocalValue(
		value: String,
		actualOwner: WifiPortableOpaqueOwner,
		logicalId: String,
		ownership: WifiPortableOpaqueOwnershipSet,
		collisions: MutableMap<String, LocalWifiCollision>,
	) {
		val expectedIdentityOwner = ownership.identityOwners[value]
		val expectedScopeOwner = ownership.scopeOwners[value]
		if (expectedIdentityOwner == null && expectedScopeOwner == null) return
		val expected = expectedIdentityOwner ?: expectedScopeOwner ?: originConflict()
		if (expected != actualOwner) originConflict()
		collisions.getOrPut(expected.entryIdentity, ::LocalWifiCollision).apply {
			logicalTrackingIds += logicalId
			values += value
		}
	}

	private suspend fun pageStrings(
		loader: suspend (String?, Int) -> List<String>,
		expectedCount: Long,
		budget: ImportedWifiProductReadBudget,
		visitor: (String) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			budget.recordQuery()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			budget.recordRows(page)
			if (page.isEmpty()) break
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left >= right } ||
				after?.let { page.first() <= it } == true
			) storedCorrupt()
			page.forEach(visitor)
			loaded = addRowCount(loaded, page.size.toLong())
			after = page.last()
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
		}
		if (loaded != expectedCount) storedCorrupt()
	}

	private suspend fun pageRuns(
		loader: suspend (String?, Int) -> List<WifiLocalRunOwner>,
		expectedCount: Long,
		budget: ImportedWifiProductReadBudget,
		visitor: (WifiLocalRunOwner) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			budget.recordQuery()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			budget.recordRows(page)
			if (page.isEmpty()) break
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left.serviceRunId >= right.serviceRunId } ||
				after?.let { page.first().serviceRunId <= it } == true
			) storedCorrupt()
			page.forEach(visitor)
			loaded = addRowCount(loaded, page.size.toLong())
			after = page.last().serviceRunId
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
		}
		if (loaded != expectedCount) storedCorrupt()
	}

	private suspend fun pageObservations(
		loader: suspend (String?, Int) -> List<WifiLocalObservationOwner>,
		expectedCount: Long,
		budget: ImportedWifiProductReadBudget,
		visitor: (WifiLocalObservationOwner) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			budget.recordQuery()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
			budget.recordRows(page)
			if (page.isEmpty()) break
			if (page.size > ImportedWifiDao.OWNER_PAGE_SIZE ||
				page.zipWithNext().any { (left, right) -> left.logicalFactId >= right.logicalFactId } ||
				after?.let { page.first().logicalFactId <= it } == true
			) storedCorrupt()
			page.forEach(visitor)
			loaded = addRowCount(loaded, page.size.toLong())
			after = page.last().logicalFactId
			if (page.size < ImportedWifiDao.OWNER_PAGE_SIZE) break
		}
		if (loaded != expectedCount) storedCorrupt()
	}

	private suspend fun loadExactWifiFences(
		ownership: WifiPortableOpaqueOwnershipSet,
		state: SourceEvidenceState,
		budget: ImportedWifiProductReadBudget,
	): Set<String> {
		val fences = mutableListOf<SourceDeletionFenceEntity>()
		for (scopes in ownership.scopeOwners.keys.chunked(SQLITE_BIND_BATCH)) {
			budget.recordQuery()
			val page = database.importedWifiDao().deletionFenceIdentityOwners(
				scopes,
				checkedLimit(scopes.size),
			)
			budget.recordRows(page)
			if (page.size > scopes.size || page.any {
					it.collectedDataEpoch != state.collectedDataEpoch ||
						!it.isExactWifiDeletionFence()
				}
			) originConflict()
			fences += page
		}
		return fences.mapTo(linkedSetOf(), SourceDeletionFenceEntity::scopeIdentityDigest)
	}

	private fun retainedObservationIdentities(
		entry: PortableCapturedWifiEntryV1,
		retainedFromMs: Long?,
	): RetainedWifiObservationSet {
		val observations = entry.runs.flatMap(PortableCapturedWifiRunV1::observations)
		if (retainedFromMs == null) {
			return RetainedWifiObservationSet(
				observations.mapTo(linkedSetOf(), PortableCapturedWifiObservationV1::identity),
				false,
			)
		}
		val timeRetained = observations.filter { it.coverageStartTimeMs >= retainedFromMs }
		val retainedIds = timeRetained.mapTo(hashSetOf(), PortableCapturedWifiObservationV1::identity)
		val closure = timeRetained.filter { observation ->
			observation.aggregateOwnerIdentity?.let { it in retainedIds } != false
		}.mapTo(linkedSetOf(), PortableCapturedWifiObservationV1::identity)
		return RetainedWifiObservationSet(closure, closure.size != observations.size)
	}

	private fun countRows(vararg counts: Int) {
		counts.fold(0L) { total, count -> addRowCount(total, count.toLong()) }
	}

	private fun consumeRows(remaining: Int, loaded: Int): Int {
		if (loaded > remaining) dependencyOverflow()
		return remaining - loaded
	}

	private fun addRowCount(current: Long, added: Long): Long = try {
		if (current < 0L || added < 0L) dependencyOverflow()
		Math.addExact(current, added).also {
			if (it > limits.maximumAuthorityRows) dependencyOverflow()
		}
	} catch (_: ArithmeticException) {
		dependencyOverflow()
	}

	private fun checkedLimit(maximumRows: Int): Int = try {
		Math.addExact(maximumRows, 1)
	} catch (_: ArithmeticException) {
		dependencyOverflow()
	}

	private fun isValidCandidatePage(
		candidates: List<ImportedWifiHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): Boolean {
		if ((beforeStartTimeMs == null) != (beforeIdentity == null) ||
			candidates.map(ImportedWifiHistoryCandidate::identity).distinct().size != candidates.size ||
			candidates.any { candidate ->
				runCatching {
					PortableWifiOpaqueIdentity(candidate.identity)
					PortableWifiDigest(candidate.contentChecksum)
					PortableWifiOpaqueIdentity(candidate.newestMemberIdentity)
					candidate.importRevision > 0L && candidate.startTimeMs >= 0L &&
						candidate.endTimeMs >= candidate.startTimeMs && candidate.receivedAtMs >= 0L &&
						candidate.newestMemberStartTimeMs in candidate.startTimeMs..candidate.endTimeMs
				}.getOrDefault(false).not()
			}
		) return false
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null) {
				add(beforeStartTimeMs to beforeIdentity)
			}
			addAll(candidates.map { it.newestMemberStartTimeMs to it.newestMemberIdentity })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first || left.first == right.first && left.second > right.second
		}
	}

	private fun dependencyOverflow(): Nothing = abort(ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
	private fun storedCorrupt(): Nothing = abort(ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	private fun originConflict(): Nothing = abort(ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT)
	private fun abort(reason: ImportedWifiProductFailure): Nothing = throw ImportedWifiProductAbort(reason)

	private inner class RoomImportedWifiProductRecentScan(
		private val state: ImportedWifiProductReadState,
	) : ImportedWifiProductRecentScan {
		override suspend fun selectPageInTransaction(
			request: ImportedWifiProductRecentRequest,
		): ImportedWifiProductRecentPage =
			selectRecentPageInTransaction(request, state)
	}

	private companion object {
		const val SQLITE_BIND_BATCH = 256
		const val MAX_AUTHORITY_OWNER_ROWS_PER_VALUE = 16
	}
}

internal enum class ImportedWifiProductReadCheckpoint {
	CANDIDATES_SELECTED,
	LINEAGES_LOADED,
	LINEAGES_AUTHENTICATED,
	OWNERSHIP_AUTHENTICATED,
}

internal data class ImportedWifiProductLimits(
	val maximumCandidates: Int = ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES,
	val maximumAuthorityRows: Int = ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS,
	val maximumAuthorityBytes: Long = 64L * 1024L * 1024L,
	val maximumQueries: Int = 16_384,
) {
	init {
		require(maximumCandidates in 1..ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES)
		require(maximumAuthorityRows in 1..ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS)
		require(maximumAuthorityBytes > 0L)
		require(maximumQueries > 0)
	}
}

private data class ImportedWifiProductReadState(
	val budget: ImportedWifiProductReadBudget,
	var localOwners: LocalWifiOwnerSnapshot? = null,
) {
	constructor(limits: ImportedWifiProductLimits) : this(
		ImportedWifiProductReadBudget(limits),
	)
}

private class ImportedWifiProductReadBudget(
	private val limits: ImportedWifiProductLimits,
) {
	private var rows = 0L
	private var bytes = 0L
	private var queries = 0

	fun recordQuery() {
		queries = try {
			Math.addExact(queries, 1)
		} catch (_: ArithmeticException) {
			throw ImportedWifiProductReadLimitExceeded()
		}
		if (queries > limits.maximumQueries) throw ImportedWifiProductReadLimitExceeded()
	}

	fun recordScalar(value: Any?) = recordRows(listOf(value))

	fun recordRows(values: Collection<*>) {
		try {
			rows = Math.addExact(rows, values.size.toLong())
			bytes = values.fold(bytes) { total, value ->
				Math.addExact(total, value.estimatedReadBytes())
			}
		} catch (_: ArithmeticException) {
			throw ImportedWifiProductReadLimitExceeded()
		}
		if (rows > limits.maximumAuthorityRows || bytes > limits.maximumAuthorityBytes) {
			throw ImportedWifiProductReadLimitExceeded()
		}
	}

	fun remainingRows(): Int =
		(limits.maximumAuthorityRows.toLong() - rows).coerceAtLeast(0L).toInt()
}

private fun Any?.estimatedReadBytes(): Long {
	if (this == null) return 1L
	val textBytes = Math.multiplyExact(toString().length.toLong(), 2L)
	return Math.addExact(32L, textBytes)
}

private data class ImportedWifiProductBatch(
	val headers: List<ImportedWifiEntryRevisionEntity>,
	val receipts: List<ImportedWifiReceiptEntity>,
	val runs: List<ImportedWifiRunEntity>,
	val zones: List<ImportedWifiRunZoneEntity>,
	val observations: List<ImportedWifiObservationEntity>,
) {
	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } &&
			receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } &&
			zones.all { it.entryIdentity in expected } &&
			observations.all { it.entryIdentity in expected }
	}
}

private data class RetainedWifiObservationSet(
	val identities: Set<PortableWifiOpaqueIdentity>,
	val limited: Boolean,
)

private data class LocalWifiCollision(
	val logicalTrackingIds: MutableSet<String> = linkedSetOf(),
	val values: MutableSet<String> = linkedSetOf(),
)

private data class LocalWifiOwnerSnapshot(
	val entries: List<String>,
	val runs: List<WifiLocalRunOwner>,
	val observations: List<WifiLocalObservationOwner>,
	val deletedRuns: List<WifiLocalRunOwner>,
) {
	init {
		require(entries.map { logicalId ->
			PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}.distinct().size == entries.size)
	}

	fun topLevelCollision(identity: String): String? {
		val matches = entries.filter { logicalId ->
			PortableWifiOpaqueIdentity.derive(
				PortableWifiIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value == identity
		}
		return matches.singleOrNull()
	}
}

private class ImportedWifiProductAbort(
	val reason: ImportedWifiProductFailure,
) : RuntimeException(null, null, false, false)

private fun SourceEvidenceState.hasValidImportedWifiProductShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun ImportedWifiHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedWifiRevision,
): Boolean {
	val newest = latest.entry.runs.maxWith(
		compareBy<PortableCapturedWifiRunV1>(
			{ it.startTimeMs },
			{ it.identity.value },
		),
	)
	return identity == latest.header.identity && importRevision == latest.header.importRevision &&
		contentChecksum == latest.header.contentChecksum && startTimeMs == latest.header.startTimeMs &&
		endTimeMs == latest.header.endTimeMs && receivedAtMs == latest.header.receivedAtMs &&
		newestMemberStartTimeMs == newest.startTimeMs && newestMemberIdentity == newest.identity.value
}

private fun ImportedWifiHistoryCandidate.toProductCandidate() = ImportedWifiProductCandidate(
	identity = PortableWifiOpaqueIdentity(identity),
	importRevision = importRevision,
	contentChecksum = PortableWifiDigest(contentChecksum),
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	receivedAtMs = receivedAtMs,
	newestMemberStartTimeMs = newestMemberStartTimeMs,
	newestMemberIdentity = PortableWifiOpaqueIdentity(newestMemberIdentity),
)

private fun ImportedWifiHistoryCandidate.unverifiable(
	reason: ImportedWifiProductFailure,
	localOwners: LocalWifiOwnerSnapshot? = null,
	authenticatedSelections: Set<String> = emptySet(),
) = ImportedWifiProductEvaluation.Unverifiable(
	toProductCandidate(),
	reason,
	localOwners?.topLevelCollision(identity),
	authenticatedSelection = candidateSelectionIfAuthenticated(reason, authenticatedSelections),
)

private fun List<ImportedWifiHistoryCandidate>.unverifiable(
	reason: ImportedWifiProductFailure,
	localOwners: LocalWifiOwnerSnapshot? = null,
	authenticatedSelections: Set<String> = emptySet(),
) = map { it.unverifiable(reason, localOwners, authenticatedSelections) }

private fun ImportedWifiHistoryCandidate.candidateSelectionIfAuthenticated(
	reason: ImportedWifiProductFailure,
	authenticatedSelections: Set<String>,
) = toProductCandidate().selection.takeIf {
	reason == ImportedWifiProductFailure.VALUE_OVERFLOW && identity in authenticatedSelections
}

private fun SourceDeletionFenceEntity.isExactWifiDeletionFence(): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
		purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN

private fun ImportedWifiAuthorityOwner.matchesExpectedOwnership(
	ownership: WifiPortableOpaqueOwnershipSet,
): Boolean = when (ownerKind) {
	ImportedWifiAuthorityOwner.ENTRY -> ownership.identityOwners[protectedIdentity]?.let { owner ->
		owner.kind == PortableWifiIdentityKind.LOGICAL_ENTRY &&
			owner.entryIdentity == entryIdentity
	} == true
	ImportedWifiAuthorityOwner.RUN -> ownership.identityOwners[protectedIdentity]?.let { owner ->
		owner.kind == PortableWifiIdentityKind.PHYSICAL_RUN &&
			owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
			owner.deletionScopeDigest == deletionScopeDigest
	} == true
	ImportedWifiAuthorityOwner.OBSERVATION ->
		ownership.identityOwners[protectedIdentity]?.let { owner ->
			owner.kind == PortableWifiIdentityKind.OBSERVATION &&
				owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity
		} == true
	ImportedWifiAuthorityOwner.DELETION_SCOPE ->
		ownership.scopeOwners[protectedIdentity]?.let { owner ->
			owner.kind == PortableWifiIdentityKind.PHYSICAL_RUN &&
				owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
				owner.deletionScopeDigest == deletionScopeDigest
		} == true
	ImportedWifiAuthorityOwner.ENTRY_DELETION ->
		ownership.identityOwners[protectedIdentity]?.kind == PortableWifiIdentityKind.LOGICAL_ENTRY
	ImportedWifiAuthorityOwner.RUN_DELETION ->
		ownership.identityOwners[protectedIdentity]?.let { owner ->
			owner.kind == PortableWifiIdentityKind.PHYSICAL_RUN &&
				owner.entryIdentity == entryIdentity &&
				owner.deletionScopeDigest == deletionScopeDigest
		} == true
	ImportedWifiAuthorityOwner.SOURCE_FENCE ->
		protectedIdentity in ownership.scopeOwners &&
			sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
			purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN
	ImportedWifiAuthorityOwner.SELECTED_PROTECTED ->
		receiptOrigin == WifiSelectedDeletionReceiptEntity.ORIGIN_LOCAL &&
			selectionIdentity == entryIdentity && entryIdentity != null && (
			ownership.identityOwners[protectedIdentity]?.let { owner ->
				owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
					(deletionScopeDigest == null || owner.deletionScopeDigest == deletionScopeDigest)
			} == true ||
				ownership.scopeOwners[protectedIdentity]?.let { owner ->
					owner.entryIdentity == entryIdentity && owner.runIdentity == runIdentity &&
						owner.deletionScopeDigest == deletionScopeDigest
				} == true
			)
	else -> false
}

private fun ImportedWifiLineageAuthenticator.Reason.toProductFailure(): ImportedWifiProductFailure =
	when (this) {
		ImportedWifiLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW,
		ImportedWifiLineageAuthenticator.Reason.RUN_OVERFLOW,
		ImportedWifiLineageAuthenticator.Reason.ZONE_OVERFLOW,
		ImportedWifiLineageAuthenticator.Reason.OBSERVATION_OVERFLOW,
		ImportedWifiLineageAuthenticator.Reason.REVISION_OVERFLOW,
		-> ImportedWifiProductFailure.DEPENDENCY_OVERFLOW
		ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
			ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
