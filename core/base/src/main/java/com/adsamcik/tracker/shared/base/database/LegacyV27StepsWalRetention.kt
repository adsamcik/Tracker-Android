@file:Suppress("ComplexCondition", "LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import java.security.MessageDigest

/**
 * Immutable authentication snapshot for released-v27 zero-mask Steps WAL.
 *
 * Ordinary retention receives this only after exact drain completion. Full clear uses a separate
 * transaction-bound fence and never exposes its snapshot to the pruning deletion function.
 */
internal data class LegacyV27StepsWalRetentionAuthority(
	val cutoffAdmissionOrdinal: Long,
	val collectedDataEpoch: Long,
	val status: String,
	val suppressedOutboxCount: Long,
	val authenticationMode: LegacyV27StepsWalAuthenticationMode,
	val permitsPendingChecksum: Boolean,
)

internal enum class LegacyV27StepsWalAuthenticationMode {
	ORDINARY_RETENTION,
	COLLECTED_DATA_FULL_CLEAR,
}

/**
 * Transaction-bound authority for the full collected-data clear only.
 *
 * The app-owned durable marker and process admission barrier are established before AppDatabase
 * enters this transaction. This fence binds that outer authority to the exact operation and epoch
 * transition so the legacy escape hatch cannot be reused by ordinary retention.
 */
internal class LegacyV27StepsWalFullClearFence private constructor(
	val operationId: String,
	val oldCollectedDataEpoch: Long,
	val newCollectedDataEpoch: Long,
	val deletedAtMs: Long,
) {
	companion object {
		fun establish(
			database: SupportSQLiteDatabase,
			operationId: String,
			oldCollectedDataEpoch: Long,
			newCollectedDataEpoch: Long,
			deletedAtMs: Long,
		): LegacyV27StepsWalFullClearFence {
			requireLegacy(database.inTransaction())
			requireLegacy(operationId.isNotBlank())
			requireLegacy(oldCollectedDataEpoch >= 0L)
			requireLegacy(
				oldCollectedDataEpoch < Long.MAX_VALUE &&
					newCollectedDataEpoch == oldCollectedDataEpoch + 1L,
			)
			requireLegacy(deletedAtMs >= 0L)
			requireLegacy(
				database.scalarLegacyLong(
					"SELECT COUNT(*) FROM source_evidence_state WHERE id = 1 " +
						"AND collected_data_epoch = ?",
					arrayOf(oldCollectedDataEpoch),
				) == 1L,
			)
			requireLegacy(
				database.scalarLegacyLong(
					"SELECT COUNT(*) FROM collected_data_deletion_operation " +
						"WHERE operation_id = ?",
					arrayOf(operationId),
				) == 0L,
			)
			return LegacyV27StepsWalFullClearFence(
				operationId = operationId,
				oldCollectedDataEpoch = oldCollectedDataEpoch,
				newCollectedDataEpoch = newCollectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
		}
	}
}

internal fun SupportSQLiteDatabase.hasLegacyV27StepsWalForCollectedDataFullClear(): Boolean {
	requireLegacy(inTransaction())
	return scalarLegacyLong(
		"SELECT EXISTS(SELECT 1 FROM source_event_wal WHERE source_kind = ? " +
			"AND (authorization_purpose_eligibility_mask = 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer') LIMIT 1)",
		arrayOf(SourceDestinationOwnerEntity.SOURCE_STEPS),
	) == 1L
}

internal fun SupportSQLiteDatabase.requireLegacyV27StepsWalRetentionAuthority():
	LegacyV27StepsWalRetentionAuthority {
	val authority = requireLegacyV27StepsWalAuthority(
		LegacyV27StepsWalAuthenticationMode.ORDINARY_RETENTION,
	)
	val targets = requireLegacyProjectionDrainContract(authority)
	val permitsPendingChecksum = requireLegacyProjectionDrainCompletion(authority, targets)
	return authority.copy(permitsPendingChecksum = permitsPendingChecksum)
}

internal fun SupportSQLiteDatabase.authenticateLegacyV27StepsWalForCollectedDataFullClear(
	fence: LegacyV27StepsWalFullClearFence,
) {
	requireLegacy(inTransaction())
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_evidence_state WHERE id = 1 " +
				"AND collected_data_epoch = ?",
			arrayOf(fence.oldCollectedDataEpoch),
		) == 1L,
	)
	requireLegacy(
		fence.oldCollectedDataEpoch < Long.MAX_VALUE &&
			fence.newCollectedDataEpoch == fence.oldCollectedDataEpoch + 1L,
	)
	requireLegacy(fence.operationId.isNotBlank() && fence.deletedAtMs >= 0L)
	val maximumRowId = query(
		"SELECT MAX(rowid) FROM source_event_wal WHERE source_kind = ? " +
			"AND (authorization_purpose_eligibility_mask = 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer')",
		arrayOf(SourceDestinationOwnerEntity.SOURCE_STEPS),
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		val value = cursor.nullableLegacyLong(0)
		requireLegacy(!cursor.moveToNext())
		value
	} ?: return
	val authority = requireLegacyV27StepsWalAuthority(
		LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
	)
	requireLegacy(authority.collectedDataEpoch == fence.oldCollectedDataEpoch)
	val targets = requireLegacyProjectionDrainContract(authority)
	if (
		authority.status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE ||
		authority.status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL
	) {
		requireLegacyProjectionDrainCompletion(authority, targets)
	}
	var afterRowId: Long? = null
	while (true) {
		val lowerBoundRowId = afterRowId
		val page = query(
			"SELECT rowid, admission_ordinal FROM source_event_wal " +
				"WHERE (? IS NULL OR rowid > ?) AND rowid <= ? AND source_kind = ? " +
				"AND (authorization_purpose_eligibility_mask = 0 OR " +
				"typeof(authorization_purpose_eligibility_mask) != 'integer') " +
				"ORDER BY rowid LIMIT $LEGACY_FULL_CLEAR_PAGE_SIZE",
			arrayOf(
				lowerBoundRowId,
				lowerBoundRowId,
				maximumRowId,
				SourceDestinationOwnerEntity.SOURCE_STEPS,
			),
		).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					val rowId = cursor.requiredLegacyLong(0)
					val admissionOrdinal = cursor.requiredLegacyLong(1)
					requireLegacy(
						(lowerBoundRowId == null || rowId > lowerBoundRowId) &&
							rowId <= maximumRowId,
					)
					requireLegacy(admissionOrdinal > 0L)
					add(rowId to admissionOrdinal)
				}
			}
		}
		if (page.isEmpty()) break
		page.forEach { (_, admissionOrdinal) ->
			requireAuthenticatedLegacyV27StepsWal(admissionOrdinal, authority)
		}
		afterRowId = page.last().first
		if (page.size < LEGACY_FULL_CLEAR_PAGE_SIZE) break
	}
}

