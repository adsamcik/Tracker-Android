package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private data class CountDomainMaintenanceOwnerRow(
	val cursor: CountDomainMaintenanceOwnerCursor,
	val owner: StepsCountDomainOwnerRevisionEntity,
)

private data class CountDomainMaintenanceOwnerCursor(
	val ownerKind: String,
	val ownerIdentity: String,
	val ownerRevision: Long,
) : Comparable<CountDomainMaintenanceOwnerCursor> {
	override fun compareTo(other: CountDomainMaintenanceOwnerCursor): Int =
		compareValuesBy(
			this,
			other,
			CountDomainMaintenanceOwnerCursor::ownerKind,
			CountDomainMaintenanceOwnerCursor::ownerIdentity,
			CountDomainMaintenanceOwnerCursor::ownerRevision,
		)
}

private data class CountDomainMaintenanceReceiptRow(
	val rowId: Long,
	val receipt: StepsCountDomainReceiptEntity,
)

private data class CountDomainMaintenanceMarkerRow(
	val rowId: Long,
	val marker: StepsCountDomainCompletenessMarkerEntity,
)


internal suspend fun SupportSQLiteDatabase.authenticateCountDomainMaintenanceSnapshot(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
): StepsCountDomainMaintenanceMutationVersion {
	val expectedMutationVersion = maintenanceMutationVersion()
	auditCountDomainOwnerKeyPages(checkpoint, expectedMutationVersion)
	auditCountDomainOwnerPages(checkpoint, expectedMutationVersion)
	auditCountDomainReceiptPages(checkpoint, expectedMutationVersion)
	auditCountDomainMarkerPages(checkpoint, expectedMutationVersion)
	requireMaintenanceMutationVersion(expectedMutationVersion)
	return expectedMutationVersion
}

