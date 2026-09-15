@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.wifi

import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
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
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
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
) : ImportedWifiProductEvaluator {
	@Inject
	constructor(database: AppDatabase) : this(
		database,
		{ currentCoroutineContext().ensureActive() },
		ImportedWifiProductLimits(),
	)

	override suspend fun selectIdentityInTransaction(
		selection: WifiImportedHistorySelectionKey,
	): ImportedWifiProductEvaluation? {
		val candidate = database.importedWifiDao().latestHistoryCandidate(selection.value) ?: return null
		if (!isValidCandidatePage(listOf(candidate), null, null)) {
			throw IllegalArgumentException("Invalid imported Wi-Fi candidate")
		}
		return evaluateSafely(listOf(candidate)).single()
	}

	override suspend fun selectRecentInTransaction(limit: Int): List<ImportedWifiProductEvaluation> {
		require(limit in 1..ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val candidates = database.importedWifiDao().recentHistoryCandidatePage(limit, null, null)
		if (!isValidCandidatePage(candidates, null, null)) {
			throw IllegalArgumentException("Invalid imported Wi-Fi candidate page")
		}
		return evaluateSafely(candidates)
	}

	private suspend fun evaluateSafely(
		candidates: List<ImportedWifiHistoryCandidate>,
	): List<ImportedWifiProductEvaluation> = try {
		evaluate(candidates)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: ImportedWifiProductAbort) {
		candidates.unverifiable(failure.reason)
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		candidates.unverifiable(ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		candidates.unverifiable(ImportedWifiProductFailure.VALUE_OVERFLOW)
	} catch (_: DateTimeException) {
		candidates.unverifiable(ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: RuntimeException) {
		candidates.unverifiable(ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	}

	@Suppress("LongMethod")
	private suspend fun evaluate(
		candidates: List<ImportedWifiHistoryCandidate>,
	): List<ImportedWifiProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		checkpoint(ImportedWifiProductReadCheckpoint.CANDIDATES_SELECTED)
		if (!isValidCandidatePage(candidates, null, null) ||
			candidates.size > limits.maximumCandidates
		) abort(ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
		val state = database.sourceEvidenceStateDao().get()
			?: return candidates.unverifiable(ImportedWifiProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (!state.hasValidImportedWifiProductShape()) storedCorrupt()

		val identities = candidates.map(ImportedWifiHistoryCandidate::identity)
		val batch = loadBatch(identities)
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
			}
		}
		checkpoint(ImportedWifiProductReadCheckpoint.LINEAGES_AUTHENTICATED)
		if (current.isEmpty()) {
			return candidates.map {
				it.unverifiable(ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH)
			}
		}

		val ownership = WifiPortableOpaqueOwnershipSet.from(current.values.map { it.entry })
			?: originConflict()
		authenticateImportedOwnership(ownership, state)
		val localCollisions = authenticateLocalOwnership(ownership)
		checkpoint(ImportedWifiProductReadCheckpoint.OWNERSHIP_AUTHENTICATED)

		val entryDeletions = database.importedWifiDao().entryDeletionsForHistory(
			current.keys.toList(),
			checkedLimit(current.size),
		)
		val generations = database.importedWifiDao().deletionGenerationsForHistory(
			current.keys.toList(),
			checkedLimit(limits.maximumAuthorityRows),
		)
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
		val exactFences = loadExactWifiFences(ownership, state)

		return candidates.map { candidate ->
			if (candidate.identity in stale) {
				candidate.unverifiable(ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH)
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

	private suspend fun loadBatch(identities: List<String>): ImportedWifiProductBatch {
		val dao = database.importedWifiDao()
		var remaining = limits.maximumAuthorityRows
		val headers = dao.entryRevisionsForHistory(identities, checkedLimit(remaining))
		remaining = consumeRows(remaining, headers.size)
		val receipts = dao.receiptsForHistory(identities, checkedLimit(remaining))
		remaining = consumeRows(remaining, receipts.size)
		val runs = dao.runsForHistory(identities, checkedLimit(remaining))
		remaining = consumeRows(remaining, runs.size)
		val zones = dao.runZonesForHistory(identities, checkedLimit(remaining))
		remaining = consumeRows(remaining, zones.size)
		val observations = dao.observationsForHistory(identities, checkedLimit(remaining))
		consumeRows(remaining, observations.size)
		return ImportedWifiProductBatch(headers, receipts, runs, zones, observations)
	}

	private suspend fun authenticateImportedOwnership(
		ownership: WifiPortableOpaqueOwnershipSet,
		state: SourceEvidenceState,
	) {
		val dao = database.importedWifiDao()
		var authenticatedRows = 0L
		for (values in ownership.allValues.chunked(SQLITE_BIND_BATCH)) {
			currentCoroutineContext().ensureActive()
			val limit = checkedLimit(values.size)
			val entries = dao.existingEntryIdentities(values, limit)
			val runs = dao.existingRunIdentityOwners(values, limit)
			val observations = dao.existingObservationIdentityOwners(values, limit)
			val scopes = dao.existingRunScopeOwners(values, limit)
			val tombstones = dao.entryDeletions(values)
			val generationsByRun = dao.deletionGenerationsByRun(values)
			val generationsByScope = dao.deletionGenerationsByScope(values)
			val generationsByEntry = dao.deletionGenerationsByEntry(values, limit)
			val generations = (generationsByRun + generationsByScope + generationsByEntry)
				.distinctBy(ImportedWifiDeletionGenerationEntity::runIdentity)
			val fences = dao.deletionFenceIdentityOwners(values, limit)
			listOf(
				entries.size,
				runs.size,
				observations.size,
				scopes.size,
				tombstones.size,
				generations.size,
				fences.size,
			).forEach { count ->
				authenticatedRows = addRowCount(authenticatedRows, count.toLong())
			}
			if (listOf(
					entries.size,
					runs.size,
					observations.size,
					scopes.size,
					generationsByEntry.size,
					fences.size,
				)
					.any { it >= limit } ||
				tombstones.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				generations.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
				fences.any { it.collectedDataEpoch != state.collectedDataEpoch }
			) dependencyOverflow()
			if (entries.any { identity ->
					ownership.identityOwners[identity]?.kind != PortableWifiIdentityKind.LOGICAL_ENTRY
				} || runs.any { row ->
					val owner = ownership.identityOwners[row.identity]
					owner?.kind != PortableWifiIdentityKind.PHYSICAL_RUN ||
						owner.entryIdentity != row.entryIdentity ||
						owner.runIdentity != row.identity ||
						owner.deletionScopeDigest != row.deletionScopeDigest
				} || observations.any { row ->
					val owner = ownership.identityOwners[row.identity]
					owner?.kind != PortableWifiIdentityKind.OBSERVATION ||
						owner.entryIdentity != row.entryIdentity ||
						owner.runIdentity != row.runIdentity
				} || scopes.any { row ->
					val owner = ownership.scopeOwners[row.deletionScopeDigest]
					owner?.entryIdentity != row.entryIdentity || owner.runIdentity != row.runIdentity
				} || tombstones.any {
					ownership.identityOwners[it.entryIdentity]?.kind !=
						PortableWifiIdentityKind.LOGICAL_ENTRY
				} ||
				generations.any { marker ->
					val owner = ownership.identityOwners[marker.runIdentity]
					owner?.kind != PortableWifiIdentityKind.PHYSICAL_RUN ||
						owner.entryIdentity != marker.entryIdentity ||
						owner.deletionScopeDigest != marker.deletionScopeDigest
				} || fences.any { fence ->
					fence.scopeIdentityDigest !in ownership.scopeOwners &&
						fence.scopeIdentityDigest in ownership.identityOwners ||
						fence.scopeIdentityDigest in ownership.scopeOwners && !fence.isExactWifiDeletionFence()
				}
			) originConflict()
		}
	}

	private suspend fun authenticateLocalOwnership(
		ownership: WifiPortableOpaqueOwnershipSet,
	): Map<String, String> {
		if (ownership.allValues.isEmpty()) return emptyMap()
		val dao = database.importedWifiDao()
		val expectedCount = listOf(
			dao.localEntryOwnerCount(),
			dao.localRunOwnerCount(),
			dao.localObservationOwnerCount(),
			dao.localDeletionOwnerCount(),
		)
		if (expectedCount.any { it < 0L }) storedCorrupt()
		expectedCount.fold(0L) { total, count -> addRowCount(total, count) }
		val collisions = linkedMapOf<String, LocalWifiCollision>()
		pageStrings(dao::localEntryOwnerPage, expectedCount[0]) { logicalId ->
			recordLocalEntry(logicalId, ownership, collisions)
		}
		pageRuns(dao::localRunOwnerPage, expectedCount[1]) { owner ->
			recordLocalRun(owner, ownership, collisions)
		}
		pageObservations(dao::localObservationOwnerPage, expectedCount[2]) { owner ->
			recordLocalObservation(owner, ownership, collisions)
		}
		pageRuns(dao::localDeletionOwnerPage, expectedCount[3]) { owner ->
			recordLocalRun(owner, ownership, collisions)
		}
		return collisions.mapValues { (entryIdentity, collision) ->
			val logicalId = collision.logicalTrackingIds.singleOrNull() ?: originConflict()
			if (collision.values != ownership.valuesByEntry[entryIdentity]) originConflict()
			logicalId
		}
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
		visitor: (String) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
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
		visitor: (WifiLocalRunOwner) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
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
		visitor: (WifiLocalObservationOwner) -> Unit,
	) {
		var after: String? = null
		var loaded = 0L
		while (true) {
			currentCoroutineContext().ensureActive()
			val page = loader(after, ImportedWifiDao.OWNER_PAGE_SIZE)
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
	): Set<String> {
		val fences = mutableListOf<SourceDeletionFenceEntity>()
		for (scopes in ownership.scopeOwners.keys.chunked(SQLITE_BIND_BATCH)) {
			val page = database.importedWifiDao().deletionFenceIdentityOwners(
				scopes,
				checkedLimit(scopes.size),
			)
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
					candidate.importRevision > 0L && candidate.startTimeMs >= 0L &&
						candidate.endTimeMs >= candidate.startTimeMs && candidate.receivedAtMs >= 0L
				}.getOrDefault(false).not()
			}
		) return false
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null) {
				add(beforeStartTimeMs to beforeIdentity)
			}
			addAll(candidates.map { it.startTimeMs to it.identity })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first || left.first == right.first && left.second > right.second
		}
	}

	private fun dependencyOverflow(): Nothing = abort(ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
	private fun storedCorrupt(): Nothing = abort(ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	private fun originConflict(): Nothing = abort(ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT)
	private fun abort(reason: ImportedWifiProductFailure): Nothing = throw ImportedWifiProductAbort(reason)

	private companion object {
		const val SQLITE_BIND_BATCH = 256
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
) {
	init {
		require(maximumCandidates in 1..ImportedWifiDao.MAX_HISTORY_ENTRY_CANDIDATES)
		require(maximumAuthorityRows in 1..ImportedWifiDao.MAX_GLOBAL_AUTHORITY_ROWS)
	}
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

private class ImportedWifiProductAbort(
	val reason: ImportedWifiProductFailure,
) : RuntimeException(null, null, false, false)

private fun SourceEvidenceState.hasValidImportedWifiProductShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun ImportedWifiHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedWifiRevision,
): Boolean = identity == latest.header.identity && importRevision == latest.header.importRevision &&
	contentChecksum == latest.header.contentChecksum && startTimeMs == latest.header.startTimeMs &&
	endTimeMs == latest.header.endTimeMs && receivedAtMs == latest.header.receivedAtMs

private fun ImportedWifiHistoryCandidate.toProductCandidate() = ImportedWifiProductCandidate(
	identity = PortableWifiOpaqueIdentity(identity),
	importRevision = importRevision,
	contentChecksum = PortableWifiDigest(contentChecksum),
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	receivedAtMs = receivedAtMs,
)

private fun ImportedWifiHistoryCandidate.unverifiable(reason: ImportedWifiProductFailure) =
	ImportedWifiProductEvaluation.Unverifiable(toProductCandidate(), reason)

private fun List<ImportedWifiHistoryCandidate>.unverifiable(reason: ImportedWifiProductFailure) =
	map { it.unverifiable(reason) }

private fun SourceDeletionFenceEntity.isExactWifiDeletionFence(): Boolean =
	sourceKind == SourceDestinationOwnerEntity.SOURCE_WIFI &&
		purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN

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