private fun SupportSQLiteDatabase.requireLegacyV27StepsWalAuthority(
	authenticationMode: LegacyV27StepsWalAuthenticationMode,
): LegacyV27StepsWalRetentionAuthority {
	query(
		"SELECT identity_hash FROM room_master_table WHERE id = ? ORDER BY rowid LIMIT 2",
		arrayOf(FINAL_V28_MARKER_ID),
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyText(0) == FINAL_V28_ASSEMBLY_ID)
		requireLegacy(!cursor.moveToNext())
	}

	val authority = query(
		"SELECT id, source_schema_version, contract_version, cutoff_admission_ordinal, " +
			"collected_data_epoch, status, owner_boot_id, owner_token, lease_generation, " +
			"lease_expires_elapsed_nanos, started_at_ms, completed_at_ms, " +
			"suppressed_outbox_count, failure_code " +
			"FROM legacy_v27_projection_drain ORDER BY rowid LIMIT 2",
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		val id = cursor.requiredLegacyLong("id")
		val sourceSchemaVersion = cursor.requiredLegacyLong("source_schema_version")
		val contractVersion = cursor.requiredLegacyLong("contract_version")
		val cutoff = cursor.requiredLegacyLong("cutoff_admission_ordinal")
		val epoch = cursor.requiredLegacyLong("collected_data_epoch")
		val status = cursor.requiredLegacyText("status")
		val ownerBootId = cursor.nullableLegacyText("owner_boot_id")
		val ownerToken = cursor.nullableLegacyText("owner_token")
		val leaseGeneration = cursor.requiredLegacyLong("lease_generation")
		val leaseExpires = cursor.nullableLegacyLong("lease_expires_elapsed_nanos")
		val startedAt = cursor.nullableLegacyLong("started_at_ms")
		val completedAt = cursor.nullableLegacyLong("completed_at_ms")
		val suppressedOutboxCount = cursor.requiredLegacyLong("suppressed_outbox_count")
		val failureCode = cursor.nullableLegacyText("failure_code")
		requireLegacy(!cursor.moveToNext())
		requireLegacy(id == LegacyV27ProjectionDrainEntity.SINGLETON_ID.toLong())
		requireLegacy(
			sourceSchemaVersion == LegacyV27ProjectionDrainEntity.SOURCE_SCHEMA_VERSION.toLong(),
		)
		requireLegacy(contractVersion == LegacyV27ProjectionDrainEntity.CONTRACT_VERSION.toLong())
		requireLegacy(cutoff >= 0L && epoch >= 0L)
		requireLegacy(suppressedOutboxCount >= 0L)
		requireLegacyDrainState(
			authenticationMode = authenticationMode,
			status = status,
			ownerBootId = ownerBootId,
			ownerToken = ownerToken,
			leaseGeneration = leaseGeneration,
			leaseExpires = leaseExpires,
			startedAt = startedAt,
			completedAt = completedAt,
			suppressedOutboxCount = suppressedOutboxCount,
			failureCode = failureCode,
		)
		LegacyV27StepsWalRetentionAuthority(
			cutoffAdmissionOrdinal = cutoff,
			collectedDataEpoch = epoch,
			status = status,
			suppressedOutboxCount = suppressedOutboxCount,
			authenticationMode = authenticationMode,
			permitsPendingChecksum =
				authenticationMode ==
					LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
		)
	}
	return authority
}

