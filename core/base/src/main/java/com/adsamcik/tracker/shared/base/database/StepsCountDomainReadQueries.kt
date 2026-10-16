package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity

internal fun SupportSQLiteDatabase.queryOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"AND owner_revision = ? LIMIT 2",
		arrayOf<Any?>(ownerKind, scopeIdentity, ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			return@use null
		}
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) {
			throw CountDomainStoredEvidenceException()
		}
		owner
	}
}

internal fun SupportSQLiteDatabase.queryLatestOwner(
	ownerKind: String,
	scopeIdentity: String,
	ownerIdentity: String,
): StepsCountDomainOwnerRevisionEntity? {
	requireAuthenticCountDomainOwnerScopes(
		listOf(CountDomainOwnerScopeKey(ownerKind, scopeIdentity, ownerIdentity)),
	)
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE " +
			"owner_kind = ? AND scope_identity = ? AND owner_identity = ? " +
			"ORDER BY owner_revision DESC LIMIT 2",
		arrayOf(ownerKind, scopeIdentity, ownerIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			return@use null
		}
		val owner = cursor.toStepsCountDomainOwner()
		if (cursor.moveToNext()) {
			val olderOrMalformed = cursor.toStepsCountDomainOwner()
			if (olderOrMalformed.ownerKind != ownerKind ||
				olderOrMalformed.scopeIdentity != scopeIdentity ||
				olderOrMalformed.ownerIdentity != ownerIdentity
			) {
				throw CountDomainStoredEvidenceException()
			}
		}
		owner
	}
}

internal fun SupportSQLiteDatabase.queryReceipt(
	receiptIdentity: String,
): StepsCountDomainReceiptEntity? {
	requireAuthenticCountDomainReceiptKeys(listOf(receiptIdentity))
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity = ? LIMIT 2",
		arrayOf(receiptIdentity),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			return@use null
		}
		val receipt = cursor.toStepsCountDomainReceipt()
		if (cursor.moveToNext()) {
			throw CountDomainStoredEvidenceException()
		}
		receipt
	}
}

internal fun SupportSQLiteDatabase.queryCompletenessMarker(
	ownerIdentity: String,
	ownerRevision: Long,
): StepsCountDomainCompletenessMarkerEntity? {
	requireAuthenticCountDomainMarkerKeys(listOf(ownerIdentity to ownerRevision))
	return query(
		"SELECT * FROM main.steps_count_domain_completeness_marker " +
			"WHERE owner_identity = ? AND owner_revision = ? LIMIT 2",
		arrayOf<Any?>(ownerIdentity, ownerRevision),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			return@use null
		}
		val marker = cursor.toStepsCountDomainCompletenessMarker()
		if (cursor.moveToNext()) {
			throw CountDomainStoredEvidenceException()
		}
		marker
	}
}

internal fun SupportSQLiteDatabase.queryOwnerChunk(
	keys: List<StepsCountDomainOwnerLookupKey>,
): List<StepsCountDomainOwnerRevisionEntity> {
	if (keys.isEmpty()) {
		return emptyList()
	}
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ? AND owner_revision = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity, it.ownerRevision) }
	return query(
		"SELECT * FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT ${keys.size + 1}",
		arguments.toTypedArray<Any?>(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainOwner())
		}
	}
}

internal fun SupportSQLiteDatabase.queryReceiptChunk(
	identities: List<String>,
): List<StepsCountDomainReceiptEntity> {
	if (identities.isEmpty()) {
		return emptyList()
	}
	requireAuthenticCountDomainReceiptKeys(identities)
	val placeholders = List(identities.size) { "?" }.joinToString()
	return query(
		"SELECT * FROM main.steps_count_domain_receipt WHERE receipt_identity IN ($placeholders) " +
			"ORDER BY receipt_identity LIMIT ${identities.size + 1}",
		identities.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) add(cursor.toStepsCountDomainReceipt())
		}
	}
}

internal fun SupportSQLiteDatabase.queryCompletenessMarkerChunk(
		owners: List<StepsCountDomainOwnerRevisionEntity>,
	): List<StepsCountDomainCompletenessMarkerEntity> {
		if (owners.isEmpty()) {
			return emptyList()
		}
		requireAuthenticCountDomainMarkerKeys(
			owners.map { owner -> owner.ownerIdentity to owner.ownerRevision },
		)
		val predicate = owners.joinToString(" OR ") {
			"(owner_identity = ? AND owner_revision = ?)"
		}
		val arguments = owners.flatMap { listOf(it.ownerIdentity, it.ownerRevision) }
		return query(
			"SELECT * FROM main.steps_count_domain_completeness_marker WHERE $predicate " +
				"ORDER BY owner_identity, owner_revision LIMIT ${owners.size + 1}",
			arguments.toTypedArray<Any?>(),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.toStepsCountDomainCompletenessMarker())
			}
		}
	}

