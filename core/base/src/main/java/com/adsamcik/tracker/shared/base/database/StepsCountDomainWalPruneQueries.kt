package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceEventRetentionProofCandidateRow
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity

internal data class StepsWalPruneCursor(
	val createdAtMs: Long,
	val admissionOrdinal: Long,
) : Comparable<StepsWalPruneCursor> {
	override fun compareTo(other: StepsWalPruneCursor): Int =
		compareValuesBy(
			this,
			other,
			StepsWalPruneCursor::createdAtMs,
			StepsWalPruneCursor::admissionOrdinal,
		)
}

internal data class StepsWalPruneCandidate(
	val cursor: StepsWalPruneCursor,
	val retentionProofAuthority: SourceEventRetentionProofCandidateRow,
	val ownerKey: StepsCountDomainOwnerLookupKey?,
	val ownerlessRunKey: OwnerlessStepsManifestRunKey?,
) {
	val eventId: String get() = retentionProofAuthority.eventId
	val collectedDataEpoch: Long get() = retentionProofAuthority.collectedDataEpoch
	val authorizationPurposeEligibilityMask: Long
		get() = retentionProofAuthority.purposeEligibilityMask
	val isLegacyV27: Boolean
		get() = authorizationPurposeEligibilityMask == 0L
}


internal fun SupportSQLiteDatabase.querySessionWalPruneOwnerPage(
	safeOrdinal: Long,
	createdBeforeMs: Long,
	after: StepsWalPruneCursor?,
	limit: Int,
): List<StepsWalPruneCandidate> {
	val cursorPredicate = if (after == null) {
		""
	} else {
		"AND (wal.created_at_ms > ? OR " +
			"(wal.created_at_ms = ? AND wal.admission_ordinal > ?)) "
	}
	val arguments = buildList<Any?> {
		add(SourceDestinationOwnerEntity.SOURCE_STEPS)
		add(createdBeforeMs)
		add(safeOrdinal)
		if (after != null) {
			add(after.createdAtMs)
			add(after.createdAtMs)
			add(after.admissionOrdinal)
		}
		add(SourceBrokerPurpose.MASK_SESSION_CAPTURE)
		add(limit)
	}.toTypedArray()
	return query(
		STEPS_WAL_PRUNE_SELECT + cursorPredicate + STEPS_WAL_PRUNE_SCOPE,
		arguments,
	).use { cursor ->
		buildList {
			while (cursor.moveToNext()) {
				add(cursor.toStepsWalPruneCandidate(after))
			}
		}
	}
}

private fun android.database.Cursor.toStepsWalPruneCandidate(after: StepsWalPruneCursor?): StepsWalPruneCandidate {
	val admissionOrdinal = requiredLong(0)
	val eventId = requiredText(1)
	val candidateCursor = StepsWalPruneCursor(requiredLong(2), admissionOrdinal)
	val collectedDataEpoch = requiredLong(3)
	val eligibilityMask = requiredLong(4)
	val logicalTrackingId = nullableText("logical_tracking_id")
	val serviceRunId = nullableText("service_run_id")
	val ownerlessRunKey = ownerlessPruneRunKey(eligibilityMask, logicalTrackingId, serviceRunId)
	if (admissionOrdinal <= 0L || eventId.isBlank() || (after != null && candidateCursor <= after)) {
		throw CountDomainStoredEvidenceException()
	}
	return StepsWalPruneCandidate(
		cursor = candidateCursor,
		retentionProofAuthority = toStepsWalRetentionProof(
			candidateCursor, eventId, eligibilityMask, collectedDataEpoch, logicalTrackingId, serviceRunId,
		),
		ownerKey = if (eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
			StepsCountDomainOwnerLookupKey(
				StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
				StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(admissionOrdinal, eventId),
				1L,
			)
		} else {
			null
		},
		ownerlessRunKey = ownerlessRunKey,
	)
}

private fun ownerlessPruneRunKey(
	eligibilityMask: Long,
	logicalTrackingId: String?,
	serviceRunId: String?,
): OwnerlessStepsManifestRunKey? {
	return if (eligibilityMask != 0L && eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) {
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			throw CountDomainStoredEvidenceException()
		}
		OwnerlessStepsManifestRunKey(logicalTrackingId, serviceRunId)
	} else {
		if (eligibilityMask != 0L && (logicalTrackingId != null || serviceRunId != null)) {
			throw CountDomainStoredEvidenceException()
		}
		null
	}
}