internal fun SupportSQLiteDatabase.requireAuthenticatedLegacyV27StepsWal(
	admissionOrdinal: Long,
	authority: LegacyV27StepsWalRetentionAuthority,
) {
	query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal = ? ORDER BY rowid LIMIT 2",
		arrayOf(admissionOrdinal),
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyLong("admission_ordinal") == admissionOrdinal)
		requireLegacy(
			cursor.requiredLegacyLong("source_kind") ==
				SourceDestinationOwnerEntity.SOURCE_STEPS.toLong(),
		)
		val eventId = cursor.requiredLegacyText("event_id")
		val sourceInstanceId = cursor.requiredLegacyText("source_instance_id")
		val registrationGeneration = cursor.requiredLegacyLong("registration_generation")
		val logicalTrackingId = cursor.nullableLegacyText("logical_tracking_id")
		val serviceRunId = cursor.nullableLegacyText("service_run_id")
		requireLegacy(eventId.isNotBlank() && sourceInstanceId.isNotBlank())
		requireLegacy(registrationGeneration > 0L)
		requireLegacy(cursor.requiredLegacyLong("source_sequence") >= 0L)
		requireLegacy(cursor.nullableLegacyLong("config_revision")?.let { it >= 0L } != false)
		requireLegacy(cursor.requiredLegacyLong("plan_attribution") >= 0L)
		requireLegacy(cursor.requiredLegacyText("clock_domain_id").isNotBlank())
		requireLegacy(cursor.requiredLegacyLong("observed_elapsed_nanos") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("received_elapsed_nanos") >= 0L)
		requireLegacy(cursor.nullableLegacyLong("wall_time_ms")?.let { it >= 0L } != false)
		requireLegacy(
			cursor.nullableLegacyLong("wall_time_uncertainty_ms")?.let { it >= 0L } != false,
		)
		requireLegacy(
			cursor.requiredLegacyLong("captured_collected_data_epoch") ==
				authority.collectedDataEpoch,
		)
		requireLegacy(cursor.requiredLegacyLong("acquired_at_ms") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("quality_flags") >= 0L)
		requireLegacy(
			cursor.nullableLegacyDouble("quality_confidence")?.let {
				it.isFinite() && it in 0.0..1.0
			} != false,
		)
		requireLegacy(cursor.requiredLegacyLong("payload_version") == LEGACY_V27_PAYLOAD_VERSION)
		requireLegacy(cursor.requiredLegacyLong("created_at_ms") >= 0L)
		requireLegacy(
			cursor.requiredLegacyLong("authorization_purpose_eligibility_mask") == 0L,
		)
		requireLegacy(admissionOrdinal <= authority.cutoffAdmissionOrdinal)
		requireLegacy((logicalTrackingId == null) == (serviceRunId == null))
		requireLegacy(logicalTrackingId?.isNotBlank() != false)
		requireLegacy(serviceRunId?.isNotBlank() != false)
		requireLegacy(cursor.nullableLegacyText("provider_dedup_key")?.isNotBlank() != false)
		LEGACY_V28_NULL_WAL_COLUMNS.forEach { column ->
			requireLegacy(cursor.isNull(cursor.getColumnIndexOrThrow(column)))
		}
		val integrityIdentity = cursor.requiredLegacyText("integrity_identity")
		requireLegacy(
			integrityIdentity == SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED ||
				(integrityIdentity == SourceEventWalEntity.LEGACY_PENDING_CHECKSUM &&
					authority.permitsPendingChecksum),
		)
		val payload = cursor.requiredLegacyBlob("payload")
		val payloadChecksum = cursor.requiredLegacyText("payload_checksum")
		requireLegacy(payload.isNotEmpty())
		requireLegacy(payloadChecksum.matches(LOWERCASE_SHA_256))
		requireLegacy(payload.sha256() == payloadChecksum)
		requireLegacy(!cursor.moveToNext())

		if (logicalTrackingId != null && serviceRunId != null) {
			requireLegacyTerminalRun(logicalTrackingId, serviceRunId)
		}
		requireNoLiveLegacyWalOwner(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			authenticationMode = authority.authenticationMode,
		)
	}
}

private fun requireLegacyDrainState(
	authenticationMode: LegacyV27StepsWalAuthenticationMode,
	status: String,
	ownerBootId: String?,
	ownerToken: String?,
	leaseGeneration: Long,
	leaseExpires: Long?,
	startedAt: Long?,
	completedAt: Long?,
	suppressedOutboxCount: Long,
	failureCode: String?,
) {
	when (status) {
		LegacyV27ProjectionDrainEntity.STATUS_PENDING -> {
			requireLegacy(
				authenticationMode ==
					LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
			)
			requireLegacy(ownerBootId == null && ownerToken == null && leaseGeneration == 0L)
			requireLegacy(leaseExpires == null && startedAt == null && completedAt == null)
			requireLegacy(suppressedOutboxCount == 0L && failureCode == null)
		}
		LegacyV27ProjectionDrainEntity.STATUS_RUNNING -> {
			requireLegacy(
				authenticationMode ==
					LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
			)
			requireLegacy(!ownerBootId.isNullOrBlank() && !ownerToken.isNullOrBlank())
			requireLegacy(leaseGeneration > 0L)
			requireLegacy(leaseExpires != null && leaseExpires > 0L)
			requireLegacy(startedAt != null && startedAt >= 0L)
			requireLegacy(completedAt == null && suppressedOutboxCount == 0L)
			requireLegacy(failureCode == null)
		}
		LegacyV27ProjectionDrainEntity.STATUS_FAILED_RETRYABLE -> {
			requireLegacy(
				authenticationMode ==
					LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
			)
			requireLegacy(ownerBootId == null && ownerToken == null && leaseGeneration > 0L)
			requireLegacy(leaseExpires == null)
			requireLegacy(startedAt != null && startedAt >= 0L)
			requireLegacy(completedAt == null && suppressedOutboxCount == 0L)
			requireLegacy(!failureCode.isNullOrBlank())
		}
		LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET -> {
			requireLegacy(
				authenticationMode ==
					LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
			)
			requireLegacy(ownerBootId == null && ownerToken == null && leaseExpires == null)
			requireLegacy(completedAt == null && suppressedOutboxCount == 0L)
			requireLegacy(
				(leaseGeneration == 0L && startedAt == null) ||
					(leaseGeneration > 0L && startedAt != null && startedAt >= 0L),
			)
			requireLegacy(failureCode == LEGACY_V27_UNSUPPORTED_PROJECTION)
		}
		LegacyV27ProjectionDrainEntity.STATUS_COMPLETE,
		LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL,
		-> {
			requireLegacy(ownerBootId == null && ownerToken == null && leaseExpires == null)
			requireLegacy(leaseGeneration > 0L)
			requireLegacy(startedAt != null && startedAt >= 0L)
			requireLegacy(completedAt != null && completedAt >= 0L)
			requireLegacy(
				(status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE &&
					failureCode == null) ||
					(status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL &&
						failureCode == LEGACY_V27_PARTIAL_RECOVERY),
			)
		}
		else -> legacyFailure()
	}
}

