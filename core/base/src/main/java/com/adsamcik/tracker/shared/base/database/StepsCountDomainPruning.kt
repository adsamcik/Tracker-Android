package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_MAINTENANCE_OWNER_BATCH
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_WAL_PRUNE_BATCH
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.OwnerMaintenanceSession
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionProofCandidateRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun StepsCountDomainStore.removeSessionWalOwnersForPruneAuthenticated(
	safeOrdinal: Long,
	createdBeforeMs: Long,
	limit: Int,
	session: OwnerMaintenanceSession,
): StepsCountDomainMaintenanceResult {
	require(safeOrdinal >= 0L)
	require(createdBeforeMs >= 0L)
	if (limit !in 1..MAX_WAL_PRUNE_BATCH) {
		return StepsCountDomainMaintenanceResult.Overflow
	}
	requireActiveSnapshot(session)
	if (!session.schemaAvailable) {
		return StepsCountDomainMaintenanceResult.SchemaUnavailable
	}
	val sqlite = database.openHelper.writableDatabase
	currentCoroutineContext().ensureActive()
	sqlite.authenticateStepsWalMaintenanceSnapshot(session)
	val candidates = queryWalPruneCandidates(
		sqlite = sqlite,
		safeOrdinal = safeOrdinal,
		createdBeforeMs = createdBeforeMs,
		limit = limit,
		session = session,
	)
	val timelineCache = session.ownerlessStepsManifestTimelineCache
	var removedOwners = 0L
	var removedReceipts = 0L
	try {
		val ownerKeys = authenticateWalPruneOwnerKeys(candidates, session, timelineCache)
		timelineCache.clearForMutation()
		ownerKeys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
			currentCoroutineContext().ensureActive()
			when (
				val result = removeOwnersAuthenticated(
					session = session,
					keys = chunk,
					requireEveryOwner = true,
				)
			) {
				is StepsCountDomainMaintenanceResult.Applied -> {
					removedOwners += result.removedOwners
					removedReceipts += result.removedReceipts
				}
				StepsCountDomainMaintenanceResult.SchemaUnavailable,
				StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable,
				StepsCountDomainMaintenanceResult.Overflow,
				-> throw CountDomainStoredEvidenceException()
			}
		}
	} finally {
		timelineCache.invalidate()
	}
	return StepsCountDomainMaintenanceResult.Applied(removedOwners, removedReceipts)
}

internal suspend fun StepsCountDomainStore.pruneSessionWalForStorageAuthenticated(
	safeOrdinal: Long,
	createdBeforeMs: Long,
	limit: Int,
	session: OwnerMaintenanceSession,
	beforeWalDeletion: suspend (Int, List<SourceEventRetentionProofCandidateRow>) -> Unit,
): StepsCountDomainMaintenanceResult {
	require(safeOrdinal >= 0L)
	require(createdBeforeMs >= 0L)
	if (limit !in 1..MAX_WAL_PRUNE_BATCH) {
		return StepsCountDomainMaintenanceResult.Overflow
	}
	requireActiveSnapshot(session)
	if (!session.schemaAvailable) {
		return StepsCountDomainMaintenanceResult.SchemaUnavailable
	}
	val sqlite = database.openHelper.writableDatabase
	currentCoroutineContext().ensureActive()
	sqlite.authenticateStepsWalMaintenanceSnapshot(session)
	val candidates = queryWalPruneCandidates(
		sqlite = sqlite,
		safeOrdinal = safeOrdinal,
		createdBeforeMs = createdBeforeMs,
		limit = limit,
		session = session,
	).boundedOwnerlessRunPrefix()
	val timelineCache = session.ownerlessStepsManifestTimelineCache
	return try {
		val ownerKeys = authenticateWalPruneOwnerKeys(candidates, session, timelineCache)
		timelineCache.clearForMutation()
		val removed = removeWalPruneOwners(ownerKeys, session)
		val legacy = candidates.filter(StepsWalPruneCandidate::isLegacyV27)
		val current = candidates.filterNot(StepsWalPruneCandidate::isLegacyV27)
		// Migrated v27 rows carry their own authenticated drain authority rather than
		// the current WAL integrity envelope used by retained event proofs.
		requireActiveSnapshot(session)
		val changesBeforeProofs = sqlite.totalChanges()
		beforeWalDeletion(
			candidates.size,
			current.map(StepsWalPruneCandidate::retentionProofAuthority),
		)
		// The proof callback runs inside this owning transaction. Account for its exact
		// Room writes before the next guarded WAL mutation snapshot.
		session.acceptOwnedChanges(Math.subtractExact(sqlite.totalChanges(), changesBeforeProofs))
		val removedWalRows = deleteWalPruneCandidates(sqlite, legacy, current, session)
		StepsCountDomainMaintenanceResult.Applied(
			removedOwners = removed.removedOwners,
			removedReceipts = removed.removedReceipts,
			walEventsDeleted = removedWalRows,
		)
	} finally {
		timelineCache.invalidate()
	}
}

