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
 * Immutable authority for removing released-v27 Steps WAL after its compatibility drain finished.
 *
 * The migration deliberately leaves purpose eligibility at zero. This authority recognizes that
 * frozen legacy shape without converting it into v28 capture authority or count-domain ownership.
 */
internal data class LegacyV27StepsWalRetentionAuthority(
	val cutoffAdmissionOrdinal: Long,
	val collectedDataEpoch: Long,
	val status: String,
	val suppressedOutboxCount: Long,
)

internal fun SupportSQLiteDatabase.requireLegacyV27StepsWalRetentionAuthority():
	LegacyV27StepsWalRetentionAuthority {
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
		requireLegacy(
			status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE ||
				status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL,
		)
		requireLegacy(ownerBootId == null && ownerToken == null && leaseExpires == null)
		requireLegacy(leaseGeneration > 0L)
		requireLegacy(startedAt != null && startedAt >= 0L)
		requireLegacy(completedAt != null && completedAt >= 0L)
		requireLegacy(suppressedOutboxCount >= 0L)
		requireLegacy(
			(status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE && failureCode == null) ||
				(status == LegacyV27ProjectionDrainEntity.STATUS_COMPLETE_PARTIAL &&
					failureCode == LEGACY_V27_PARTIAL_RECOVERY),
		)
		LegacyV27StepsWalRetentionAuthority(cutoff, epoch, status, suppressedOutboxCount)
	}
	requireLegacyProjectionDrainCompletion(authority)
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
		val logicalTrackingId = cursor.requiredLegacyText("logical_tracking_id")
		val serviceRunId = cursor.requiredLegacyText("service_run_id")
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
		requireLegacy(logicalTrackingId.isNotBlank() && serviceRunId.isNotBlank())
		requireLegacy(cursor.nullableLegacyText("provider_dedup_key")?.isNotBlank() != false)
		LEGACY_V28_NULL_WAL_COLUMNS.forEach { column ->
			requireLegacy(cursor.isNull(cursor.getColumnIndexOrThrow(column)))
		}
		requireLegacy(
			cursor.requiredLegacyText("integrity_identity") ==
				SourceEventWalEntity.LEGACY_CHECKSUM_VERIFIED,
		)
		val payload = cursor.requiredLegacyBlob("payload")
		val payloadChecksum = cursor.requiredLegacyText("payload_checksum")
		requireLegacy(payload.isNotEmpty())
		requireLegacy(payloadChecksum.matches(LOWERCASE_SHA_256))
		requireLegacy(payload.sha256() == payloadChecksum)
		requireLegacy(!cursor.moveToNext())

		requireMigratedTerminalRun(logicalTrackingId, serviceRunId)
		requireNoLiveLegacyWalOwner(
			admissionOrdinal = admissionOrdinal,
			eventId = eventId,
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
		)
	}
}

internal fun SupportSQLiteDatabase.deleteAuthenticatedLegacyV27StepsWal(
	admissionOrdinals: List<Long>,
	authority: LegacyV27StepsWalRetentionAuthority,
): Int {
	if (admissionOrdinals.isEmpty()) return 0
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

private fun SupportSQLiteDatabase.requireLegacyProjectionDrainCompletion(
	authority: LegacyV27StepsWalRetentionAuthority,
) {
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
	requireLegacy(targets.size == LEGACY_V27_SUPPORTED_PROJECTIONS.size)
	requireLegacy(
		targets.map { it.projectionId }.toSet() == LEGACY_V27_SUPPORTED_PROJECTIONS.keys,
	)
	targets.forEach { target ->
		val allowedDispositions =
			requireNotNull(LEGACY_V27_SUPPORTED_PROJECTIONS[target.projectionId])
		requireLegacy(target.projectionVersion == 1L)
		requireLegacy(target.initialRegistrationStatus in LEGACY_V27_INITIAL_REGISTRATION_STATUSES)
		requireLegacy(target.initialActivationOrdinal > 0L)
		requireLegacy(target.initialActivationOrdinal - 1L <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.initialCheckpointOrdinal >= target.initialActivationOrdinal - 1L)
		requireLegacy(target.initialCheckpointOrdinal <= authority.cutoffAdmissionOrdinal)
		requireLegacy(target.requiredThroughOrdinal == authority.cutoffAdmissionOrdinal)
		requireLegacy(target.lastCompletedOrdinal == target.requiredThroughOrdinal)
		requireLegacy(target.retentionRequired in 0L..1L)
		requireLegacy(target.disposition in allowedDispositions)
		requireLegacy(target.completedAtMs != null && target.completedAtMs >= 0L)
		requireLegacy(target.failureCode?.isNotBlank() != false)
		requireLegacyProjectionRegistration(target)
		if (target.projectionId == "event-tracking-frame") {
			requireLegacy(
				(target.initialRegistrationStatus == "ACTIVE") ==
					(target.disposition !=
						LegacyV27ProjectionTargetEntity.DISPOSITION_SUPPRESSED_UNREGISTERED_OUTPUT),
			)
		}
		if (target.projectionId == "location-domain") {
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

private fun SupportSQLiteDatabase.requireMigratedTerminalRun(
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
		requireLegacy(cursor.requiredLegacyText("state") == "FINALIZED")
		requireLegacy(cursor.requiredLegacyLong("desired_plan_revision") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("rollout_revision") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("foreground_capability_flags") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("started_at_ms") >= 0L)
		requireLegacy(cursor.requiredLegacyLong("started_elapsed_nanos") >= 0L)
		requireLegacy(cursor.nullableLegacyLong("completed_at_ms") == null)
		requireLegacy(!cursor.nullableLegacyText("completion_reason").isNullOrBlank())
		requireLegacy(cursor.requiredLegacyText("boot_id") == "LEGACY_UNKNOWN")
		requireLegacy(cursor.requiredLegacyLong("lease_generation") == 0L)
		requireLegacy(cursor.requiredLegacyText("start_origin").isNotBlank())
		requireLegacy(
			cursor.requiredLegacyLong("desired_foreground_capability_flags") ==
				cursor.requiredLegacyLong("foreground_capability_flags"),
		)
		requireLegacy(
			cursor.nullableLegacyLong("applied_foreground_capability_flags")
				?.let { it >= 0L } != false,
		)
		requireLegacy(cursor.requiredLegacyText("runtime_acknowledgement") == "TERMINAL_FAILURE")
		requireLegacy(
			cursor.requiredLegacyText("runtime_failure_code") ==
				V28_MIGRATION_INTERRUPTION_REASON,
		)
		requireLegacy(cursor.requiredLegacyLong("run_revision") == 1L)
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
	val index = getColumnIndexOrThrow(column)
	return when (getType(index)) {
		Cursor.FIELD_TYPE_NULL -> null
		Cursor.FIELD_TYPE_INTEGER -> getLong(index)
		else -> legacyFailure()
	}
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
	throw IllegalStateException("Released-v27 Steps WAL retention evidence is unverifiable")

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
private const val LEGACY_V27_PROJECTION_ID_SQL =
	"'activity-automation', 'event-tracking-frame', 'explicit-tracking-joins', 'location-domain'"
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
	setOf("ACTIVE", "NOT_REGISTERED_AT_MIGRATION")
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