internal fun SupportSQLiteDatabase.deleteAuthenticatedLegacyV27StepsWal(
	admissionOrdinals: List<Long>,
	authority: LegacyV27StepsWalRetentionAuthority,
): Int {
	if (admissionOrdinals.isEmpty()) return 0
	requireLegacy(
		authority.authenticationMode ==
			LegacyV27StepsWalAuthenticationMode.ORDINARY_RETENTION,
	)
	requireLegacy(admissionOrdinals.size == admissionOrdinals.distinct().size)
	admissionOrdinals.forEach { ordinal ->
		requireAuthenticatedLegacyV27StepsWal(ordinal, authority)
	}
	val placeholders = admissionOrdinals.joinToString(",") { "?" }
	return compileStatement(
		"DELETE FROM source_event_wal WHERE source_kind = " +
			SourceDestinationOwnerEntity.SOURCE_STEPS +
			" AND authorization_purpose_eligibility_mask = 0 " +
			"AND admission_ordinal IN ($placeholders)",
	).use { statement ->
		admissionOrdinals.forEachIndexed { index, ordinal ->
			statement.bindLong(index + 1, ordinal)
		}
		statement.executeUpdateDelete()
	}.also { deleted ->
		requireLegacy(deleted == admissionOrdinals.size)
	}
}

private fun SupportSQLiteDatabase.requireLegacyProjectionDrainContract(
	authority: LegacyV27StepsWalRetentionAuthority,
): List<LegacyDrainTarget> {
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_evidence_state WHERE id = 1 " +
				"AND collected_data_epoch = ?",
			arrayOf(authority.collectedDataEpoch),
		) == 1L,
	)
	val targets = query(
		"SELECT projection_id, projection_version, initial_activation_ordinal, " +
			"initial_checkpoint_ordinal, required_through_ordinal, last_completed_ordinal, " +
			"retention_required, initial_registration_status, disposition, completed_at_ms, " +
			"failure_code FROM legacy_v27_projection_target " +
			"ORDER BY projection_id, projection_version",
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(
					LegacyDrainTarget(
						projectionId = cursor.requiredLegacyText("projection_id"),
						projectionVersion = cursor.requiredLegacyLong("projection_version"),
						initialActivationOrdinal =
							cursor.requiredLegacyLong("initial_activation_ordinal"),
						initialCheckpointOrdinal =
							cursor.requiredLegacyLong("initial_checkpoint_ordinal"),
						requiredThroughOrdinal =
							cursor.requiredLegacyLong("required_through_ordinal"),
						lastCompletedOrdinal =
							cursor.requiredLegacyLong("last_completed_ordinal"),
						retentionRequired = cursor.requiredLegacyLong("retention_required"),
						initialRegistrationStatus =
							cursor.requiredLegacyText("initial_registration_status"),
						disposition = cursor.requiredLegacyText("disposition"),
						completedAtMs = cursor.nullableLegacyLong("completed_at_ms"),
						failureCode = cursor.nullableLegacyText("failure_code"),
					),
				)
			}
		}
	}
	if (authority.status == LegacyV27ProjectionDrainEntity.STATUS_BLOCKED_UNSUPPORTED_TARGET) {
		requireLegacyBlockedUnsupportedProjectionDrainContract(authority, targets)
		return targets
	}
	requireLegacy(targets.size == LEGACY_V27_SUPPORTED_PROJECTIONS.size)
	requireLegacy(
		targets.map { it.projectionId }.toSet() == LEGACY_V27_SUPPORTED_PROJECTIONS.keys,
	)
	targets.forEach { target ->
		val allowedDispositions =
			requireNotNull(LEGACY_V27_ALL_SUPPORTED_PROJECTIONS[target.projectionId])
		requireLegacy(target.projectionVersion == 1L)
		requireLegacy(target.initialRegistrationStatus in LEGACY_V27_INITIAL_REGISTRATION_STATUSES)
		requireLegacy(target.initialActivationOrdinal > 0L)
		requireLegacy(target.initialActivationOrdinal - 1L <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.initialCheckpointOrdinal >= target.initialActivationOrdinal - 1L)
		requireLegacy(target.initialCheckpointOrdinal <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.requiredThroughOrdinal == authority.cutoffAdmissionOrdinal)
		requireLegacy(target.lastCompletedOrdinal >= target.initialActivationOrdinal - 1L)
		requireLegacy(target.lastCompletedOrdinal <= target.requiredThroughOrdinal)
		requireLegacy(target.retentionRequired in 0L..1L)
		requireLegacy(target.disposition in allowedDispositions)
		requireLegacy(target.failureCode?.isNotBlank() != false)
		if (target.disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
			requireLegacy(target.completedAtMs == null)
		} else {
			requireLegacy(target.lastCompletedOrdinal == target.requiredThroughOrdinal)
			requireLegacy(target.completedAtMs != null && target.completedAtMs >= 0L)
		}
		if (target.initialRegistrationStatus == "NOT_REGISTERED_AT_MIGRATION") {
			requireLegacy(target.initialActivationOrdinal == 1L)
			requireLegacy(target.retentionRequired == 1L)
		}
		if (
			authority.authenticationMode ==
			LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR
		) {
			requireLegacyProjectionRegistrationForFullClear(target)
		}
	}
	return targets
}

