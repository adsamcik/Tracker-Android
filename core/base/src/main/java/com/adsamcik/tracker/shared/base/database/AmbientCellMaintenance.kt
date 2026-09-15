package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AmbientCellRetentionCommand(
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
	val beforeMs: Long,
	val expectedCollectedDataEpoch: Long,
	val expectedAmbientConsentEpoch: Long,
	val appliedAtMs: Long,
) {
	init {
		require(retentionPolicyId.isNotBlank())
		require(retentionApprovalRevision > 0L)
		require(beforeMs >= 0L && expectedCollectedDataEpoch >= 0L)
		require(expectedAmbientConsentEpoch >= 0L && appliedAtMs >= beforeMs)
	}
}

sealed interface AmbientCellRetentionResult {
	data class Pruned(
		val localLineages: Int,
		val importedArchives: Int,
		val gaps: Int,
	) : AmbientCellRetentionResult

	data object NoChange : AmbientCellRetentionResult
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellRetentionResult
}

sealed interface AmbientCellDeletionResult {
	data class Deleted(
		val localRevisionCount: Long,
		val importedFactCount: Long,
		val importedGapCount: Long,
		val deletionGeneration: Long,
	) : AmbientCellDeletionResult

	data object AlreadyDeleted : AmbientCellDeletionResult
	data class Unavailable(val reason: AmbientCellMaintenanceUnavailableReason) :
		AmbientCellDeletionResult
}

enum class AmbientCellMaintenanceUnavailableReason {
	SOURCE_AUTHORITY_UNAVAILABLE,
	RETENTION_APPROVAL_MISMATCH,
	RETENTION_BOUNDARY_MISMATCH,
	COLLECTED_DATA_EPOCH_CHANGED,
	CONSENT_STILL_ACTIVE,
	AMBIENT_DEMAND_ACTIVE,
	AMBIENT_PROVIDER_ACTIVE,
	DEPENDENCY_OVERFLOW,
	DELETION_GENERATION_EXHAUSTED,
}

suspend fun AppDatabase.pruneAmbientCell(
	command: AmbientCellRetentionCommand,
): AmbientCellRetentionResult = withTransaction {
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != command.expectedCollectedDataEpoch) {
		return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	if (state.retainedFromMs != command.beforeMs) {
		return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.RETENTION_BOUNDARY_MISMATCH,
		)
	}
	val dao = ambientCellFactDao()
	val authority = dao.latestAuthority()
	if (authority?.isActive != true ||
		authority.collectedDataEpoch != command.expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != command.expectedAmbientConsentEpoch
	) {
		return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	if (authority.retentionPolicyId != command.retentionPolicyId ||
		authority.retentionApprovalRevision != command.retentionApprovalRevision
	) {
		return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.RETENTION_APPROVAL_MISMATCH,
		)
	}
	currentCoroutineContext().ensureActive()
	val localIds = dao.localFactIdsBefore(
		command.beforeMs,
		command.retentionPolicyId,
		MAINTENANCE_LIMIT + 1,
	)
	val archiveIds = dao.importedArchiveIdsBefore(
		command.beforeMs,
		command.retentionPolicyId,
		MAINTENANCE_LIMIT + 1,
	)
	if (localIds.size > MAINTENANCE_LIMIT || archiveIds.size > MAINTENANCE_LIMIT) {
		return@withTransaction cellUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (localIds.isEmpty() && archiveIds.isEmpty()) {
		val gaps = dao.deleteGapsBefore(command.beforeMs)
		if (gaps == 0) return@withTransaction AmbientCellRetentionResult.NoChange
		check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
		return@withTransaction AmbientCellRetentionResult.Pruned(0, 0, gaps)
	}
	if (localIds.isNotEmpty()) {
		localIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.deleteLocalCursors(ids)
			dao.deleteLocalFacts(ids)
		}
	}
	if (archiveIds.isNotEmpty()) {
		archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.insertImportTombstones(ids.map { archiveId ->
				AmbientCellFactIntegrity.createImportTombstone(
					archiveId,
					command.expectedCollectedDataEpoch,
					1L,
					command.appliedAtMs,
				)
			})
			dao.deleteImportReceipts(ids)
			dao.deleteImportedGaps(ids)
			dao.deleteImportedFacts(ids)
		}
	}
	val gaps = dao.deleteGapsBefore(command.beforeMs)
	check(sourceEvidenceStateDao().incrementRevision(command.appliedAtMs) == 1)
	AmbientCellRetentionResult.Pruned(localIds.size, archiveIds.size, gaps)
}

