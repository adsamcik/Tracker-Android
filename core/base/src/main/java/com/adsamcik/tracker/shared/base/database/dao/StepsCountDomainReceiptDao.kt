package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity

enum class StepsCountDomainAppendResult {
	INSERTED,
	EXACT_REPLAY,
	IDENTITY_CONFLICT,
	REVISION_GAP,
	TERMINAL_OWNER,
}

/** Source-specific Room contract; AppDatabase registration belongs to serialized schema assembly. */
@Dao
interface StepsCountDomainReceiptDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReceipt(receipt: StepsCountDomainReceiptEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertOwner(owner: StepsCountDomainOwnerRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCompletenessMarker(
		marker: StepsCountDomainCompletenessMarkerEntity,
	): Long

	@Query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity = :identity LIMIT 1",
	)
	suspend fun receipt(identity: String): StepsCountDomainReceiptEntity?

	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = :ownerKind " +
			"AND owner_identity = :ownerIdentity AND owner_revision = :ownerRevision LIMIT 1",
	)
	suspend fun owner(
		ownerKind: String,
		ownerIdentity: String,
		ownerRevision: Long,
	): StepsCountDomainOwnerRevisionEntity?

	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_kind = :ownerKind " +
			"AND owner_identity = :ownerIdentity ORDER BY owner_revision DESC LIMIT 1",
	)
	suspend fun latestOwner(
		ownerKind: String,
		ownerIdentity: String,
	): StepsCountDomainOwnerRevisionEntity?

	@Query(
		"SELECT * FROM steps_count_domain_owner_revision WHERE owner_identity IN (:ownerIdentities) " +
			"ORDER BY owner_kind, owner_identity, owner_revision LIMIT :limit",
	)
	suspend fun owners(
		ownerIdentities: List<String>,
		limit: Int,
	): List<StepsCountDomainOwnerRevisionEntity>

	@Query(
		"SELECT * FROM steps_count_domain_receipt WHERE receipt_identity IN (:receiptIdentities) " +
			"ORDER BY receipt_identity LIMIT :limit",
	)
	suspend fun receipts(
		receiptIdentities: List<String>,
		limit: Int,
	): List<StepsCountDomainReceiptEntity>

	@Query(
		"SELECT * FROM steps_count_domain_completeness_marker WHERE owner_identity = :ownerIdentity " +
			"AND owner_revision = :ownerRevision LIMIT 1",
	)
	suspend fun completenessMarker(
		ownerIdentity: String,
		ownerRevision: Long,
	): StepsCountDomainCompletenessMarkerEntity?

	@Transaction
	suspend fun append(
		storedReceipt: StepsCountDomainReceiptEntity?,
		owner: StepsCountDomainOwnerRevisionEntity,
		marker: StepsCountDomainCompletenessMarkerEntity? = null,
	): StepsCountDomainAppendResult {
		if (storedReceipt == null) {
			if (owner.receiptIdentity != null) {
				return StepsCountDomainAppendResult.IDENTITY_CONFLICT
			}
		} else if (
			owner.receiptIdentity != storedReceipt.receiptIdentity ||
			storedReceipt.ownerKind != owner.ownerKind ||
			storedReceipt.scopeIdentity != owner.scopeIdentity ||
			storedReceipt.ownerIdentity != owner.ownerIdentity ||
			storedReceipt.ownerRevision != owner.ownerRevision ||
			storedReceipt.effectChecksum != owner.ownerEffectChecksum
		) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		if ((owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS) !=
			(marker != null)
		) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
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
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}

		val latest = latestOwner(owner.ownerKind, owner.ownerIdentity)
		val exact = owner(owner.ownerKind, owner.ownerIdentity, owner.ownerRevision)
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			exact != latest
		) {
			return StepsCountDomainAppendResult.TERMINAL_OWNER
		}
		if (latest?.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN &&
			exact != latest &&
			owner.operation != StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT &&
			!(
				owner.ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT &&
					owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
				)
		) {
			return StepsCountDomainAppendResult.TERMINAL_OWNER
		}
		if (exact != null) {
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
		if (latest != null && latest.scopeIdentity != owner.scopeIdentity) {
			return StepsCountDomainAppendResult.IDENTITY_CONFLICT
		}
		val revisionIsValid = when {
			latest == null &&
				owner.operation == StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN -> true
			owner.ownerKind ==
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_COMPLETENESS ->
				latest == null || owner.ownerRevision > latest.ownerRevision
			else -> owner.ownerRevision == (latest?.ownerRevision ?: 0L) + 1L
		}
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

private fun StepsCountDomainReceiptEntity.hasSameImmutableDomain(
	other: StepsCountDomainReceiptEntity,
): Boolean = domainIdentity == other.domainIdentity &&
	collectedDataEpoch == other.collectedDataEpoch &&
	countDomainVersion == other.countDomainVersion