private fun SupportSQLiteDatabase.requireLegacyBlockedUnsupportedProjectionDrainContract(
	authority: LegacyV27StepsWalRetentionAuthority,
	targets: List<LegacyDrainTarget>,
) {
	requireLegacy(
		authority.authenticationMode ==
			LegacyV27StepsWalAuthenticationMode.COLLECTED_DATA_FULL_CLEAR,
	)
	val supportedIdentities =
		LEGACY_V27_SUPPORTED_PROJECTIONS.keys.map { it to LEGACY_V27_PROJECTION_VERSION }.toSet()
	val targetIdentities = targets.map { it.projectionId to it.projectionVersion }
	requireLegacy(targetIdentities.size == targetIdentities.toSet().size)
	requireLegacy(targetIdentities.toSet().containsAll(supportedIdentities))
	val minimumFrozenWalOrdinal = query(
		"SELECT MIN(admission_ordinal) FROM source_event_wal " +
			"WHERE admission_ordinal <= ?",
		arrayOf(authority.cutoffAdmissionOrdinal),
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		val value = cursor.nullableLegacyLong(0)
		requireLegacy(!cursor.moveToNext())
		value
	}
	requireLegacy(
		minimumFrozenWalOrdinal != null &&
			minimumFrozenWalOrdinal > 0L &&
			minimumFrozenWalOrdinal <= authority.cutoffAdmissionOrdinal,
	)
	val frozenWalPredecessor = (minimumFrozenWalOrdinal ?: legacyFailure()) - 1L
	var unsupportedTargetCount = 0
	targets.forEach { target ->
		val identity = target.projectionId to target.projectionVersion
		val isSupportedIdentity = identity in supportedIdentities
		requireLegacy(target.projectionId.isNotBlank() && target.projectionVersion > 0L)
		requireLegacy(target.initialActivationOrdinal > 0L)
		requireLegacy(target.initialActivationOrdinal - 1L <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.initialCheckpointOrdinal >= target.initialActivationOrdinal - 1L)
		requireLegacy(target.initialCheckpointOrdinal <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.requiredThroughOrdinal == authority.cutoffAdmissionOrdinal)
		requireLegacy(target.retentionRequired in 0L..1L)
		requireLegacy(target.completedAtMs == null)
		val expectedLastCompletedOrdinal = maxOf(
			if (identity == LEGACY_V27_EVENT_FRAME_IDENTITY) {
				target.initialActivationOrdinal - 1L
			} else {
				target.initialCheckpointOrdinal
			},
			frozenWalPredecessor,
		)
		requireLegacy(target.lastCompletedOrdinal == expectedLastCompletedOrdinal)
		if (isSupportedIdentity) {
			requireLegacy(
				target.initialRegistrationStatus in
					LEGACY_V27_BLOCKED_INITIAL_REGISTRATION_STATUSES,
			)
			requireLegacy(target.disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING)
			requireLegacy(target.failureCode == null)
		} else {
			unsupportedTargetCount += 1
			requireLegacy(
				target.initialRegistrationStatus in
					LEGACY_V27_REGISTERED_INITIAL_STATUSES,
			)
			requireLegacy(
				target.disposition ==
					LegacyV27ProjectionTargetEntity.DISPOSITION_BLOCKED_UNSUPPORTED,
			)
			requireLegacy(target.failureCode == LEGACY_V27_UNSUPPORTED_PROJECTION)
		}
		requireLegacyBlockedProjectionRegistration(target, isSupportedIdentity)
		requireLegacyBlockedProjectionCheckpoint(target)
	}
	requireLegacy(unsupportedTargetCount > 0)
}

private fun SupportSQLiteDatabase.requireLegacyBlockedProjectionRegistration(
	target: LegacyDrainTarget,
	isSupportedIdentity: Boolean,
) {
	query(
		"SELECT activation_ordinal, retention_required, status, created_at_ms " +
			"FROM source_projection_registration WHERE projection_id = ? " +
			"AND projection_version = ? ORDER BY rowid LIMIT 2",
		arrayOf(target.projectionId, target.projectionVersion),
	).use { cursor ->
		if (target.initialRegistrationStatus == LEGACY_V27_NOT_REGISTERED_AT_MIGRATION) {
			requireLegacy(isSupportedIdentity)
			requireLegacy(target.initialActivationOrdinal == 1L)
			requireLegacy(target.initialCheckpointOrdinal == 0L)
			requireLegacy(target.retentionRequired == 1L)
			requireLegacy(!cursor.moveToFirst())
			return
		}
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyLong("activation_ordinal") == target.initialActivationOrdinal)
		requireLegacy(cursor.requiredLegacyLong("retention_required") == target.retentionRequired)
		val expectedStatus =
			if (target.initialRegistrationStatus == LEGACY_V27_ACTIVE_REGISTRATION) {
				LEGACY_V27_PENDING_REGISTRATION
			} else {
				target.initialRegistrationStatus
			}
		requireLegacy(cursor.requiredLegacyText("status") == expectedStatus)
		requireLegacy(cursor.requiredLegacyLong("created_at_ms") >= 0L)
		requireLegacy(!cursor.moveToNext())
	}
}

private fun SupportSQLiteDatabase.requireLegacyBlockedProjectionCheckpoint(
	target: LegacyDrainTarget,
) {
	query(
		"SELECT contiguous_admission_ordinal, state_version, updated_at_ms " +
			"FROM source_projection_checkpoint WHERE projection_id = ? " +
			"AND projection_version = ? ORDER BY rowid LIMIT 2",
		arrayOf(target.projectionId, target.projectionVersion),
	).use { cursor ->
		if (!cursor.moveToFirst()) {
			requireLegacy(
				target.initialCheckpointOrdinal == target.initialActivationOrdinal - 1L,
			)
			return
		}
		requireLegacy(
			cursor.requiredLegacyLong("contiguous_admission_ordinal") ==
				target.initialCheckpointOrdinal,
		)
		requireLegacy(cursor.requiredLegacyLong("state_version") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("updated_at_ms") >= 0L)
		requireLegacy(!cursor.moveToNext())
	}
}

