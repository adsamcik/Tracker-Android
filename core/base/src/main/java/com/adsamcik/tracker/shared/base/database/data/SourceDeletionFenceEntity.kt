package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.security.MessageDigest

/**
 * Durable, payload-free fence for one deleted source/purpose scope.
 *
 * The scope stores only a domain-separated digest. Projectors can deterministically derive the same
 * identity from immutable attribution, while the tombstone does not retain the deleted logical or
 * physical session identifiers. The fence is independent of writer id/version so mutation paths
 * can reject replay, backfill, or a later writer generation for the deleted scope. Each path must
 * still consult this authority in the same transaction as its destination write.
 */
@Entity(
	tableName = "source_deletion_fence",
	primaryKeys = ["source_kind", "purpose", "scope_kind", "scope_identity_digest"],
)
data class SourceDeletionFenceEntity(
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "scope_kind") val scopeKind: String,
	@ColumnInfo(name = "scope_identity_digest") val scopeIdentityDigest: String,
	@ColumnInfo(name = "fence_generation") val fenceGeneration: Long,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(sourceKind > 0) { "Deletion-fence source kind must be positive" }
		require(purpose.isNotBlank()) { "Deletion-fence purpose must not be blank" }
		require(scopeKind == SCOPE_LOGICAL_SERVICE_RUN) { "Unknown deletion-fence scope" }
		require(SHA_256_HEX.matches(scopeIdentityDigest)) {
			"Deletion-fence scope identity must be a lowercase SHA-256 digest"
		}
		require(fenceGeneration > 0L) { "Deletion-fence generation must be positive" }
		require(collectedDataEpoch >= 0L) { "Collected-data epoch cannot be negative" }
		require(deletedAtMs >= 0L) { "Deletion time cannot be negative" }
		require(
			effectChecksum == fenceChecksum(
				sourceKind = sourceKind,
				purpose = purpose,
				scopeKind = scopeKind,
				scopeIdentityDigest = scopeIdentityDigest,
				fenceGeneration = fenceGeneration,
				collectedDataEpoch = collectedDataEpoch,
				deletedAtMs = deletedAtMs,
			),
		) { "Deletion-fence checksum does not match its durable authority" }
	}

	/** Stable scope construction shared by mutation and read paths. */
	companion object {
		const val SCOPE_LOGICAL_SERVICE_RUN = "LOGICAL_SERVICE_RUN"

		private const val IDENTITY_DOMAIN = "tracker-source-deletion-scope-v1"
		private const val CHECKSUM_DOMAIN = "tracker-source-deletion-fence-v1"
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")

		/** Stable opaque identity for one immutable logical-session/physical-run pair. */
		fun logicalServiceRunIdentity(
			sourceKind: Int,
			purpose: String,
			logicalTrackingId: String,
			serviceRunId: String,
		): String {
			require(sourceKind > 0)
			require(purpose.isNotBlank())
			require(logicalTrackingId.isNotBlank())
			require(serviceRunId.isNotBlank())
			return digest(
				IDENTITY_DOMAIN,
				sourceKind.toString(),
				purpose,
				SCOPE_LOGICAL_SERVICE_RUN,
				logicalTrackingId,
				serviceRunId,
			)
		}

		/** Creates a validated fence for one immutable logical-session/physical-run pair. */
		fun createLogicalServiceRun(
			sourceKind: Int,
			purpose: String,
			logicalTrackingId: String,
			serviceRunId: String,
			fenceGeneration: Long,
			collectedDataEpoch: Long,
			deletedAtMs: Long,
		): SourceDeletionFenceEntity {
			val scopeIdentityDigest = logicalServiceRunIdentity(
				sourceKind = sourceKind,
				purpose = purpose,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
			)
			return SourceDeletionFenceEntity(
				sourceKind = sourceKind,
				purpose = purpose,
				scopeKind = SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = scopeIdentityDigest,
				fenceGeneration = fenceGeneration,
				collectedDataEpoch = collectedDataEpoch,
				deletedAtMs = deletedAtMs,
				effectChecksum = fenceChecksum(
					sourceKind = sourceKind,
					purpose = purpose,
					scopeKind = SCOPE_LOGICAL_SERVICE_RUN,
					scopeIdentityDigest = scopeIdentityDigest,
					fenceGeneration = fenceGeneration,
					collectedDataEpoch = collectedDataEpoch,
					deletedAtMs = deletedAtMs,
				),
			)
		}

		private fun fenceChecksum(
			sourceKind: Int,
			purpose: String,
			scopeKind: String,
			scopeIdentityDigest: String,
			fenceGeneration: Long,
			collectedDataEpoch: Long,
			deletedAtMs: Long,
		): String = digest(
			CHECKSUM_DOMAIN,
			sourceKind.toString(),
			purpose,
			scopeKind,
			scopeIdentityDigest,
			fenceGeneration.toString(),
			collectedDataEpoch.toString(),
			deletedAtMs.toString(),
		)

		private fun digest(vararg values: String): String {
			val canonical = values.joinToString(separator = "") { value ->
				"${value.length}:$value"
			}
			return MessageDigest.getInstance("SHA-256")
				.digest(canonical.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte -> "%02x".format(byte) }
		}
	}
}
