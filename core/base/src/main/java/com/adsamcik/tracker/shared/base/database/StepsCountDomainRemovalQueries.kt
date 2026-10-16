package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private data class TerminalOwnerCandidate(
	val linkedAtMs: Long,
	val key: StepsCountDomainOwnerLookupKey,
)


internal fun SupportSQLiteDatabase.deleteOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): Int {
	if (keys.isEmpty()) {
		return 0
	}
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return compileStatement(
		"DELETE FROM main.steps_count_domain_owner_revision WHERE $predicate",
	).use { statement ->
		arguments.forEachIndexed { index, value ->
			when (value) {
				is String -> statement.bindString(index + 1, value)
				is Long -> statement.bindLong(index + 1, value)
				else -> error("Unsupported count-domain owner key value")
			}
		}
		statement.executeUpdateDelete()
	}
}

internal fun SupportSQLiteDatabase.deleteUnreferencedReceipts(
	receiptIdentities: List<String>,
): Long {
	if (receiptIdentities.isEmpty()) {
		return 0L
	}
	val placeholders = List(receiptIdentities.size) { "?" }.joinToString()
	val before = countUnreferencedReceipts(receiptIdentities)
	execSQL(
		"DELETE FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = steps_count_domain_receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
	return before
}

internal fun SupportSQLiteDatabase.countUnreferencedReceipts(
	receiptIdentities: List<String>,
): Long {
	if (receiptIdentities.isEmpty()) {
		return 0L
	}
	val placeholders = List(receiptIdentities.size) { "?" }.joinToString()
	return longForQuery(
		"SELECT COUNT(*) FROM main.steps_count_domain_receipt AS receipt " +
			"WHERE receipt_identity IN ($placeholders) " +
			"AND NOT EXISTS (SELECT 1 FROM main.steps_count_domain_owner_revision AS owner " +
			"WHERE owner.receipt_identity = receipt.receipt_identity)",
		receiptIdentities.toTypedArray(),
	)
}

internal suspend fun SupportSQLiteDatabase.queryTerminalCompactionCandidates(
	maximumRetainedTerminalOwners: Int,
	limit: Int,
	onPage: suspend () -> Unit,
): List<StepsCountDomainOwnerLookupKey> {
	var skipped = 0
	var cursor: TerminalOwnerCandidate? = null
	val candidates = mutableListOf<StepsCountDomainOwnerLookupKey>()
	while (candidates.size < limit) {
		currentCoroutineContext().ensureActive()
		val remainingToRead =
			maximumRetainedTerminalOwners.toLong() - skipped.toLong() +
				limit.toLong() - candidates.size.toLong()
		val page = queryTerminalCompactionCandidatePage(
			after = cursor,
			limit = minOf(COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE.toLong(), remainingToRead).toInt(),
		)
		if (page.isEmpty()) {
			break
		}
		onPage()
		val skippedInPage = minOf(page.size, maximumRetainedTerminalOwners - skipped)
		skipped += skippedInPage
		candidates += page.drop(skippedInPage).take(limit - candidates.size).map(TerminalOwnerCandidate::key)
		val next = page.last()
		if (cursor != null && !next.isAfterTerminalCursor(cursor)) {
			throw CountDomainStoredEvidenceException()
		}
		cursor = next
		if (page.size < COUNT_DOMAIN_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	return candidates
}

private fun SupportSQLiteDatabase.queryTerminalCompactionCandidatePage(
	after: TerminalOwnerCandidate?,
	limit: Int,
): List<TerminalOwnerCandidate> {
	if (limit <= 0) {
		return emptyList()
	}
	val cursorPredicate = if (after == null) {
		""
	} else {
		"AND (linked_at_ms < ? OR " +
			"(linked_at_ms = ? AND owner_kind > ?) OR " +
			"(linked_at_ms = ? AND owner_kind = ? AND owner_identity > ?) OR " +
			"(linked_at_ms = ? AND owner_kind = ? AND owner_identity = ? " +
			"AND owner_revision < ?)) "
	}
	val arguments = buildList<Any?> {
		if (after != null) {
			add(after.linkedAtMs)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.key.ownerIdentity)
			add(after.linkedAtMs)
			add(after.key.ownerKind)
			add(after.key.ownerIdentity)
			add(after.key.ownerRevision)
		}
		add(limit)
	}.toTypedArray()
	return query(
		"SELECT owner_kind, owner_identity, owner_revision " +
			", linked_at_ms " +
			"FROM main.steps_count_domain_owner_revision " +
			"WHERE operation IN ('RETRACT', 'UNPROVEN') " +
			cursorPredicate +
			"ORDER BY linked_at_ms DESC, owner_kind, owner_identity, owner_revision DESC " +
			"LIMIT ?",
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					TerminalOwnerCandidate(
						linkedAtMs = cursor.requiredLong("linked_at_ms"),
						key = StepsCountDomainOwnerLookupKey(
							cursor.requiredText("owner_kind"),
							cursor.requiredText("owner_identity"),
							cursor.requiredLong("owner_revision"),
						),
					),
				)
			}
		}
	}
}

private fun TerminalOwnerCandidate.isAfterTerminalCursor(
	previous: TerminalOwnerCandidate,
): Boolean =
	linkedAtMs < previous.linkedAtMs ||
		(linkedAtMs == previous.linkedAtMs && key.ownerKind > previous.key.ownerKind) ||
		(linkedAtMs == previous.linkedAtMs &&
			key.ownerKind == previous.key.ownerKind &&
			key.ownerIdentity > previous.key.ownerIdentity) ||
		(linkedAtMs == previous.linkedAtMs &&
			key.ownerKind == previous.key.ownerKind &&
			key.ownerIdentity == previous.key.ownerIdentity &&
			key.ownerRevision < previous.key.ownerRevision)


internal fun SupportSQLiteDatabase.totalChanges(): Long =
	longForQuery("SELECT total_changes()")

internal fun SupportSQLiteDatabase.maintenanceMutationVersion() =
	StepsCountDomainMaintenanceMutationVersion(
		totalChanges = totalChanges(),
		schemaVersion = longForQuery("PRAGMA main.schema_version"),
		tempSchemaVersion = longForQuery("PRAGMA temp.schema_version"),
	)

internal fun SupportSQLiteDatabase.requireMaintenanceMutationVersion(
	expected: StepsCountDomainMaintenanceMutationVersion,
) {
	if (maintenanceMutationVersion() != expected) {
		throw CountDomainStoredEvidenceException()
	}
}

internal fun SupportSQLiteDatabase.executeDelete(sql: String): Int =
	compileStatement(sql).use { statement -> statement.executeUpdateDelete() }


internal fun SupportSQLiteDatabase.roomDeleteInvalidationEffect(
	table: String,
	affectedRows: Long,
): Long {
	if (affectedRows == 0L) {
		return 0L
	}
	val triggerSql = roomDeletionTriggerSql(table) ?: return 0L
	val tableIds = ROOM_INVALIDATION_TABLE_ID.findAll(triggerSql)
		.mapNotNull { match -> match.groupValues[1].toLongOrNull() }
		.toList()
	if (tableIds.size != 1 || tableIds.single() !in 0L..Int.MAX_VALUE.toLong()) {
		throw CountDomainStoredEvidenceException()
	}
	val invalidated = longForQuery(
		"SELECT invalidated FROM temp.room_table_modification_log WHERE table_id = ?",
		arrayOf(tableIds.single()),
	)
	if (invalidated !in 0L..1L) {
		throw CountDomainStoredEvidenceException()
	}
	return if (invalidated == 0L) {
		1L
	} else {
		0L
	}
}

private fun SupportSQLiteDatabase.roomDeletionTriggerSql(table: String): String? {
	val triggerName = "room_table_modification_trigger_${table}_DELETE"
	return query(
		"SELECT sql FROM sqlite_temp_master WHERE type = 'trigger' AND name = ? LIMIT 2",
		arrayOf(triggerName),
	).use { cursor ->
		if (!cursor.moveToFirst() || cursor.isNull(0)) {
			return null
		}
		val sql = cursor.getString(0)
		if (cursor.moveToNext()) {
			throw CountDomainStoredEvidenceException()
		}
		sql
	}
}


private val ROOM_INVALIDATION_TABLE_ID =
	Regex("""(?i)\btable_id\s*=\s*([0-9]+)\b""")