private fun SupportSQLiteDatabase.requireLegacyProjectionDrainCompletion(
	authority: LegacyV27StepsWalRetentionAuthority,
	targets: List<LegacyDrainTarget>,
): Boolean {
	requireLegacy(
		authority.status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE ||
			authority.status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL,
	)
	targets.forEach { target ->
		val allowedDispositions =
			requireNotNull(LEGACY_V27_SUPPORTED_PROJECTIONS[target.projectionId])
		requireLegacy(target.lastCompletedOrdinal == target.requiredThroughOrdinal)
		requireLegacy(target.disposition in allowedDispositions)
		requireLegacy(target.completedAtMs != null && target.completedAtMs >= 0L)
		requireLegacyProjectionRegistration(target)
		if (target.projectionId == "event-tracking-frame" ||
			target.projectionId == "location-domain"
		) {
			requireLegacy(
				(target.initialRegistrationStatus == "ACTIVE") ==
					(target.disposition !=
						LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT),
			)
		}
	}
	val partial = targets.any { target ->
		target.disposition in setOf(
			LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
			LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL,
		) || target.failureCode != null
	}
	requireLegacy(
		(authority.status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL) == partial,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_outbox " +
				"WHERE admission_ordinal <= ? AND delivered_at_ms IS NULL " +
				"AND terminal_disposition IS NULL",
			arrayOf(authority.cutoffAdmissionOrdinal),
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_outbox WHERE admission_ordinal <= ? " +
				"AND delivered_at_ms IS NULL AND terminal_disposition IN (" +
				"'SUPPRESSED_STALE_CONTROL', 'SUPPRESSED_UNWIRED_OUTPUT', " +
				"'SUPPRESSED_UNREGISTERED_OUTPUT', 'LOCATION_SHADOW_RETAINED', " +
				"'LOCATION_SHADOW_PARTIAL')",
			arrayOf(authority.cutoffAdmissionOrdinal),
		) == authority.suppressedOutboxCount,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_failure WHERE projection_version = 1 " +
				"AND projection_id IN ($LEGACY_V27_PROJECTION_ID_SQL) AND terminal = 0",
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_join_state WHERE projection_version = 1 " +
				"AND projection_id IN ($LEGACY_V27_PROJECTION_ID_SQL)",
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_checkpoint WHERE projection_version = 1 " +
				"AND projection_id IN ($LEGACY_V27_PROJECTION_ID_SQL)",
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_projection_registration WHERE projection_version = 1 " +
				"AND projection_id IN ($LEGACY_V27_PROJECTION_ID_SQL) AND status != 'RETIRED'",
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM source_product_projection_lane WHERE source_kind = ? " +
				"AND status = 'ACTIVE' AND activation_ordinal <= ?",
			arrayOf(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				authority.cutoffAdmissionOrdinal,
			),
		) == 0L,
	)
	val eventFrame = targets.single { it.projectionId == "event-tracking-frame" }
	return eventFrame.initialRegistrationStatus == "NOT_REGISTERED_AT_MIGRATION" &&
		eventFrame.initialActivationOrdinal == 1L &&
		eventFrame.retentionRequired == 1L &&
		eventFrame.disposition ==
			LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT &&
		eventFrame.completedAtMs != null &&
		eventFrame.failureCode == null
}

private fun SupportSQLiteDatabase.requireLegacyProjectionRegistration(
	target: LegacyDrainTarget,
) {
	query(
		"SELECT activation_ordinal, retention_required, status " +
			"FROM source_projection_registration WHERE projection_id = ? " +
			"AND projection_version = 1 ORDER BY rowid LIMIT 2",
		arrayOf(target.projectionId),
	).use { cursor ->
		if (target.initialRegistrationStatus == "NOT_REGISTERED_AT_MIGRATION") {
			requireLegacy(!cursor.moveToFirst())
			return
		}
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyLong("activation_ordinal") == target.initialActivationOrdinal)
		requireLegacy(cursor.requiredLegacyLong("retention_required") == target.retentionRequired)
		requireLegacy(cursor.requiredLegacyText("status") == "RETIRED")
		requireLegacy(!cursor.moveToNext())
	}
}

private fun SupportSQLiteDatabase.requireLegacyProjectionRegistrationForFullClear(
	target: LegacyDrainTarget,
) {
	query(
		"SELECT activation_ordinal, retention_required, status " +
			"FROM source_projection_registration WHERE projection_id = ? " +
			"AND projection_version = 1 ORDER BY rowid LIMIT 2",
		arrayOf(target.projectionId),
	).use { cursor ->
		if (target.initialRegistrationStatus == "NOT_REGISTERED_AT_MIGRATION") {
			requireLegacy(!cursor.moveToFirst())
			return
		}
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyLong("activation_ordinal") == target.initialActivationOrdinal)
		requireLegacy(cursor.requiredLegacyLong("retention_required") == target.retentionRequired)
		val expectedStatuses =
			if (target.disposition == LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING) {
				setOf("LEGACY_V27_PENDING")
			} else {
				setOf("LEGACY_V27_PENDING", "RETIRED")
			}
		requireLegacy(cursor.requiredLegacyText("status") in expectedStatuses)
		requireLegacy(!cursor.moveToNext())
	}
}

