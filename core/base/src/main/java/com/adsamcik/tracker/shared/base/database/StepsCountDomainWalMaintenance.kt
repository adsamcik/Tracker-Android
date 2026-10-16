package com.adsamcik.tracker.shared.base.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun SupportSQLiteDatabase.authenticateStepsWalMaintenanceSnapshot(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	auditStepsWalMaintenancePages(session)
	auditStepsRegistrationMaintenancePages(session)
	auditStepsRunMaintenancePages(session)
	session.requireUnchangedMutationVersion()
}

private suspend fun SupportSQLiteDatabase.auditStepsWalMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	var legacyAuthority: LegacyV27StepsWalRetentionAuthority? = null
	auditStepsMaintenanceRows(
		table = "source_event_wal",
		columns = "admission_ordinal, event_id, created_at_ms, source_kind, " +
			"source_instance_id, registration_generation, " +
			"authorization_purpose_eligibility_mask, logical_tracking_id, service_run_id",
		checkpoint = StepsCountDomainMaintenanceCheckpoint.WAL_DOMAIN_PAGE_AUTHENTICATED,
		session = session,
	) { cursor ->
		val rowId = cursor.requiredLong("maintenance_rowid")
		if (cursor.requireMaintenanceSourceKind() == SourceDestinationOwnerEntity.SOURCE_STEPS.toLong()) {
			val identity = cursor.requireStepsWalMaintenanceIdentity()
			if (identity.eligibilityMask == 0L) {
				val authority = legacyAuthority ?: requireLegacyV27StepsWalRetentionAuthority().also { legacyAuthority = it }
				requireAuthenticatedLegacyV27StepsWal(identity.admissionOrdinal, authority)
			}
		}
		rowId
	}
}

private suspend fun SupportSQLiteDatabase.auditStepsRegistrationMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	auditStepsMaintenanceRows(
		table = "provider_registration_generation",
		columns = "source_kind, registration_generation, source_instance_id, status",
		checkpoint = StepsCountDomainMaintenanceCheckpoint.WAL_REGISTRATION_PAGE_AUTHENTICATED,
		session = session,
	) { cursor ->
		val rowId = cursor.requiredLong("maintenance_rowid")
		if (cursor.requireMaintenanceSourceKind() == SourceDestinationOwnerEntity.SOURCE_STEPS.toLong()) {
			cursor.requireStepsRegistrationMaintenanceIdentity()
		}
		rowId
	}
}

private suspend fun SupportSQLiteDatabase.auditStepsRunMaintenancePages(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	auditStepsMaintenanceRows(
		table = "source_service_run",
		columns = "service_run_id, logical_tracking_id, state, completed_at_ms, " +
			"completion_reason, runtime_acknowledgement, runtime_failure_code",
		checkpoint = StepsCountDomainMaintenanceCheckpoint.WAL_RUN_PAGE_AUTHENTICATED,
		session = session,
	) { cursor ->
		val rowId = cursor.requiredLong("maintenance_rowid")
		cursor.requireStepsRunMaintenanceIdentity()
		rowId
	}
}

private data class StepsWalMaintenanceIdentity(val admissionOrdinal: Long, val eligibilityMask: Long)