internal fun SupportSQLiteDatabase.queryLatestOwnerChunk(
	keys: List<StepsCountDomainOwnerLineageKey>,
): List<Pair<StepsCountDomainOwnerLineageKey, Long>> {
	if (keys.isEmpty()) {
		return emptyList()
	}
	val predicate = keys.joinToString(" OR ") {
		"(owner_kind = ? AND owner_identity = ?)"
	}
	val arguments = keys.flatMap { listOf(it.ownerKind, it.ownerIdentity) }
	return query(
		"SELECT owner_kind, owner_identity, MAX(owner_revision) AS latest_revision " +
			"FROM main.steps_count_domain_owner_revision WHERE $predicate " +
			"GROUP BY owner_kind, owner_identity LIMIT ${keys.size + 1}",
		arguments.toTypedArray(),
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					StepsCountDomainOwnerLineageKey(
						cursor.requiredText("owner_kind"),
						cursor.requiredText("owner_identity"),
					) to cursor.requiredLong("latest_revision"),
				)
			}
		}
	}
}


internal fun SupportSQLiteDatabase.requireAuthenticMissingCountDomainOwnerKeys(
	keys: List<StepsCountDomainOwnerLookupKey>,
) {
	for (chunk in keys.distinct().chunked(OWNER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) {
			continue
		}
		val predicate = chunk.joinToString(" OR ") {
			"(CAST(owner_kind AS TEXT) = ? AND CAST(owner_identity AS TEXT) = ? " +
				"AND CAST(owner_revision AS INTEGER) = ?) OR " +
				"(owner_kind = ? AND owner_revision = ? AND typeof(owner_identity) != 'text') OR " +
				"(owner_identity = ? AND owner_revision = ? AND typeof(owner_kind) != 'text') OR " +
				"(owner_kind = ? AND owner_identity = ? AND typeof(owner_revision) != 'integer')"
		}
		val arguments = chunk.flatMap { key ->
			listOf(
				key.ownerKind,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerRevision,
				key.ownerIdentity,
				key.ownerRevision,
				key.ownerKind,
				key.ownerIdentity,
			)
		}
		val hidden = query(
			"SELECT 1 FROM main.steps_count_domain_owner_revision WHERE ($predicate) LIMIT 1",
			arguments.toTypedArray<Any?>(),
		).use { cursor -> cursor.moveToFirst() }
		if (hidden) {
			throw CountDomainStoredEvidenceException()
		}
	}
}


// Explicit CAST operands preserve storage-class ordering despite a key column's TEXT affinity.
// Each UNION branch uses one direct indexed range; CAST only verifies its bounded candidates.
private fun indexedNonTextKeyRanges(column: String, nullOperand: String = "NULL"): List<String> = listOf(
	"$column IS $nullOperand",
	"$column < CAST('' AS TEXT)",
	"$column >= CAST(X'' AS BLOB)",
)

private fun indexedNonTextKeyRange(column: String): String =
	indexedNonTextKeyRanges(column).joinToString(" OR ", "(", ")")

private fun countDomainOwnerScopeCandidatePredicates(): List<Pair<String, String>> {
	val kindRanges = indexedNonTextKeyRanges("owner.owner_kind", "requested.non_text_null")
	val identityRanges = indexedNonTextKeyRanges("owner.owner_identity", "requested.non_text_null")
	return listOf(
		("owner.owner_kind = requested.owner_kind AND owner.owner_identity = requested.owner_identity") to
			COUNT_DOMAIN_OWNER_PRIMARY_INDEX,
	) + identityRanges.map { range ->
		("owner.owner_kind = requested.owner_kind AND $range AND typeof(owner.owner_identity) != 'text' " +
			"AND CAST(owner.owner_kind AS TEXT) = requested.owner_kind " +
			"AND CAST(owner.owner_identity AS TEXT) = requested.owner_identity") to COUNT_DOMAIN_OWNER_PRIMARY_INDEX
	} + kindRanges.map { range ->
		("$range AND typeof(owner.owner_kind) != 'text' " +
			"AND CAST(owner.owner_kind AS TEXT) = requested.owner_kind " +
			"AND CAST(owner.owner_identity AS TEXT) = requested.owner_identity") to COUNT_DOMAIN_OWNER_PRIMARY_INDEX
	} + identityRanges.map { range ->
		("owner.owner_kind = requested.owner_kind AND owner.scope_identity = requested.scope_identity " +
			"AND $range AND typeof(owner.owner_identity) != 'text'") to COUNT_DOMAIN_OWNER_SCOPE_INDEX
	} + kindRanges.map { range ->
		("$range AND owner.scope_identity = requested.scope_identity " +
			"AND owner.owner_identity = requested.owner_identity AND typeof(owner.owner_kind) != 'text'") to
			COUNT_DOMAIN_OWNER_PRIMARY_INDEX
	} + listOf(
		("owner.owner_kind = requested.owner_kind AND owner.owner_identity = requested.owner_identity " +
			"AND typeof(owner.scope_identity) != 'text'") to COUNT_DOMAIN_OWNER_PRIMARY_INDEX,
	)
}

// These indexes already belong to the exact validated owner table; no schema or extra index is required.
private const val COUNT_DOMAIN_OWNER_PRIMARY_INDEX = "sqlite_autoindex_steps_count_domain_owner_revision_1"
private const val COUNT_DOMAIN_OWNER_SCOPE_INDEX = "idx_steps_count_domain_owner_scope"