private suspend fun StepsCountDomainStore.removeWalPruneOwners(
	ownerKeys: List<StepsCountDomainOwnerLookupKey>,
	session: OwnerMaintenanceSession,
): StepsCountDomainMaintenanceResult.Applied {
	var removedOwners = 0L
	var removedReceipts = 0L
	ownerKeys.chunked(MAX_MAINTENANCE_OWNER_BATCH).forEach { chunk ->
		currentCoroutineContext().ensureActive()
		when (
			val result = removeOwnersAuthenticated(
				session = session,
				keys = chunk,
				requireEveryOwner = true,
			)
		) {
			is StepsCountDomainMaintenanceResult.Applied -> {
				removedOwners += result.removedOwners
				removedReceipts += result.removedReceipts
			}
			StepsCountDomainMaintenanceResult.SchemaUnavailable,
			StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable,
			StepsCountDomainMaintenanceResult.Overflow,
			-> throw CountDomainStoredEvidenceException()
		}
	}
	return StepsCountDomainMaintenanceResult.Applied(removedOwners, removedReceipts)
}

private suspend fun StepsCountDomainStore.deleteWalPruneCandidates(
	sqlite: SupportSQLiteDatabase,
	legacy: List<StepsWalPruneCandidate>,
	current: List<StepsWalPruneCandidate>,
	session: OwnerMaintenanceSession,
): Int {
	var removedWalRows = 0
	legacy.chunked(WAL_PRUNE_CANDIDATE_PAGE_SIZE).forEach { chunk ->
		currentCoroutineContext().ensureActive()
		requireActiveSnapshot(session)
		val authority = sqlite.requireLegacyV27StepsWalRetentionAuthority()
		val invalidationEffect = sqlite.roomDeleteInvalidationEffect(
			"source_event_wal",
			chunk.size.toLong(),
		)
		val deleted = sqlite.deleteAuthenticatedLegacyV27StepsWal(
			chunk.map { it.cursor.admissionOrdinal },
			authority,
		)
		session.acceptOwnedChanges(Math.addExact(deleted.toLong(), invalidationEffect))
		removedWalRows += deleted
	}
	current.chunked(WAL_PRUNE_CANDIDATE_PAGE_SIZE).forEach { chunk ->
		currentCoroutineContext().ensureActive()
		requireActiveSnapshot(session)
		val invalidationEffect = sqlite.roomDeleteInvalidationEffect(
			"source_event_wal",
			chunk.size.toLong(),
		)
		val deleted = sqlite.deleteCurrentStepsWalCandidates(chunk)
		session.acceptOwnedChanges(Math.addExact(deleted.toLong(), invalidationEffect))
		removedWalRows += deleted
	}
	return removedWalRows
}

private suspend fun StepsCountDomainStore.queryWalPruneCandidates(
	sqlite: SupportSQLiteDatabase,
	safeOrdinal: Long,
	createdBeforeMs: Long,
	limit: Int,
	session: OwnerMaintenanceSession,
): List<StepsWalPruneCandidate> {
	val candidates = ArrayList<StepsWalPruneCandidate>(limit)
	var remaining = limit
	var cursor: StepsWalPruneCursor? = null
	while (remaining > 0) {
		currentCoroutineContext().ensureActive()
		requireActiveSnapshot(session)
		val page = sqlite.querySessionWalPruneOwnerPage(
			safeOrdinal = safeOrdinal,
			createdBeforeMs = createdBeforeMs,
			after = cursor,
			limit = minOf(remaining, WAL_PRUNE_CANDIDATE_PAGE_SIZE),
		)
		if (page.isEmpty()) {
			break
		}
		candidates += page
		if (candidates.size > MAX_WAL_PRUNE_BATCH) {
			throw CountDomainStoredEvidenceException()
		}
		checkpointAndRequireUnchanged(
			session,
			StepsCountDomainMaintenanceCheckpoint.WAL_CANDIDATE_PAGE_AUTHENTICATED,
		)
		remaining -= page.size
		val next = page.last().cursor
		if (cursor != null && next <= cursor) {
			throw CountDomainStoredEvidenceException()
		}
		cursor = next
	}
	return candidates
}

private const val WAL_PRUNE_CANDIDATE_PAGE_SIZE = 128