suspend fun AppDatabase.deleteAmbientCellAfterConsentReset(
	expectedCollectedDataEpoch: Long,
	expectedRevokedConsentEpoch: Long,
	deletedAtMs: Long,
): AmbientCellDeletionResult = withTransaction {
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedRevokedConsentEpoch >= 0L)
	require(deletedAtMs >= 0L)
	val state = sourceEvidenceStateDao().get()
		?: return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED,
		)
	}
	val dao = ambientCellFactDao()
	val authority = dao.latestAuthority()
	if (authority == null || authority.state != AmbientCellAuthorityEntity.STATE_REVOKED ||
		authority.collectedDataEpoch != expectedCollectedDataEpoch ||
		authority.ambientConsentEpoch != expectedRevokedConsentEpoch
	) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.SOURCE_AUTHORITY_UNAVAILABLE,
		)
	}
	val policyAuthority = sourcePolicyDao().authority()
	val policy = policyAuthority?.takeIf {
		it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
	}?.let {
		sourcePolicyDao().policyAtRevision(
			it.currentPolicyRevision,
			SourceDestinationOwnerEntity.SOURCE_CELL,
		)
	}
	if (policy == null || policy.ambientPersistenceEligible || policy.ambientConsentEpoch != null) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.CONSENT_STILL_ACTIVE,
		)
	}
	val activeDemands = sourceBrokerDao().activeDemandsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		MAINTENANCE_LIMIT + 1,
	)
	if (activeDemands.size > MAINTENANCE_LIMIT) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	if (activeDemands.any { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.AMBIENT_DEMAND_ACTIVE,
		)
	}
	val registrations = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		SourceDestinationOwnerEntity.SOURCE_CELL,
		MAX_REGISTRATIONS + 1,
	)
	if (registrations.size > MAX_REGISTRATIONS) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	for (registration in registrations) {
		val members = sourceBrokerDao().latestAuthorization(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			registration.registrationGeneration,
		)
		if (members.any {
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible
			}
		) {
			return@withTransaction cellDeletionUnavailable(
				AmbientCellMaintenanceUnavailableReason.AMBIENT_PROVIDER_ACTIVE,
			)
		}
	}
	val localCount = dao.localFactCount()
	val importedCount = dao.importedFactCount()
	val importedGapCount = dao.importedGapCount()
	val archiveIds = (
		dao.allImportedArchiveIds(MAINTENANCE_LIMIT + 1) +
			dao.allImportReceiptArchiveIds(MAINTENANCE_LIMIT + 1)
	).distinct()
	if (archiveIds.size > MAINTENANCE_LIMIT) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DEPENDENCY_OVERFLOW,
		)
	}
	val previousGeneration = dao.latestDeletionMarker(expectedCollectedDataEpoch)
		?.deletionGeneration ?: 0L
	if (previousGeneration == Long.MAX_VALUE) {
		return@withTransaction cellDeletionUnavailable(
			AmbientCellMaintenanceUnavailableReason.DELETION_GENERATION_EXHAUSTED,
		)
	}
	if (localCount == 0L && importedCount == 0L && importedGapCount == 0L &&
		archiveIds.isEmpty()
	) {
		return@withTransaction AmbientCellDeletionResult.AlreadyDeleted
	}
	val nextGeneration = previousGeneration + 1L
	dao.insertDeletionMarker(AmbientCellFactIntegrity.createDeletionMarker(
		expectedCollectedDataEpoch,
		nextGeneration,
		expectedRevokedConsentEpoch,
		"AMBIENT_CELL_CONSENT_RESET",
		deletedAtMs,
	))
	archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
		dao.insertImportTombstones(ids.map { archiveId ->
			AmbientCellFactIntegrity.createImportTombstone(
				archiveId,
				expectedCollectedDataEpoch,
				nextGeneration,
				deletedAtMs,
			)
		})
	}
	dao.deleteAllLocalCursors()
	dao.deleteAllLocalFacts()
	dao.deleteAllGaps()
	if (archiveIds.isNotEmpty()) {
		archiveIds.chunked(SQLITE_ID_CHUNK_SIZE).forEach { ids ->
			dao.deleteImportReceipts(ids)
			dao.deleteImportedGaps(ids)
			dao.deleteImportedFacts(ids)
		}
	}
	check(sourceEvidenceStateDao().incrementRevision(deletedAtMs) == 1)
	AmbientCellDeletionResult.Deleted(
		localCount,
		importedCount,
		importedGapCount,
		nextGeneration,
	)
}

private fun cellUnavailable(reason: AmbientCellMaintenanceUnavailableReason) =
	AmbientCellRetentionResult.Unavailable(reason)

private fun cellDeletionUnavailable(reason: AmbientCellMaintenanceUnavailableReason) =
	AmbientCellDeletionResult.Unavailable(reason)

private const val MAINTENANCE_LIMIT = 16_384
private const val MAX_REGISTRATIONS = 32
private const val SQLITE_ID_CHUNK_SIZE = 400