private fun SupportSQLiteDatabase.requireLegacyTerminalRun(
	logicalTrackingId: String,
	serviceRunId: String,
) {
	query(
		"SELECT service_run_id, logical_tracking_id, state, desired_plan_revision, " +
			"rollout_revision, foreground_capability_flags, started_at_ms, " +
			"started_elapsed_nanos, completed_at_ms, completion_reason, boot_id, " +
			"lease_generation, start_origin, desired_foreground_capability_flags, " +
			"applied_foreground_capability_flags, runtime_acknowledgement, runtime_failure_code, " +
			"run_revision, start_delivery_token, start_command_generation, " +
			"prepared_manifest_revision, prepared_intent_revision, android_delivery_state, " +
			"android_delivery_updated_at_ms, start_is_user_initiated, start_is_ambient, " +
			"session_segment_id, presentation_acknowledgement, presentation_acknowledged_at_ms " +
			"FROM source_service_run WHERE service_run_id = ? ORDER BY rowid LIMIT 2",
		arrayOf(serviceRunId),
	).use { cursor ->
		requireLegacy(cursor.moveToFirst())
		requireLegacy(cursor.requiredLegacyText("service_run_id") == serviceRunId)
		requireLegacy(cursor.requiredLegacyText("logical_tracking_id") == logicalTrackingId)
		val state = cursor.requiredLegacyText("state")
		requireLegacy(state in LEGACY_V27_TERMINAL_STATES)
		requireLegacy(cursor.requiredLegacyLong("desired_plan_revision") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("rollout_revision") >= 0L)
		val foregroundCapabilityFlags =
			cursor.requiredLegacyLong("foreground_capability_flags")
		requireLegacy(foregroundCapabilityFlags >= 0L)
		val startedAtMs = cursor.requiredLegacyLong("started_at_ms")
		requireLegacy(startedAtMs >= 0L)
		requireLegacy(cursor.requiredLegacyLong("started_elapsed_nanos") >= 0L)
		val completedAtMs = cursor.nullableLegacyLong("completed_at_ms")
		val completionReason = cursor.nullableLegacyText("completion_reason")
		requireLegacy(cursor.requiredLegacyText("boot_id") == "LEGACY_UNKNOWN")
		requireLegacy(cursor.requiredLegacyLong("lease_generation") == 0L)
		requireLegacy(cursor.requiredLegacyText("start_origin").isNotBlank())
		requireLegacy(
			cursor.requiredLegacyLong("desired_foreground_capability_flags") ==
				foregroundCapabilityFlags,
		)
		val appliedForegroundCapabilityFlags =
			cursor.nullableLegacyLong("applied_foreground_capability_flags")
		val runtimeAcknowledgement = cursor.requiredLegacyText("runtime_acknowledgement")
		val runtimeFailureCode = cursor.nullableLegacyText("runtime_failure_code")
		val runRevision = cursor.requiredLegacyLong("run_revision")
		val expectedPreMigrationAcknowledgement = when (state) {
			"FINALIZED" -> "PENDING"
			"CLOSED", "FAILED" -> "LEGACY_TERMINAL"
			else -> legacyFailure()
		}
		val migratedInterrupted =
			state == "FINALIZED" &&
				completedAtMs == null &&
				!completionReason.isNullOrBlank() &&
				runtimeAcknowledgement == "TERMINAL_FAILURE" &&
				runtimeFailureCode == V28_MIGRATION_INTERRUPTION_REASON &&
				runRevision == 1L &&
				appliedForegroundCapabilityFlags?.let { it >= 0L } != false
		val preMigrationTerminal =
			state in LEGACY_V27_PREMIGRATION_TERMINAL_STATES &&
				completedAtMs != null &&
				completedAtMs >= startedAtMs &&
				!completionReason.isNullOrBlank() &&
				appliedForegroundCapabilityFlags == null &&
				runtimeAcknowledgement == expectedPreMigrationAcknowledgement &&
				runtimeFailureCode == null &&
				runRevision == 0L
		requireLegacy(migratedInterrupted || preMigrationTerminal)
		requireLegacy(cursor.nullableLegacyText("start_delivery_token") == null)
		requireLegacy(cursor.requiredLegacyLong("start_command_generation") == 0L)
		requireLegacy(cursor.requiredLegacyLong("prepared_manifest_revision") == 0L)
		requireLegacy(cursor.requiredLegacyLong("prepared_intent_revision") == 0L)
		requireLegacy(cursor.requiredLegacyText("android_delivery_state") == "LEGACY_UNKNOWN")
		requireLegacy(cursor.nullableLegacyLong("android_delivery_updated_at_ms") == null)
		requireLegacy(cursor.requiredLegacyLong("start_is_user_initiated") == 0L)
		requireLegacy(cursor.requiredLegacyLong("start_is_ambient") == 0L)
		requireLegacy(cursor.nullableLegacyLong("session_segment_id") == null)
		requireLegacy(
			cursor.requiredLegacyText("presentation_acknowledgement") == "LEGACY_UNVERIFIABLE",
		)
		requireLegacy(cursor.nullableLegacyLong("presentation_acknowledged_at_ms") == null)
		requireLegacy(!cursor.moveToNext())
	}
}

private fun SupportSQLiteDatabase.requireNoLiveLegacyWalOwner(
	admissionOrdinal: Long,
	eventId: String,
	sourceInstanceId: String,
	registrationGeneration: Long,
	authenticationMode: LegacyV27StepsWalAuthenticationMode,
) {
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM provider_registration_generation WHERE source_kind = ? " +
				"AND source_instance_id = ? AND registration_generation = ? " +
				"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING')",
			arrayOf(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				sourceInstanceId,
				registrationGeneration,
			),
		) == 0L,
	)
	if (authenticationMode == LegacyV27StepsWalAuthenticationMode.ORDINARY_RETENTION) {
		requireLegacy(
			scalarLegacyLong(
				"SELECT COUNT(*) FROM source_projection_outbox WHERE admission_ordinal = ? " +
					"AND delivered_at_ms IS NULL AND terminal_disposition IS NULL",
				arrayOf(admissionOrdinal),
			) == 0L,
		)
		requireLegacy(
			scalarLegacyLong(
				"SELECT COUNT(*) FROM source_projection_failure WHERE admission_ordinal = ? " +
					"AND terminal = 0",
				arrayOf(admissionOrdinal),
			) == 0L,
		)
	}
	val ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
		admissionOrdinal,
		eventId,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM steps_count_domain_owner_revision " +
				"WHERE owner_kind = ? AND owner_identity = ?",
			arrayOf(StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL, ownerIdentity),
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM steps_count_domain_receipt " +
				"WHERE owner_kind = ? AND owner_identity = ?",
			arrayOf(StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL, ownerIdentity),
		) == 0L,
	)
	requireLegacy(
		scalarLegacyLong(
			"SELECT COUNT(*) FROM steps_count_domain_completeness_marker " +
				"WHERE owner_kind = ? AND owner_identity = ?",
			arrayOf(StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL, ownerIdentity),
		) == 0L,
	)
}

private fun SupportSQLiteDatabase.scalarLegacyLong(
	sql: String,
	args: Array<out Any?> = emptyArray(),
): Long = query(sql, args).use { cursor ->
	requireLegacy(cursor.moveToFirst())
	val value = cursor.requiredLegacyLong(0)
	requireLegacy(!cursor.moveToNext())
	value
}

private fun Cursor.requiredLegacyLong(column: String): Long =
	requiredLegacyLong(getColumnIndexOrThrow(column))

