package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity

/**
 * Result of appending count-domain ownership, distinguishing idempotent replay from identity,
 * revision and terminal-owner conflicts.
 */
enum class StepsCountDomainAppendResult {
	INSERTED,
	EXACT_REPLAY,
	IDENTITY_CONFLICT,
	REVISION_GAP,
	TERMINAL_OWNER,
}

/** Source-specific Room contract registered by the serialized AppDatabase schema assembly. */
@Dao
interface StepsCountDomainReceiptDao {
	/**
	 * Inserts [StepsCountDomainReceiptEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReceipt(receipt: StepsCountDomainReceiptEntity): Long

	/**
	 * Inserts [StepsCountDomainOwnerRevisionEntity] rows. Existing conflicting rows remain unchanged.
	 * An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertOwner(owner: StepsCountDomainOwnerRevisionEntity): Long

	/**
	 * Inserts [StepsCountDomainCompletenessMarkerEntity] rows. Existing conflicting rows remain
	 * unchanged. An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCompletenessMarker(
		marker: StepsCountDomainCompletenessMarkerEntity,
	): Long

	/**
	 * Reads `steps_count_domain_receipt` rows. Query conditions include `receipt_identity =
	 * :identity`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity = :identity LIMIT 1",
	)
	suspend fun receipt(identity: String): StepsCountDomainReceiptEntity?

	/**
	 * Reads `steps_count_domain_owner_revision` rows. Query conditions include `owner_kind =
	 * :ownerKind`, `owner_identity = :ownerIdentity`, `owner_revision = :ownerRevision`. Returns at
	 * most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = :ownerKind " +
			"AND owner_identity = :ownerIdentity AND owner_revision = :ownerRevision LIMIT 1",
	)
	suspend fun owner(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): StepsCountDomainOwnerRevisionEntity?

	/**
	 * Reads `steps_count_domain_owner_revision` rows. Query conditions include `owner_kind =
	 * :ownerKind`, `owner_identity = :ownerIdentity`. Orders by `owner_revision DESC`. Returns at
	 * most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = :ownerKind " +
			"AND owner_identity = :ownerIdentity ORDER BY owner_revision DESC LIMIT 1",
	)
	suspend fun latestOwner(
		ownerKind: String,
		ownerIdentity: String,
	): StepsCountDomainOwnerRevisionEntity?

	/**
	 * Reads `steps_count_domain_owner_revision` rows. Query conditions include `owner_identity IN
	 * (:ownerIdentities)`. Orders by `owner_kind, owner_identity, owner_revision`. Returns at most
	 * `limit` rows.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_identity IN (:ownerIdentities) " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun owners(
		ownerIdentities: List<String>,
		limit: Int,
	): List<StepsCountDomainOwnerRevisionEntity>

	/**
	 * Reads `steps_count_domain_receipt` rows. Query conditions include `receipt_identity IN
	 * (:receiptIdentities)`. Orders by `receipt_identity`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity IN (:receiptIdentities) " +
			"ORDER BY receipt_identity LIMIT :limit",
	)
	suspend fun receipts(
		receiptIdentities: List<String>,
		limit: Int,
	): List<StepsCountDomainReceiptEntity>

	/**
	 * Reads `steps_count_domain_completeness_marker` rows. Query conditions include `owner_identity =
	 * :ownerIdentity`, `owner_revision = :ownerRevision`. Returns at most 1 row. Returns null when no
	 * row matches.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_completeness_marker WHERE owner_identity = :ownerIdentity " +
			"AND owner_revision = :ownerRevision LIMIT 1",
	)
	suspend fun completenessMarker(
		ownerIdentity: String,
		ownerRevision: Long,
	): StepsCountDomainCompletenessMarkerEntity?

	/**
	 * Reads `steps_count_domain_completeness_marker` rows. Query conditions include `owner_identity
	 * IN (:ownerIdentities)`. Orders by `owner_identity, owner_revision`. Returns at most `limit`
	 * rows.
	 */
	@Query(
		"SELECT * FROM steps_count_domain_completeness_marker " +
			"WHERE owner_identity IN (:ownerIdentities) " +
			"ORDER BY owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun completenessMarkers(
		ownerIdentities: List<String>,
		limit: Int,
	): List<StepsCountDomainCompletenessMarkerEntity>

	/**
	 * Atomically appends a count-domain owner with its optional receipt and completeness marker;
	 * distinguishes exact replay, identity conflict, revision gaps and terminal ownership.
	 */
	// Preserve the ordered shape, terminal, replay, continuation and insertion outcomes.
	@Suppress("ReturnCount")
	@Transaction
	suspend fun append(
		storedReceipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		marker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainAppendResult {
		if (!hasCoherentAppendShape(storedReceipt, owner, marker)) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}

		val latest = latestOwner(owner.ownerKind, owner.ownerIdentity)
		val exact = owner(owner.ownerKind, owner.ownerIdentity, owner.ownerRevision)
		if (hasTerminalAppendOwner(latest, exact, owner)) {
			return StepsCountDomainAppendResult.TERMINAL_OWNER
		}
		if (exact != null) {
			return authenticateAppendReplay(storedReceipt, owner, marker, exact)
		}
		appendContinuationFailure(storedReceipt, owner, latest)?.let { return it }
		return persistAppend(storedReceipt, owner, marker)
	}