internal fun SupportSQLiteDatabase.requireAuthenticCountDomainOwnerScopes(
	keys: List<CountDomainOwnerScopeKey>,
) {
	val invalidPredicate = "typeof(owner.owner_kind) != 'text' OR " +
		"owner.owner_kind NOT IN " +
		"('SESSION_WAL', 'SESSION_FACT', 'SESSION_COMPLETENESS', 'AMBIENT_FACT') OR " +
		"typeof(owner.scope_identity) != 'text' OR " +
		"CAST(owner.scope_identity AS TEXT) != requested.scope_identity OR " +
		"length(owner.scope_identity) != 71 OR substr(owner.scope_identity, 1, 7) != 'sha256:' OR " +
		"substr(owner.scope_identity, 8) GLOB '*[^0-9a-f]*' OR " +
		"typeof(owner.owner_identity) != 'text' OR length(owner.owner_identity) != 71 OR " +
		"substr(owner.owner_identity, 1, 7) != 'sha256:' OR " +
		"substr(owner.owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
		"typeof(owner.owner_revision) != 'integer' OR owner.owner_revision <= 0"
	val candidates = countDomainOwnerScopeCandidatePredicates().joinToString(" UNION ALL ") { (predicate, index) ->
		"SELECT 1 FROM requested_owner_scopes AS requested " +
			"CROSS JOIN main.steps_count_domain_owner_revision AS owner INDEXED BY $index " +
			"WHERE ($predicate) AND ($invalidPredicate)"
	}
	for (chunk in keys.distinct().chunked(LATEST_QUERY_CHUNK)) {
		if (chunk.isEmpty()) {
			continue
		}
		val requestedValues = chunk.joinToString(", ") { "(?, ?, ?, ?)" }
		val arguments = chunk.flatMap { key ->
			listOf<Any?>(key.ownerKind, key.scopeIdentity, key.ownerIdentity, null)
		}
		val invalid = query(
			"WITH requested_owner_scopes(owner_kind, scope_identity, owner_identity, non_text_null) AS " +
				"(VALUES $requestedValues) $candidates LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) {
			throw CountDomainStoredEvidenceException()
		}
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainReceiptKeys(
	identities: List<String>,
) {
	for (chunk in identities.distinct().chunked(RECEIPT_QUERY_CHUNK)) {
		if (chunk.isEmpty()) {
			continue
		}
		val receiptIdentityNonText = indexedNonTextKeyRange("receipt_identity")
		val predicate = chunk.joinToString(" OR ") {
			"(receipt_identity = ? OR ($receiptIdentityNonText " +
				"AND typeof(receipt_identity) != 'text' AND CAST(receipt_identity AS TEXT) = ?))"
		}
		val arguments = chunk.flatMap { identity -> listOf(identity, identity) }
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_receipt WHERE ($predicate) AND (" +
				"typeof(receipt_identity) != 'text' OR length(receipt_identity) != 71 OR " +
				"substr(receipt_identity, 1, 7) != 'sha256:' OR " +
				"substr(receipt_identity, 8) GLOB '*[^0-9a-f]*') LIMIT 1",
			arguments.toTypedArray(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) {
			throw CountDomainStoredEvidenceException()
		}
	}
}

private fun SupportSQLiteDatabase.requireAuthenticCountDomainMarkerKeys(
	keys: List<Pair<String, Long>>,
) {
	for (chunk in keys.distinct().chunked(MARKER_QUERY_CHUNK)) {
		if (chunk.isEmpty()) {
			continue
		}
		val predicate = chunk.joinToString(" OR ") {
			"((owner_identity = ? AND owner_revision = ?) OR " +
				"((typeof(owner_identity) != 'text' OR typeof(owner_revision) != 'integer') " +
				"AND CAST(owner_identity AS TEXT) = ? AND CAST(owner_revision AS INTEGER) = ?))"
		}
		val arguments = chunk.flatMap { (identity, revision) ->
			listOf(identity, revision, identity, revision)
		}
		val invalid = query(
			"SELECT 1 FROM main.steps_count_domain_completeness_marker WHERE ($predicate) AND (" +
				"typeof(owner_kind) != 'text' OR owner_kind != 'SESSION_COMPLETENESS' OR " +
				"typeof(owner_identity) != 'text' OR length(owner_identity) != 71 OR " +
				"substr(owner_identity, 1, 7) != 'sha256:' OR " +
				"substr(owner_identity, 8) GLOB '*[^0-9a-f]*' OR " +
				"typeof(owner_revision) != 'integer' OR owner_revision <= 0) LIMIT 1",
			arguments.toTypedArray<Any?>(),
		).use { cursor -> cursor.moveToFirst() }
		if (invalid) {
			throw CountDomainStoredEvidenceException()
		}
	}
}


internal const val OWNER_QUERY_CHUNK = 100
internal const val LATEST_QUERY_CHUNK = 200
internal const val RECEIPT_QUERY_CHUNK = 400
internal const val MARKER_QUERY_CHUNK = 200
