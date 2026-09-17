package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest

@Entity(
	tableName = "source_caller_accepted_authority",
	primaryKeys = ["reference", "source_kind", "purpose"],
	indices = [
		Index(
			value = ["logical_tracking_id", "manifest_revision"],
			name = "idx_source_caller_authority_manifest",
		),
		Index(value = ["status"], name = "idx_source_caller_authority_status"),
	],
)
data class SourceCallerAcceptedAuthorityEntity(
	@ColumnInfo(name = "reference") val reference: String,
	@ColumnInfo(name = "format_version") val formatVersion: Int,
	@ColumnInfo(name = "origin") val origin: String,
	@ColumnInfo(name = "accepted_purpose") val acceptedPurpose: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "policy_revision") val policyRevision: Long,
	@ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "rollout_revision") val rolloutRevision: Long,
	@ColumnInfo(name = "execution_revision") val executionRevision: Long,
	@ColumnInfo(name = "owner_cas_token") val ownerCasToken: String,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "status") val status: String,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
	@ColumnInfo(name = "tombstoned_at_ms") val tombstonedAtMs: Long?,
	@ColumnInfo(name = "tombstone_reason") val tombstoneReason: String?,
	@ColumnInfo(name = "integrity_checksum") val integrityChecksum: String,
) {
	companion object {
		const val FORMAT_VERSION = 1
		const val STATUS_ACTIVE = "ACTIVE"
		const val STATUS_TOMBSTONED = "TOMBSTONED"
	}
}

object SourceCallerAcceptedAuthorityIntegrity {
	fun seal(rows: List<SourceCallerAcceptedAuthorityEntity>): List<SourceCallerAcceptedAuthorityEntity> {
		require(rows.isNotEmpty())
		val checksum = compute(rows)
		return rows.map { it.copy(integrityChecksum = checksum) }
	}

	fun isAuthentic(rows: List<SourceCallerAcceptedAuthorityEntity>): Boolean {
		if (rows.isEmpty()) return false
		val checksum = rows.first().integrityChecksum
		return checksum.isNotBlank() &&
			rows.all { it.integrityChecksum == checksum } &&
			compute(rows) == checksum
	}

	private fun compute(rows: List<SourceCallerAcceptedAuthorityEntity>): String {
		val canonical = rows.sortedWith(
			compareBy<SourceCallerAcceptedAuthorityEntity>(
				SourceCallerAcceptedAuthorityEntity::sourceKind,
			).thenBy(SourceCallerAcceptedAuthorityEntity::purpose),
		).joinToString("\u001f") { row ->
			listOf(
				row.reference,
				row.formatVersion,
				row.origin,
				row.acceptedPurpose,
				row.sourceKind,
				row.purpose,
				row.policyRevision,
				row.consentEpoch,
				row.collectedDataEpoch,
				row.rolloutRevision,
				row.executionRevision,
				row.ownerCasToken,
				row.logicalTrackingId.orEmpty(),
				row.manifestRevision ?: 0L,
				row.status,
				row.createdAtMs,
				row.tombstonedAtMs ?: -1L,
				row.tombstoneReason.orEmpty(),
			).joinToString("\u001e")
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}
