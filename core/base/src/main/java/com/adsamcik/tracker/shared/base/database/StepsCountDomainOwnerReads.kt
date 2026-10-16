package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore.Companion.MAX_OWNER_LOOKUP
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private suspend fun StepsCountDomainStore.readAuthenticatedOwners(
	sqlite: SupportSQLiteDatabase,
	keys: List<StepsCountDomainOwnerLookupKey>,
	limit: Int,
): StepsCountDomainOwnerRead {
	val owners = readCountDomainChunks(keys, OWNER_QUERY_CHUNK, sqlite::queryOwnerChunk)
	if (owners.size > limit) {
		return StepsCountDomainOwnerRead.Overflow
	}
	val ownersByKey = owners.associateBy {
		StepsCountDomainOwnerLookupKey(it.ownerKind, it.ownerIdentity, it.ownerRevision)
	}
	if (ownersByKey.size != owners.size) {
		return StepsCountDomainOwnerRead.Unverifiable
	}
	sqlite.requireAuthenticMissingCountDomainOwnerKeys(keys.filterNot(ownersByKey::containsKey))
	sqlite.requireAuthenticCountDomainOwnerScopes(
		owners.map { owner ->
			CountDomainOwnerScopeKey(owner.ownerKind, owner.scopeIdentity, owner.ownerIdentity)
		},
	)
	val latestRevisions = readCountDomainChunks(
		keys.map { StepsCountDomainOwnerLineageKey(it.ownerKind, it.ownerIdentity) }.distinct(),
		LATEST_QUERY_CHUNK,
		sqlite::queryLatestOwnerChunk,
	).toMap()
	if (owners.any { StepsCountDomainOwnerLineageKey(it.ownerKind, it.ownerIdentity) !in latestRevisions }) {
		return StepsCountDomainOwnerRead.Unverifiable
	}
	return readOwnerAttachments(sqlite, keys, ownersByKey, latestRevisions, limit)
}

private suspend fun StepsCountDomainStore.readOwnerAttachments(
	sqlite: SupportSQLiteDatabase,
	keys: List<StepsCountDomainOwnerLookupKey>,
	ownersByKey: Map<StepsCountDomainOwnerLookupKey, StepsCountDomainOwnerRevisionEntity>,
	latestRevisions: Map<StepsCountDomainOwnerLineageKey, Long>,
	limit: Int,
): StepsCountDomainOwnerRead {
	val owners = ownersByKey.values
	val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity).distinct()
	if (receiptIds.size > limit) {
		return StepsCountDomainOwnerRead.Overflow
	}
	val receipts = readCountDomainChunks(receiptIds, RECEIPT_QUERY_CHUNK, sqlite::queryReceiptChunk)
	if (receipts.size != receiptIds.size) {
		return StepsCountDomainOwnerRead.Unverifiable
	}
	val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
	val completenessOwners = owners.filter {
		it.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
	}
	val markers = readCountDomainChunks(completenessOwners, MARKER_QUERY_CHUNK, sqlite::queryCompletenessMarkerChunk)
	if (markers.size != completenessOwners.size) {
		return StepsCountDomainOwnerRead.Unverifiable
	}
	val markersByKey = markers.associateBy {
		StepsCountDomainOwnerLookupKey(it.ownerKind, it.ownerIdentity, it.ownerRevision)
	}
	return StepsCountDomainOwnerRead.Ready(
		keys.mapNotNull { key ->
			ownersByKey[key]?.let { owner ->
				key to StepsCountDomainStoredOwner(owner, owner.receiptIdentity?.let(receiptsById::get), markersByKey[key])
			}
		}.toMap(),
		latestRevisions,
	)
}

private suspend fun <K, V> readCountDomainChunks(
	keys: List<K>,
	chunkSize: Int,
	query: (List<K>) -> List<V>,
): List<V> {
	val rows = mutableListOf<V>()
	for (chunk in keys.chunked(chunkSize)) {
		currentCoroutineContext().ensureActive()
		rows += query(chunk)
	}
	return rows
}

@Suppress("LongMethod", "ReturnCount")
internal suspend fun StepsCountDomainStore.readOwnersInCountDomain(
	keys: List<StepsCountDomainOwnerLookupKey>,
	limit: Int,
): StepsCountDomainOwnerRead {
	val distinctKeys = keys.distinct()
	if (limit !in 1..MAX_OWNER_LOOKUP || distinctKeys.size > limit) {
		return StepsCountDomainOwnerRead.Overflow
	}
	val sqlite = database.openHelper.readableDatabase
	when (inspectedSchemaState(sqlite)) {
		StepsCountDomainSchemaState.Absent,
		StepsCountDomainSchemaState.FreshRoomScaffold,
		->
			return StepsCountDomainOwnerRead.SchemaUnavailable
		StepsCountDomainSchemaState.Incompatible ->
			return StepsCountDomainOwnerRead.Unverifiable
		StepsCountDomainSchemaState.ValidV2 -> Unit
	}
	return try {
		readAuthenticatedOwners(sqlite, distinctKeys, limit)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: SQLiteException) {
		StepsCountDomainOwnerRead.Unverifiable
	} catch (_: IllegalArgumentException) {
		StepsCountDomainOwnerRead.Unverifiable
	} catch (_: IllegalStateException) {
		StepsCountDomainOwnerRead.Unverifiable
	}
}