private fun android.database.Cursor.toStepsWalRetentionProof(
	candidateCursor: StepsWalPruneCursor,
	eventId: String,
	eligibilityMask: Long,
	collectedDataEpoch: Long,
	logicalTrackingId: String?,
	serviceRunId: String?,
): SourceEventRetentionProofCandidateRow = SourceEventRetentionProofCandidateRow(
	sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
	eventId = eventId,
	sourceAdmissionOrdinal = candidateCursor.admissionOrdinal,
	purposeEligibilityMask = eligibilityMask,
	sourcePolicyRevision = nullableLong("source_policy_revision"),
	captureConsentEpoch = nullableLong("capture_consent_epoch"),
	collectedDataEpoch = collectedDataEpoch,
	logicalTrackingId = logicalTrackingId,
	serviceRunId = serviceRunId,
	clockDomainId = requiredText(getColumnIndexOrThrow("clock_domain_id")),
	sessionManifestRevision = nullableLong("session_manifest_revision"),
	lifecycleLeaseGeneration = nullableLong("lifecycle_lease_generation"),
	sourceInstanceId = requiredText(getColumnIndexOrThrow("source_instance_id")),
	registrationGeneration = requiredLong(getColumnIndexOrThrow("registration_generation")),
	physicalConfigurationFingerprint = nullableText("physical_configuration_fingerprint"),
	authorizationRevision = nullableLong("authorization_revision"),
	authorizationFingerprint = nullableText("authorization_fingerprint"),
	configurationRevision = nullableLong("config_revision"),
	walPayloadChecksum = requiredText(getColumnIndexOrThrow("payload_checksum")),
	walIntegrityIdentity = requiredText(getColumnIndexOrThrow("integrity_identity")),
)

private const val STEPS_WAL_PRUNE_SELECT =
	"SELECT wal.admission_ordinal, wal.event_id, wal.created_at_ms, " +
		"wal.captured_collected_data_epoch, wal.authorization_purpose_eligibility_mask, " +
		"wal.logical_tracking_id, wal.service_run_id, wal.source_policy_revision, " +
		"wal.capture_consent_epoch, wal.clock_domain_id, wal.session_manifest_revision, " +
		"wal.lifecycle_lease_generation, wal.source_instance_id, wal.registration_generation, " +
		"wal.physical_configuration_fingerprint, wal.authorization_revision, " +
		"wal.authorization_fingerprint, wal.config_revision, wal.payload_checksum, " +
		"wal.integrity_identity " +
		"FROM source_event_wal AS wal " +
		"WHERE wal.source_kind = ? AND wal.created_at_ms < ? AND wal.admission_ordinal <= ? "

private const val STEPS_WAL_PRUNE_SCOPE =
	"AND NOT (" +
		"wal.logical_tracking_id IS NOT NULL AND wal.service_run_id IS NOT NULL AND " +
		"wal.admission_ordinal = (" +
		"SELECT MAX(candidate.admission_ordinal) FROM source_event_wal AS candidate " +
		"WHERE candidate.source_kind = wal.source_kind " +
		"AND candidate.source_instance_id = wal.source_instance_id " +
		"AND candidate.registration_generation = wal.registration_generation " +
		"AND candidate.logical_tracking_id = wal.logical_tracking_id " +
		"AND candidate.service_run_id = wal.service_run_id " +
		"AND (candidate.authorization_purpose_eligibility_mask & ?) != 0" +
		") AND (" +
		"EXISTS (SELECT 1 FROM provider_registration_generation AS registration " +
		"WHERE registration.source_kind = wal.source_kind " +
		"AND registration.source_instance_id = wal.source_instance_id " +
		"AND registration.registration_generation = wal.registration_generation " +
		"AND registration.status IN ('RESERVED', 'ACTIVE', 'RETIRING')) " +
		"OR EXISTS (SELECT 1 FROM source_service_run AS run " +
		"WHERE run.service_run_id = wal.service_run_id " +
		"AND run.logical_tracking_id = wal.logical_tracking_id " +
		"AND (run.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED') OR " +
		"(run.completed_at_ms IS NULL AND NOT (" +
		V28_MIGRATION_TERMINAL_RUN_SQL + "))))" +
		")) ORDER BY wal.created_at_ms, wal.admission_ordinal LIMIT ?"