private fun Cursor.requiredLegacyLong(index: Int): Long {
	requireLegacy(getType(index) == Cursor.FIELD_TYPE_INTEGER)
	return getLong(index)
}

private fun Cursor.requiredLegacyText(column: String): String =
	requiredLegacyText(getColumnIndexOrThrow(column))

private fun Cursor.requiredLegacyText(index: Int): String {
	requireLegacy(getType(index) == Cursor.FIELD_TYPE_STRING)
	return requireNotNull(getString(index))
}

private fun Cursor.requiredLegacyBlob(column: String): ByteArray {
	val index = getColumnIndexOrThrow(column)
	requireLegacy(getType(index) == Cursor.FIELD_TYPE_BLOB)
	return requireNotNull(getBlob(index))
}

private fun Cursor.nullableLegacyLong(column: String): Long? {
	return nullableLegacyLong(getColumnIndexOrThrow(column))
}

private fun Cursor.nullableLegacyLong(index: Int): Long? =
	when (getType(index)) {
		Cursor.FIELD_TYPE_NULL -> null
		Cursor.FIELD_TYPE_INTEGER -> getLong(index)
		else -> legacyFailure()
	}

private fun Cursor.nullableLegacyText(column: String): String? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		Cursor.FIELD_TYPE_NULL -> null
		Cursor.FIELD_TYPE_STRING -> getString(index)
		else -> legacyFailure()
	}
}

private fun Cursor.nullableLegacyDouble(column: String): Double? {
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		Cursor.FIELD_TYPE_NULL -> null
		Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
		else -> legacyFailure()
	}
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
	.digest(this)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun requireLegacy(condition: Boolean) {
	if (!condition) legacyFailure()
}

private fun legacyFailure(): Nothing =
	throw IllegalStateException("Released-v27 Steps WAL authentication evidence is unverifiable")

private data class LegacyDrainTarget(
	val projectionId: String,
	val projectionVersion: Long,
	val initialActivationOrdinal: Long,
	val initialCheckpointOrdinal: Long,
	val requiredThroughOrdinal: Long,
	val lastCompletedOrdinal: Long,
	val retentionRequired: Long,
	val initialRegistrationStatus: String,
	val disposition: String,
	val completedAtMs: Long?,
	val failureCode: String?,
)

private const val LEGACY_V27_PAYLOAD_VERSION = 1L
private const val LEGACY_V27_PARTIAL_RECOVERY = "LEGACY_V27_RECOVERY_PARTIAL"
private const val LEGACY_V27_UNSUPPORTED_PROJECTION = "UNSUPPORTED_LEGACY_PROJECTION"
private const val LEGACY_V27_PROJECTION_VERSION = 1L
private const val LEGACY_V27_ACTIVE_REGISTRATION = "ACTIVE"
private const val LEGACY_V27_RETIRED_REGISTRATION = "RETIRED"
private const val LEGACY_V27_PENDING_REGISTRATION = "LEGACY_V27_PENDING"
private const val LEGACY_V27_NOT_REGISTERED_AT_MIGRATION = "NOT_REGISTERED_AT_MIGRATION"
private const val LEGACY_V27_PROJECTION_ID_SQL =
	"'activity-automation', 'event-tracking-frame', 'explicit-tracking-joins', 'location-domain'"
private const val LEGACY_FULL_CLEAR_PAGE_SIZE = 128
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
private val LEGACY_V28_NULL_WAL_COLUMNS = listOf(
	"delivery_identity",
	"delivery_unit_index",
	"delivery_unit_count",
	"physical_configuration_fingerprint",
	"authorization_revision",
	"authorization_fingerprint",
	"observed_interval_start_nanos",
	"received_wall_time_ms",
	"activity_automation_epoch",
	"source_policy_revision",
	"capture_consent_epoch",
	"session_manifest_revision",
	"lifecycle_lease_generation",
)
private val LEGACY_V27_INITIAL_REGISTRATION_STATUSES =
	setOf(LEGACY_V27_ACTIVE_REGISTRATION, LEGACY_V27_NOT_REGISTERED_AT_MIGRATION)
private val LEGACY_V27_REGISTERED_INITIAL_STATUSES =
	setOf(LEGACY_V27_ACTIVE_REGISTRATION, LEGACY_V27_RETIRED_REGISTRATION)
private val LEGACY_V27_BLOCKED_INITIAL_REGISTRATION_STATUSES =
	LEGACY_V27_REGISTERED_INITIAL_STATUSES + LEGACY_V27_NOT_REGISTERED_AT_MIGRATION
private val LEGACY_V27_PREMIGRATION_TERMINAL_STATES = setOf("CLOSED", "FAILED", "FINALIZED")
private val LEGACY_V27_TERMINAL_STATES =
	LEGACY_V27_PREMIGRATION_TERMINAL_STATES
private val LEGACY_V27_EVENT_FRAME_IDENTITY =
	"event-tracking-frame" to LEGACY_V27_PROJECTION_VERSION
private val LEGACY_V27_SUPPORTED_PROJECTIONS = mapOf(
	"activity-automation" to setOf(
		LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_STALE_CONTROL,
	),
	"event-tracking-frame" to setOf(
		LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS,
		LegacyV27ProjectionTargetEntity.DISPOSITION_BRIDGED_TYPED_FACTS_PARTIAL,
		LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
	),
	"explicit-tracking-joins" to setOf(
		LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNWIRED_OUTPUT,
	),
	"location-domain" to setOf(
		LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_RETAINED,
		LegacyV27ProjectionTargetEntity.DISPOSITION_LOCATION_SHADOW_PARTIAL,
		LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT,
	),
)
private val LEGACY_V27_ALL_SUPPORTED_PROJECTIONS =
	LEGACY_V27_SUPPORTED_PROJECTIONS.mapValues { (_, dispositions) ->
		dispositions + LegacyV27ProjectionTargetEntity.DISPOSITION_PENDING
	}