	// Compare only the supplied saved owner, optional receipt and optional marker; reads remain lazy.
	@Suppress("ComplexCondition")
	private suspend fun authenticateAppendReplay(
		storedReceipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		marker: StepsCountDomainCompletenessMarkerEntity?,
		exact: StepsCountDomainOwnerRevisionEntity,
	): StepsCountDomainAppendResult {
		return if (exact == owner &&
			(storedReceipt == null ||
				receipt(storedReceipt.receiptIdentity) == storedReceipt) &&
			(marker == null ||
				completenessMarker(
					marker.ownerIdentity,
					marker.ownerRevision,
				) == marker)
		) {
			StepsCountDomainAppendResult.EXACT_REPLAY
		} else {
			StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
	}

	// Each ordered rejection retains its own result before the first insertion.
	@Suppress("ReturnCount")
	private suspend fun appendContinuationFailure(
		storedReceipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		latest: StepsCountDomainOwnerRevisionEntity?,
	): StepsCountDomainAppendResult? {
		if (latest != null && latest.scopeIdentity != owner.scopeIdentity) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		val revisionIsValid = hasValidAppendRevision(owner, latest)
		if (!revisionIsValid) {
			return StepsCountDomainAppendResult.REVISION_GAP
		}
		if (storedReceipt != null && latest != null &&
			owner.ownerKind in FACT_OWNER_KINDS
		) {
			val previousReceipt = latest.receiptIdentity?.let { receipt(it) }
				?: return StepsCountDomainAppendResult.IDENTITY_CONFLICT
			if (!storedReceipt.hasSameImmutableDomain(previousReceipt)) {
				return StepsCountDomainAppendResult.IDENTITY_CONFLICT
			}
		}
		return null
	}

	private suspend fun persistAppend(
		storedReceipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		marker: StepsCountDomainCompletenessMarkerEntity?,
	): StepsCountDomainAppendResult {
		if (storedReceipt != null && insertReceipt(storedReceipt) < 0L &&
			receipt(storedReceipt.receiptIdentity) != storedReceipt
		) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		if (insertOwner(owner) < 0L) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		if (marker != null &&
			insertCompletenessMarker(marker) < 0L
		) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		return StepsCountDomainAppendResult.INSERTED
	}

	private companion object {
		val FACT_OWNER_KINDS = setOf(
			StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
			StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
		)
	}
}

// Fixed correspondence of supplied owner, optional receipt and optional completeness marker only.
// Early false exits keep the complete nullable-record shape in its original order.
@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
private fun hasCoherentAppendShape(
	storedReceipt: StepsCountDomainReceiptEntity?,
	owner: StepsCountDomainOwnerRevisionEntity,
	marker: StepsCountDomainCompletenessMarkerEntity?,
): Boolean {
	if (storedReceipt == null) {
		if (owner.receiptIdentity != null) {
			return false
		}
	} else if (
		owner.receiptIdentity != storedReceipt.receiptIdentity ||
		storedReceipt.ownerKind != owner.ownerKind ||
		storedReceipt.scopeIdentity != owner.scopeIdentity ||
		storedReceipt.ownerIdentity != owner.ownerIdentity ||
		storedReceipt.ownerRevision != owner.ownerRevision ||
		storedReceipt.effectChecksum != owner.ownerEffectChecksum
	) {
		return false
	}
	if ((owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) !=
		(marker != null)
	) {
		return false
	}
	if (marker != null && (
		marker.ownerKind != owner.ownerKind ||
			marker.ownerIdentity != owner.ownerIdentity ||
			marker.ownerRevision != owner.ownerRevision ||
			owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			marker.terminalState !=
			StepsCountDomainCompletenessMarkerEntity.STATE_COMPLETE ||
			owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
			storedReceipt?.completionEvidenceChecksum !=
			marker.evidenceChecksum ||
			owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			marker.terminalState !=
			StepsCountDomainCompletenessMarkerEntity.STATE_UNPROVEN
		)
	) {
		return false
	}
	return true
}

private fun hasValidAppendRevision(
	owner: StepsCountDomainOwnerRevisionEntity,
	latest: StepsCountDomainOwnerRevisionEntity?,
): Boolean = when {
	latest == null &&
		owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> true
	owner.ownerKind ==
		StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS ->
		latest == null || owner.ownerRevision > latest.ownerRevision
	else -> owner.ownerRevision == (latest?.ownerRevision ?: 0L) + 1L
}

// Closed saved operation/owner-kind table; replay identity remains part of each terminal case.
@Suppress("CyclomaticComplexMethod")
private fun hasTerminalAppendOwner(
	latest: StepsCountDomainOwnerRevisionEntity?,
	exact: StepsCountDomainOwnerRevisionEntity?,
	owner: StepsCountDomainOwnerRevisionEntity,
): Boolean {
	if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
		exact != latest
	) {
		return true
	}
	if (latest?.ownerKind ==
		StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS &&
		latest.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_BIND &&
		exact != latest
	) {
		return true
	}
	// This saved UNPROVEN row has one complete five-field terminal exception.
	@Suppress("ComplexCondition")
	if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
		exact != latest &&
		owner.operation != StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
		!(
			owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT &&
				owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
			)
	) {
		return true
	}
	return false
}

private fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion
