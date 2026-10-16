package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_MAINTENANCE_OWNER_BATCH
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.OwnerMaintenanceSession
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun StepsCountDomainStore.removeOwnersAuthenticated(
	session: OwnerMaintenanceSession,
	keys: List<StepsCountDomainOwnerLookupKey>,
	requireEveryOwner: Boolean = false,
): StepsCountDomainMaintenanceResult {
	requireActiveSnapshot(session)
	val distinct = keys.distinct()
	ownerRemovalPrecondition(distinct, session)?.let { return it }
	val sqlite = database.openHelper.writableDatabase
	val ownersByKey = sqlite.queryCountDomainMaintenanceCandidates(
		keys = distinct,
		onPage = {
			checkpointAndRequireUnchanged(
				session,
				StepsCountDomainMaintenanceCheckpoint.OWNER_CANDIDATE_PAGE_AUTHENTICATED,
			)
		},
	)
	if (requireEveryOwner && ownersByKey.size != distinct.size) {
		return StepsCountDomainMaintenanceResult.StoredEvidenceUnverifiable
	}
	val owners = ownersByKey.values
	val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity)
		.distinct()
	currentCoroutineContext().ensureActive()
	val removedOwnerRows = deleteOwnedCountDomainChunks(sqlite, distinct, ownersByKey, session)
	check(removedOwnerRows == owners.size) {
		"Steps count-domain owner changed during removal"
	}
	requireActiveSnapshot(session)
	val expectedRemovedReceipts = sqlite.countUnreferencedReceipts(receiptIds)
	val receiptInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.RECEIPT_TABLE,
		expectedRemovedReceipts,
	)
	val removedReceipts = sqlite.deleteUnreferencedReceipts(receiptIds)
	check(removedReceipts == expectedRemovedReceipts) {
		"Steps count-domain receipt changed during removal"
	}
	session.acceptOwnedChanges(Math.addExact(removedReceipts, receiptInvalidationEffect))
	return StepsCountDomainMaintenanceResult.Applied(owners.size.toLong(), removedReceipts)
}

private fun ownerRemovalPrecondition(
	keys: List<StepsCountDomainOwnerLookupKey>,
	session: OwnerMaintenanceSession,
): StepsCountDomainMaintenanceResult? {
	if (keys.size > MAX_MAINTENANCE_OWNER_BATCH) {
		return StepsCountDomainMaintenanceResult.Overflow
	}
	if (!session.schemaAvailable) {
		return StepsCountDomainMaintenanceResult.SchemaUnavailable
	}
	if (keys.isEmpty()) {
		return StepsCountDomainMaintenanceResult.Applied(0L, 0L)
	}
	return null
}

private suspend fun StepsCountDomainStore.deleteOwnedCountDomainChunks(
	sqlite: SupportSQLiteDatabase,
	keys: List<StepsCountDomainOwnerLookupKey>,
	ownersByKey: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainOwnerRevisionEntity>,
	session: OwnerMaintenanceSession,
): Int {
	var removedOwnerRows = 0
	keys.chunked(OWNER_QUERY_CHUNK).forEach { chunk ->
		currentCoroutineContext().ensureActive()
		requireActiveSnapshot(session)
		val chunkOwners = chunk.mapNotNull(ownersByKey::get)
		val removedMarkers = chunkOwners.count { owner ->
			owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
		}
		val expectedOwnerRows = chunkOwners.size.toLong()
		val expectedEffects = Math.addExact(
			Math.addExact(expectedOwnerRows, removedMarkers.toLong()),
			Math.addExact(
				sqlite.roomDeleteInvalidationEffect(
					StepsCountDomainSchema.OWNER_TABLE,
					expectedOwnerRows,
				),
				sqlite.roomDeleteInvalidationEffect(
					StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
					removedMarkers.toLong(),
				),
			),
		)
		val removedChunk = sqlite.deleteOwnerChunk(chunk)
		check(removedChunk == chunkOwners.size) {
			"Steps count-domain owner changed during removal"
		}
		session.acceptOwnedChanges(
			expectedEffects,
		)
		removedOwnerRows += removedChunk
	}
	return removedOwnerRows
}

internal fun StepsCountDomainStore.clearAuthenticated(
	mode: StepsCountDomainFullClearMode,
	session: OwnerMaintenanceSession,
): StepsCountDomainMaintenanceResult {
	requireActiveSnapshot(session)
	if (!session.schemaAvailable) {
		return StepsCountDomainMaintenanceResult.SchemaUnavailable
	}
	val sqlite = database.openHelper.writableDatabase
	val ownerCount = sqlite.longForQuery(
		if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
			"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision"
		} else {
			"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision " +
				"WHERE operation = 'BIND'"
		},
	)
	if (mode == StepsCountDomainFullClearMode.REMOVE_ALL) {
		removeAllCountDomainOwners(sqlite, ownerCount, session)
	} else {
		removeBoundCountDomainOwners(sqlite, ownerCount, session)
	}
	val receiptCount =
		sqlite.longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_receipt")
	requireActiveSnapshot(session)
	val receiptInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.RECEIPT_TABLE,
		receiptCount,
	)
	val removedReceipts = sqlite.executeDelete("DELETE FROM main.steps_count_domain_receipt")
	check(removedReceipts.toLong() == receiptCount)
	session.acceptOwnedChanges(Math.addExact(receiptCount, receiptInvalidationEffect))
	return StepsCountDomainMaintenanceResult.Applied(ownerCount, receiptCount)
}

private fun StepsCountDomainStore.removeAllCountDomainOwners(
	sqlite: SupportSQLiteDatabase,
	ownerCount: Long,
	session: OwnerMaintenanceSession,
) {
	val markerCount =
		sqlite.longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker")
	requireActiveSnapshot(session)
	val markerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
		markerCount,
	)
	val removedMarkers = sqlite.executeDelete(
		"DELETE FROM main.steps_count_domain_completeness_marker",
	)
	check(removedMarkers.toLong() == markerCount)
	session.acceptOwnedChanges(Math.addExact(markerCount, markerInvalidationEffect))
	requireActiveSnapshot(session)
	val ownerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.OWNER_TABLE,
		ownerCount,
	)
	val removedOwners =
		sqlite.executeDelete("DELETE FROM main.steps_count_domain_owner_revision")
	check(removedOwners.toLong() == ownerCount)
	session.acceptOwnedChanges(Math.addExact(ownerCount, ownerInvalidationEffect))
}

private fun StepsCountDomainStore.removeBoundCountDomainOwners(
	sqlite: SupportSQLiteDatabase,
	ownerCount: Long,
	session: OwnerMaintenanceSession,
) {
	val markerCount = sqlite.longForQuery(
		"SELECT COUNT(*) FROM main.steps_count_domain_completeness_marker AS marker " +
			"JOIN main.steps_count_domain_owner_revision AS owner " +
			"ON owner.owner_kind = marker.owner_kind " +
			"AND owner.owner_identity = marker.owner_identity " +
			"AND owner.owner_revision = marker.owner_revision " +
			"WHERE owner.operation = 'BIND'",
	)
	requireActiveSnapshot(session)
	val ownerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.OWNER_TABLE,
		ownerCount,
	)
	val markerInvalidationEffect = sqlite.roomDeleteInvalidationEffect(
		StepsCountDomainSchema.COMPLETENESS_MARKER_TABLE,
		markerCount,
	)
	val removedOwners = sqlite.executeDelete(
		"DELETE FROM main.steps_count_domain_owner_revision WHERE operation = 'BIND'",
	)
	check(removedOwners.toLong() == ownerCount)
	session.acceptOwnedChanges(
		Math.addExact(
			Math.addExact(ownerCount, markerCount),
			Math.addExact(ownerInvalidationEffect, markerInvalidationEffect),
		),
	)
}

internal suspend fun StepsCountDomainStore.compactTerminalOwnersAuthenticated(
	maximumRetainedTerminalOwners: Int,
	batchSize: Int,
	sourceFenceAuthenticated: Boolean,
	session: OwnerMaintenanceSession,
): StepsCountDomainMaintenanceResult {
	require(sourceFenceAuthenticated)
	require(maximumRetainedTerminalOwners >= 0)
	require(batchSize in 1..MAX_MAINTENANCE_OWNER_BATCH)
	requireActiveSnapshot(session)
	if (!session.schemaAvailable) {
		return StepsCountDomainMaintenanceResult.SchemaUnavailable
	}
	currentCoroutineContext().ensureActive()
	val candidates = database.openHelper.writableDatabase.queryTerminalCompactionCandidates(
		maximumRetainedTerminalOwners = maximumRetainedTerminalOwners,
		limit = batchSize,
		onPage = {
			checkpointAndRequireUnchanged(
				session,
				StepsCountDomainMaintenanceCheckpoint.OWNER_CANDIDATE_PAGE_AUTHENTICATED,
			)
		},
	)
	return removeOwnersAuthenticated(
		session = session,
		keys = candidates,
	)
}