private suspend fun SupportSQLiteDatabase.auditCountDomainOwnerKeyPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_owner_revision")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`steps_count_domain_owner_revision`",
			columns = "typeof(owner_kind), typeof(owner_identity), typeof(owner_revision)",
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
		) { cursor ->
			val rowId = cursor.requiredLong("maintenance_rowid")
			cursor.requireCountDomainOwnerKeyTypes()
			rowId
		}
		if (page.isEmpty()) {
			break
		}
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		checkpoint(StepsCountDomainMaintenanceCheckpoint.OWNER_KEY_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	if (auditedCount != expectedCount) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requireCountDomainOwnerKeyTypes() {
	if (requiredText(MAINTENANCE_OWNER_KIND_TYPE_COLUMN) != "text" ||
		requiredText(MAINTENANCE_OWNER_IDENTITY_TYPE_COLUMN) != "text" ||
		requiredText(MAINTENANCE_OWNER_REVISION_TYPE_COLUMN) != "integer") {
		throw CountDomainStoredEvidenceException()
	}
}


private suspend fun SupportSQLiteDatabase.auditCountDomainOwnerPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val expectedCount =
		longForQuery("SELECT COUNT(*) FROM main.steps_count_domain_owner_revision")
	var after: CountDomainMaintenanceOwnerCursor? = null
	var auditedCount = 0L
	var priorLineage: StepsCountDomainOwnerLineageKey? = null
	var priorScope: String? = null
	while (true) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceOwnerPage(after)
		if (page.isEmpty()) {
			break
		}
		requireAuthenticCountDomainMaintenanceDependencies(page.map { it.owner })
		page.forEach { row ->
			if (!row.hasValidMaintenanceLineage(after, priorLineage, priorScope)) {
				throw CountDomainStoredEvidenceException()
			}
			val lineage = StepsCountDomainOwnerLineageKey(row.owner.ownerKind, row.owner.ownerIdentity)
			priorLineage = lineage
			priorScope = row.owner.scopeIdentity
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		after = page.last().cursor
		checkpoint(StepsCountDomainMaintenanceCheckpoint.OWNER_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	if (auditedCount != expectedCount) {
		throw CountDomainStoredEvidenceException()
	}
}

private suspend fun SupportSQLiteDatabase.auditCountDomainReceiptPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_receipt")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceReceiptPage(afterRowId, maximumRowId)
		if (page.isEmpty()) {
			break
		}
		page.forEach { row ->
			if (!row.hasAuthenticMaintenanceReceipt(afterRowId)) {
				throw CountDomainStoredEvidenceException()
			}
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last().rowId
		checkpoint(StepsCountDomainMaintenanceCheckpoint.RECEIPT_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	if (auditedCount != expectedCount) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun CountDomainMaintenanceOwnerRow.hasValidMaintenanceLineage(
	after: CountDomainMaintenanceOwnerCursor?,
	priorLineage: StepsCountDomainOwnerLineageKey?,
	priorScope: String?,
): Boolean = (after == null || cursor > after) &&
	(StepsCountDomainOwnerLineageKey(owner.ownerKind, owner.ownerIdentity) != priorLineage ||
		owner.scopeIdentity == priorScope)

private fun CountDomainMaintenanceReceiptRow.hasAuthenticMaintenanceReceipt(afterRowId: Long?): Boolean =
	(afterRowId == null || rowId > afterRowId) && StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt)


private suspend fun SupportSQLiteDatabase.auditCountDomainMarkerPages(
	checkpoint: suspend (StepsCountDomainMaintenanceCheckpoint) -> Unit,
	expectedMutationVersion: StepsCountDomainMaintenanceMutationVersion,
) {
	val (maximumRowId, expectedCount) =
		maintenanceRowIdBoundary("steps_count_domain_completeness_marker")
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryCountDomainMaintenanceMarkerPage(afterRowId, maximumRowId)
		if (page.isEmpty()) {
			break
		}
		page.forEach { row ->
			if (afterRowId != null && row.rowId <= afterRowId) {
				throw CountDomainStoredEvidenceException()
			}
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last().rowId
		checkpoint(StepsCountDomainMaintenanceCheckpoint.COMPLETENESS_DOMAIN_PAGE_AUTHENTICATED)
		requireMaintenanceMutationVersion(expectedMutationVersion)
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	if (auditedCount != expectedCount) {
		throw CountDomainStoredEvidenceException()
	}
}

internal fun SupportSQLiteDatabase.maintenanceRowIdBoundary(
	table: String,
): Pair<Long?, Long> = query(
	"SELECT MAX(rowid), COUNT(*) FROM main.`$table`",
).use { cursor ->
	if (!cursor.moveToFirst()) {
		throw CountDomainStoredEvidenceException()
	}
	val maximumRowId = if (cursor.isNull(0)) {
		null
	} else {
		cursor.requiredLong(0)
	}
	val count = cursor.requiredLong(1)
	if (count < 0L || cursor.moveToNext()) {
		throw CountDomainStoredEvidenceException()
	}
	maximumRowId to count
}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceOwnerPage(
	after: CountDomainMaintenanceOwnerCursor?,
): List<CountDomainMaintenanceOwnerRow> {
	val predicate = if (after == null) {
		""
	} else {
		"WHERE owner_kind > ? OR " +
			"(owner_kind = ? AND owner_identity > ?) OR " +
			"(owner_kind = ? AND owner_identity = ? AND owner_revision > ?)"
	}
	val arguments = buildList<Any?> {
		if (after != null) {
			add(after.ownerKind)
			add(after.ownerKind)
			add(after.ownerIdentity)
			add(after.ownerKind)
			add(after.ownerIdentity)
			add(after.ownerRevision)
		}
		add(COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE)
	}.toTypedArray()
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			var previous = after
			while (cursor.moveToNext()) {
				val owner = cursor.toStepsCountDomainOwner()
				val next = CountDomainMaintenanceOwnerCursor(
					owner.ownerKind,
					owner.ownerIdentity,
					owner.ownerRevision,
				)
				if (previous != null && next <= previous) {
					throw CountDomainStoredEvidenceException()
				}
				add(CountDomainMaintenanceOwnerRow(next, owner))
				previous = next
			}
		}
	}
}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceReceiptPage(
	afterRowId: Long?,
	maximumRowId: Long,
): List<CountDomainMaintenanceReceiptRow> =
	queryCountDomainMaintenancePage(
		table = "steps_count_domain_receipt",
		afterRowId = afterRowId,
		maximumRowId = maximumRowId,
	) { cursor ->
		CountDomainMaintenanceReceiptRow(
			rowId = cursor.requiredLong("maintenance_rowid"),
			receipt = cursor.toStepsCountDomainReceipt(),
		)
	}

private fun SupportSQLiteDatabase.queryCountDomainMaintenanceMarkerPage(
	afterRowId: Long?,
	maximumRowId: Long,
): List<CountDomainMaintenanceMarkerRow> =
	queryCountDomainMaintenancePage(
		table = "steps_count_domain_completeness_marker",
		afterRowId = afterRowId,
		maximumRowId = maximumRowId,
	) { cursor ->
		CountDomainMaintenanceMarkerRow(
			rowId = cursor.requiredLong("maintenance_rowid"),
			marker = cursor.toStepsCountDomainCompletenessMarker(),
		)
	}

private fun <T> SupportSQLiteDatabase.queryCountDomainMaintenancePage(
	table: String,
	afterRowId: Long?,
	maximumRowId: Long,
	map: (android.database.Cursor) -> T,
): List<T> = queryMaintenanceRowIdPage(
	table = "main.`$table`",
	columns = "*",
	afterRowId = afterRowId,
	maximumRowId = maximumRowId,
	map = map,
)

internal fun <T> SupportSQLiteDatabase.queryMaintenanceRowIdPage(
	table: String,
	columns: String,
	afterRowId: Long?,
	maximumRowId: Long,
	pageSize: Int = COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE,
	map: (android.database.Cursor) -> T,
): List<T> {
	val predicate = if (afterRowId == null) {
		"rowid <= ?"
	} else {
		"rowid > ? AND rowid <= ?"
	}
	val arguments = buildList<Any?> {
		if (afterRowId != null) {
			add(afterRowId)
		}
		add(maximumRowId)
		add(pageSize)
	}.toTypedArray()
	return query(
		"SELECT rowid AS maintenance_rowid, $columns FROM $table " +
			"WHERE $predicate ORDER BY rowid ASC LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(map(cursor))
		}
	}
}

internal suspend fun SupportSQLiteDatabase.queryCountDomainMaintenanceCandidates(
	keys: List<StepsCountDomainOwnerLookupKey>,
	onPage: suspend () -> Unit,
): Map<StepsCountDomainOwnerLookupKey, StepsCountDomainOwnerRevisionEntity> {
	val requested = keys.toSet()
	val candidates = mutableMapOf<
		StepsCountDomainOwnerLookupKey,
		StepsCountDomainOwnerRevisionEntity
	>()
	for (page in keys.chunked(OWNER_QUERY_CHUNK)) {
		currentCoroutineContext().ensureActive()
		val predicate = page.joinToString(" OR ") {
			"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = page.flatMap { key ->
			listOf(key.ownerKind, key.ownerIdentity, key.ownerRevision)
		}.toTypedArray<Any?>()
		query(
			"SELECT * FROM main.steps_count_domain_owner_revision WHERE $predicate",
			arguments,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val owner = cursor.toStepsCountDomainOwner()
				val key = StepsCountDomainOwnerLookupKey(
					owner.ownerKind,
					owner.ownerIdentity,
					owner.ownerRevision,
				)
				if (key !in requested || candidates.put(key, owner) != null) {
					throw CountDomainStoredEvidenceException()
				}
			}
		}
		onPage()
	}
	return candidates
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMaintenanceDependencies(
	owners: List<StepsCountDomainOwnerRevisionEntity>,
) {
	val receiptsById = authenticatedCountDomainMaintenanceReceipts(owners)
	val markersByKey = authenticatedCountDomainMaintenanceMarkers(owners)
	owners.forEach { owner ->
		val marker = markersByKey[StepsCountDomainOwnerLookupKey(owner.ownerKind, owner.ownerIdentity, owner.ownerRevision)]
		val receipt = owner.receiptIdentity?.let(receiptsById::get)
		if (!owner.hasAuthenticMaintenanceDependencies(receipt, marker)) {
			throw CountDomainStoredEvidenceException()
		}
	}
}

private fun SupportSQLiteDatabase.authenticatedCountDomainMaintenanceReceipts(
	owners: List<StepsCountDomainOwnerRevisionEntity>,
): Map<String, StepsCountDomainReceiptEntity> {
	val receiptIds = owners.mapNotNull(StepsCountDomainOwnerRevisionEntity::receiptIdentity).distinct()
	val receipts = receiptIds.chunked(COUNT_DOMAIN_MAINTENANCE_RECEIPT_CHUNK).flatMap { chunk -> queryReceiptChunk(chunk) }
	val receiptsById = receipts.associateBy(StepsCountDomainReceiptEntity::receiptIdentity)
	if (receipts.size != receiptIds.size || receiptsById.size != receipts.size) {
		throw CountDomainStoredEvidenceException()
	}
	return receiptsById
}

private fun SupportSQLiteDatabase.authenticatedCountDomainMaintenanceMarkers(
	owners: List<StepsCountDomainOwnerRevisionEntity>,
): Map<StepsCountDomainOwnerLookupKey, StepsCountDomainCompletenessMarkerEntity> {
	val completenessOwners = owners.filter { owner ->
		owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS
	}
	val markers = completenessOwners.chunked(COUNT_DOMAIN_MAINTENANCE_MARKER_CHUNK)
		.flatMap { chunk -> queryCompletenessMarkerChunk(chunk) }
	val markersByKey = markers.associateBy { marker ->
		StepsCountDomainOwnerLookupKey(marker.ownerKind, marker.ownerIdentity, marker.ownerRevision)
	}
	if (markers.size != completenessOwners.size || markersByKey.size != markers.size) {
		throw CountDomainStoredEvidenceException()
	}
	return markersByKey
}

internal fun StepsCountDomainOwnerRevisionEntity.matchesBoundReceipt(receipt: StepsCountDomainReceiptEntity): Boolean =
	operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
		receiptIdentity == receipt.receiptIdentity && ownerKind == receipt.ownerKind &&
		scopeIdentity == receipt.scopeIdentity && ownerIdentity == receipt.ownerIdentity &&
		ownerRevision == receipt.ownerRevision && ownerEffectChecksum == receipt.effectChecksum &&
		StepsCountDomainReceiptIntegrity.hasValidReceipt(receipt)

private fun StepsCountDomainOwnerRevisionEntity.hasAuthenticMaintenanceDependencies(
	receipt: StepsCountDomainReceiptEntity?,
	marker: StepsCountDomainCompletenessMarkerEntity?,
): Boolean {
	val receiptMatches = if (receipt != null) {
		matchesBoundReceipt(receipt)
	} else {
		operation != StepsCountDomainOwnerRevisionEntity.OPERATION_BIND && receiptIdentity == null
	}
	return receiptMatches && hasExpectedMaintenanceMarker(receipt, marker)
}

private fun StepsCountDomainOwnerRevisionEntity.hasExpectedMaintenanceMarker(
	receipt: StepsCountDomainReceiptEntity?,
	marker: StepsCountDomainCompletenessMarkerEntity?,
): Boolean = if (ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) {
	marker?.matches(this) == true && when (operation) {
		StepsCountDomainOwnerRevisionEntity.OPERATION_BIND -> receipt?.completionEvidenceChecksum == marker.evidenceChecksum
		StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> receipt == null
		else -> false
	}
} else {
	marker == null && receipt?.completionEvidenceChecksum == null
}


// queryMaintenanceRowIdPage puts its row ID before these three storage-class columns.
private const val MAINTENANCE_OWNER_KIND_TYPE_COLUMN = 1
private const val MAINTENANCE_OWNER_IDENTITY_TYPE_COLUMN = 2
private const val MAINTENANCE_OWNER_REVISION_TYPE_COLUMN = 3


internal const val COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE = 64
private const val COUNT_DOMAIN_MAINTENANCE_RECEIPT_CHUNK = 400
private const val COUNT_DOMAIN_MAINTENANCE_MARKER_CHUNK = 200