internal fun List<StepsWalPruneCandidate>.boundedOwnerlessRunPrefix(): List<StepsWalPruneCandidate> {
	val distinctRuns = linkedSetOf<OwnerlessStepsManifestRunKey>()
	forEachIndexed { index, candidate ->
		val runKey = candidate.ownerlessRunKey ?: return@forEachIndexed
		if (runKey !in distinctRuns && distinctRuns.size >= OWNERLESS_STEPS_MAX_DISTINCT_RUNS) {
			return take(index)
		}
		distinctRuns += runKey
	}
	return this
}


internal fun SupportSQLiteDatabase.hasExactCountDomainOwner(
	key: StepsCountDomainOwnerLookupKey,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.steps_count_domain_owner_revision " +
		"WHERE typeof(owner_kind) = 'text' AND owner_kind = ? " +
		"AND typeof(owner_identity) = 'text' AND owner_identity = ? " +
		"AND typeof(owner_revision) = 'integer' AND owner_revision = ?",
	arrayOf<Any?>(key.ownerKind, key.ownerIdentity, key.ownerRevision),
).let { count ->
	if (count !in 0L..1L) {
		throw CountDomainStoredEvidenceException()
	}
	count == 1L
}

internal fun SupportSQLiteDatabase.hasExactOwnerlessStepsWalAttributionStorage(
	wal: SourceEventWalEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.source_event_wal WHERE admission_ordinal = ? " +
		"AND typeof(admission_ordinal) = 'integer' " +
		"AND typeof(event_id) = 'text' " +
		"AND typeof(provider_dedup_key) IN ('null', 'text') " +
		"AND typeof(delivery_identity) IN ('null', 'text') " +
		"AND typeof(delivery_unit_index) IN ('null', 'integer') " +
		"AND typeof(delivery_unit_count) IN ('null', 'integer') " +
		"AND typeof(logical_tracking_id) = 'text' " +
		"AND typeof(service_run_id) = 'text' " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(source_instance_id) = 'text' " +
		"AND typeof(registration_generation) = 'integer' " +
		"AND typeof(physical_configuration_fingerprint) = 'text' " +
		"AND typeof(authorization_revision) = 'integer' " +
		"AND typeof(authorization_purpose_eligibility_mask) = 'integer' " +
		"AND typeof(authorization_fingerprint) = 'text' " +
		"AND typeof(source_sequence) = 'integer' " +
		"AND typeof(config_revision) = 'integer' " +
		"AND typeof(plan_attribution) = 'integer' AND plan_attribution = ? " +
		"AND typeof(clock_domain_id) = 'text' " +
		"AND typeof(observed_elapsed_nanos) = 'integer' " +
		"AND typeof(observed_interval_start_nanos) IN ('null', 'integer') " +
		"AND typeof(received_elapsed_nanos) = 'integer' " +
		"AND typeof(received_wall_time_ms) IN ('null', 'integer') " +
		"AND typeof(wall_time_ms) = 'integer' " +
		"AND typeof(wall_time_uncertainty_ms) = 'integer' " +
		"AND typeof(captured_collected_data_epoch) = 'integer' " +
		"AND typeof(activity_automation_epoch) IN ('null', 'integer') " +
		"AND typeof(source_policy_revision) = 'integer' " +
		"AND typeof(capture_consent_epoch) = 'integer' " +
		"AND typeof(session_manifest_revision) = 'integer' " +
		"AND typeof(lifecycle_lease_generation) = 'integer' " +
		"AND typeof(acquired_at_ms) = 'integer' " +
		"AND typeof(quality_flags) = 'integer' " +
		"AND typeof(quality_confidence) IN ('null', 'real') " +
		"AND typeof(payload_version) = 'integer' " +
		"AND typeof(payload) = 'blob' " +
		"AND typeof(payload_checksum) = 'text' " +
		"AND typeof(integrity_identity) = 'text' " +
		"AND typeof(created_at_ms) = 'integer'",
	arrayOf<Any?>(wal.admissionOrdinal, OWNERLESS_STEPS_CAPTURED_REGISTRATION_PLAN_ATTRIBUTION),
) == 1L

internal fun SupportSQLiteDatabase.hasExactOwnerlessStepsProviderRegistrationStorage(
	registration: ProviderRegistrationGenerationEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.provider_registration_generation " +
		"WHERE source_kind = ? AND registration_generation = ? " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(registration_generation) = 'integer' " +
		"AND typeof(source_instance_id) = 'text' " +
		"AND typeof(owner_scope) = 'text' " +
		"AND typeof(clock_domain_id) = 'text' " +
		"AND typeof(physical_configuration_fingerprint) = 'text' " +
		"AND typeof(collected_data_epoch) = 'integer' " +
		"AND typeof(provider_residency) = 'text' " +
		"AND typeof(provider_process_incarnation_id) IN ('null', 'text') " +
		"AND typeof(status) = 'text' " +
		"AND typeof(reserved_at_ms) = 'integer' " +
		"AND typeof(reserved_elapsed_realtime_nanos) = 'integer' " +
		"AND typeof(accepted_at_ms) IN ('null', 'integer') " +
		"AND typeof(accepted_elapsed_realtime_nanos) IN ('null', 'integer') " +
		"AND typeof(retired_at_ms) IN ('null', 'integer') " +
		"AND typeof(retired_elapsed_realtime_nanos) IN ('null', 'integer') " +
		"AND typeof(failure_code) IN ('null', 'text') " +
		"AND typeof(capture_callback_barrier_authorization_revision) = 'integer'",
	arrayOf<Any?>(registration.sourceKind, registration.registrationGeneration),
) == 1L


internal fun SupportSQLiteDatabase.hasMalformedStepsWalPruneLaneAttribution(): Boolean =
	longForQuery(
		"SELECT EXISTS(SELECT 1 FROM main.source_product_projection_lane WHERE " +
			"(CAST(source_kind AS INTEGER) = ? OR " +
			"CAST(projection_id AS TEXT) = ?) AND (" +
			"typeof(source_kind) != 'integer' OR source_kind != ? OR " +
			"typeof(projection_id) != 'text' OR projection_id != ? OR " +
			"typeof(projection_version) != 'integer' OR projection_version != ?) LIMIT 1)",
		arrayOf<Any?>(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
		),
	) == 1L

internal fun SupportSQLiteDatabase.hasExactStepsWalPruneLaneStorage(
	lane: SourceProductProjectionLaneEntity,
): Boolean = longForQuery(
	"SELECT COUNT(*) FROM main.source_product_projection_lane " +
		"WHERE source_kind = ? AND binding_generation = ? " +
		"AND typeof(source_kind) = 'integer' " +
		"AND typeof(binding_generation) = 'integer' " +
		"AND typeof(projection_id) = 'text' " +
		"AND typeof(projection_version) = 'integer' " +
		"AND typeof(capture_mode_mask) = 'integer' " +
		"AND typeof(product_stage) = 'text' " +
		"AND typeof(activated_rollout_revision) = 'integer' " +
		"AND typeof(activation_ordinal) = 'integer' " +
		"AND typeof(contiguous_admission_ordinal) = 'integer' " +
		"AND typeof(capture_admission_cutoff_ordinal) IN ('null', 'integer') " +
		"AND typeof(retention_required) = 'integer' " +
		"AND typeof(status) = 'text' " +
		"AND typeof(terminal_disposition) IN ('null', 'text') " +
		"AND typeof(terminal_at_ms) IN ('null', 'integer') " +
		"AND typeof(installed_at_ms) = 'integer' " +
		"AND typeof(updated_at_ms) = 'integer'",
	arrayOf<Any?>(lane.sourceKind, lane.bindingGeneration),
) == 1L


internal fun SupportSQLiteDatabase.deleteCurrentStepsWalCandidates(
	candidates: List<StepsWalPruneCandidate>,
): Int {
	check(candidates.isNotEmpty() && candidates.none(StepsWalPruneCandidate::isLegacyV27))
	val predicate = candidates.joinToString(" OR ") {
		"(admission_ordinal = ? AND event_id = ? AND " +
			"authorization_purpose_eligibility_mask = ?)"
	}
	return compileStatement(
		"DELETE FROM source_event_wal WHERE source_kind = ? AND ($predicate)",
	).use { statement ->
		var index = 1
		statement.bindLong(index++, SourceDestinationOwnerEntity.SOURCE_STEPS.toLong())
		candidates.forEach { candidate ->
			statement.bindLong(index++, candidate.cursor.admissionOrdinal)
			statement.bindString(index++, candidate.eventId)
			statement.bindLong(index++, candidate.authorizationPurposeEligibilityMask)
		}
		statement.executeUpdateDelete()
	}.also { deleted ->
		if (deleted != candidates.size) {
			throw CountDomainStoredEvidenceException()
		}
	}
}