private suspend fun SupportSQLiteDatabase.auditStepsMaintenanceRows(
	table: String,
	columns: String,
	checkpoint: StepsCountDomainMaintenanceCheckpoint,
	session: StepsCountDomainStore.OwnerMaintenanceSession,
	readRow: (android.database.Cursor) -> Long,
) {
	val (maximumRowId, expectedCount) = maintenanceRowIdBoundary(table)
	var afterRowId: Long? = null
	var auditedCount = 0L
	while (maximumRowId != null) {
		currentCoroutineContext().ensureActive()
		val page = queryMaintenanceRowIdPage(
			table = "main.`$table`",
			columns = columns,
			afterRowId = afterRowId,
			maximumRowId = maximumRowId,
			pageSize = WAL_MAINTENANCE_PAGE_SIZE,
			map = readRow,
		)
		if (page.isEmpty()) {
			break
		}
		if (afterRowId != null && page.first() <= afterRowId) {
			throw CountDomainStoredEvidenceException()
		}
		auditedCount = Math.addExact(auditedCount, page.size.toLong())
		afterRowId = page.last()
		session.checkpoint(checkpoint)
		requireMaintenanceTotalChanges(session)
		if (page.size < WAL_MAINTENANCE_PAGE_SIZE) {
			break
		}
	}
	if (auditedCount != expectedCount) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun SupportSQLiteDatabase.requireMaintenanceTotalChanges(
	session: StepsCountDomainStore.OwnerMaintenanceSession,
) {
	if (totalChanges() != session.expectedTotalChanges()) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requireMaintenanceSourceKind(): Long {
	val sourceKind = requiredLong("source_kind")
	if (sourceKind !in 1L..6L) {
		throw CountDomainStoredEvidenceException()
	}
	return sourceKind
}

private fun android.database.Cursor.requireStepsWalMaintenanceIdentity(): StepsWalMaintenanceIdentity {
	val admissionOrdinal = requiredLong("admission_ordinal")
	val eventId = requiredText("event_id")
	val createdAtMs = requiredLong("created_at_ms")
	val sourceInstanceId = requiredText("source_instance_id")
	val registrationGeneration = requiredLong("registration_generation")
	val eligibilityMask = requiredLong("authorization_purpose_eligibility_mask")
	val logicalTrackingId = nullableText("logical_tracking_id")
	val serviceRunId = nullableText("service_run_id")
	requireStepsWalMaintenanceFields(admissionOrdinal, eventId, createdAtMs, sourceInstanceId, registrationGeneration)
	requireStepsWalMaintenanceRun(logicalTrackingId, serviceRunId)
	if (eligibilityMask != 0L && (eligibilityMask !in 1L..STEPS_WAL_ALLOWED_PURPOSE_MASK ||
		(eligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L) != (logicalTrackingId != null))
	) {
		throw CountDomainStoredEvidenceException()
	}
	return StepsWalMaintenanceIdentity(admissionOrdinal, eligibilityMask)
}

private fun requireStepsWalMaintenanceFields(
	admissionOrdinal: Long,
	eventId: String,
	createdAtMs: Long,
	sourceInstanceId: String,
	registrationGeneration: Long,
) {
	if (admissionOrdinal <= 0L || eventId.isBlank() || createdAtMs < 0L) {
		throw CountDomainStoredEvidenceException()
	}
	if (sourceInstanceId.isBlank() || registrationGeneration <= 0L) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun requireStepsWalMaintenanceRun(logicalTrackingId: String?, serviceRunId: String?) {
	if ((logicalTrackingId == null) != (serviceRunId == null) ||
		logicalTrackingId?.isBlank() == true || serviceRunId?.isBlank() == true
	) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requireStepsRegistrationMaintenanceIdentity() {
	val generation = requiredLong("registration_generation")
	val sourceInstanceId = requiredText("source_instance_id")
	val status = requiredText("status")
	if (generation <= 0L || sourceInstanceId.isBlank() ||
		status !in setOf("RESERVED", "ACTIVE", "RETIRING", "RETIRED", "FAILED")
	) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun android.database.Cursor.requireStepsRunMaintenanceIdentity() {
	val serviceRunId = requiredText("service_run_id")
	val logicalTrackingId = requiredText("logical_tracking_id")
	val state = requiredText("state")
	val completedAtMs = nullableLong("completed_at_ms")
	val completionReason = nullableText("completion_reason")
	val runtimeAcknowledgement = requiredText("runtime_acknowledgement")
	val runtimeFailureCode = nullableText("runtime_failure_code")
	val terminal = state in setOf("FINALIZED", "CLOSED", "FAILED")
	val migratedTerminal = isMigratedMaintenanceTerminal(
		state, completedAtMs, completionReason, runtimeAcknowledgement, runtimeFailureCode,
	)
	if (serviceRunId.isBlank() || logicalTrackingId.isBlank() ||
		state !in setOf("IDLE", "STARTING", "ACTIVE", "RECONFIGURING", "STOPPING", "FINALIZED", "CLOSED", "FAILED")
	) {
		throw CountDomainStoredEvidenceException()
	}
	if (completedAtMs?.let { it < 0L } == true || terminal != (completedAtMs != null || migratedTerminal)) {
		throw CountDomainStoredEvidenceException()
	}
}

private fun isMigratedMaintenanceTerminal(
	state: String,
	completedAtMs: Long?,
	completionReason: String?,
	runtimeAcknowledgement: String,
	runtimeFailureCode: String?,
): Boolean = state == "FINALIZED" && completedAtMs == null && !completionReason.isNullOrBlank() &&
	runtimeAcknowledgement == "TERMINAL_FAILURE" && runtimeFailureCode == V28_MIGRATION_INTERRUPTION_REASON



private const val WAL_MAINTENANCE_PAGE_SIZE = 128

internal const val STEPS_WAL_ALLOWED_PURPOSE_MASK =
	SourceBrokerPurpose.CONTROL_MASK or SourceBrokerPurpose.MASK_SESSION_CAPTURE
